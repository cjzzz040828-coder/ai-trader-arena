"""
动态打板候选池构建器（多池子版）。

每个池子的规则由 pool_registry 提供（板块/价格/市值/ST 等过滤条件）。每周五 15:30 cron
触发 build_pool 对所有 autoRefresh=true 的池子顺序重建：
  1. 主板/板块筛选（按池子规则的 markets，去 ST/退）
  2. 价格区间过滤
  3. 流通市值过滤（price × liutongguben）

落盘约定：
  - 当前快照：data/pools/{name}.json
  - 历史归档：data/pool_history/{name}/{YYYY-MM-DD}.json（保留 settings.pool_history_keep 份，超过删旧）

兼容：旧版的 data/dynamic_pool.json 启动时自动迁移到 data/pools/default.json。
"""
from __future__ import annotations

import json
import shutil
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any

import pandas as pd
from loguru import logger

from app.config import settings
from app.core import pool_registry
from app.core.mootdx_client import ALL_MARKET_STOCKS, STOCK_NAMES, mootdx_client

# 按池子名独立的 build 锁，防同名重复构建；不同池子可顺序串行但每个都受锁保护
_build_locks: dict[str, threading.Lock] = {}
_building: set[str] = set()
_locks_lock = threading.Lock()


def _get_build_lock(name: str) -> threading.Lock:
    with _locks_lock:
        lock = _build_locks.get(name)
        if lock is None:
            lock = threading.Lock()
            _build_locks[name] = lock
        return lock


def _data_root() -> Path:
    return Path(settings.pool_file_path).parent


def _snapshot_path(pool_name: str) -> Path:
    return _data_root() / "pools" / f"{pool_name}.json"


def _history_dir(pool_name: str) -> Path:
    return _data_root() / "pool_history" / pool_name


def _classify(code: str) -> str | None:
    if not code or len(code) != 6 or not code.isdigit():
        return None
    p3 = code[:3]
    if p3 in ("600", "601", "603", "605"):
        return "MAIN_SH"
    if p3 == "688":
        return "STAR"
    if p3 == "000":
        return "MAIN_SZ"
    if p3 == "002":
        return "SME"
    if p3 == "300":
        return "GEM"
    return None


def _name_excluded(name: str, exclude_st: bool, exclude_delisting: bool) -> bool:
    if not name:
        return False
    if exclude_st and "ST" in name.upper():
        return True
    if exclude_delisting and "退" in name:
        return True
    return False


def _chunks(lst: list, size: int):
    for i in range(0, len(lst), size):
        yield lst[i:i + size]


def _fetch_prices(client, codes: list[str]) -> dict[str, float]:
    out: dict[str, float] = {}
    BATCH = 80
    for batch in _chunks(codes, BATCH):
        try:
            df = client.quotes(symbol=batch)
        except Exception as e:
            logger.warning(f"[pool] quotes batch error: {e}")
            continue
        if df is None or (isinstance(df, pd.DataFrame) and df.empty):
            continue
        for _, row in df.iterrows():
            code = str(row.get("code", "")).zfill(6)
            try:
                price = float(row.get("price") or 0)
            except (TypeError, ValueError):
                price = 0
            if code and price > 0:
                out[code] = price
    return out


def _fetch_liutong(client, code: str) -> float:
    try:
        df = client.finance(symbol=code)
    except Exception as e:
        logger.debug(f"[pool] finance {code} error: {e}")
        return 0.0
    if df is None or (isinstance(df, pd.DataFrame) and df.empty):
        return 0.0
    try:
        return float(df.iloc[0].get("liutongguben") or 0)
    except (TypeError, ValueError):
        return 0.0


# 主板涨停阈值。用 9.8% 而非 10% 是给四舍五入/价格步进留余量（如 11.0×1.1=12.1 精确，
# 但部分票收盘价与理论涨停价有 1 分钱误差，9.8% 能稳稳覆盖真实涨停）。
LIMIT_UP_PCT = 0.098


def _compute_kline_metrics(bars: list[dict], ma_period: int = 30) -> dict:
    """从日 K（按时间升序，末尾最新）算技术指标。

    返回：
      ma30: 最近 ma_period 根 close 均值（不足返回 None；键名固定 ma30 供前端展示）
      ma_period: 实际用的均线周期
      last_low: 最新交易日最低价
      last_date: 最新交易日（YYYY-MM-DD）
      limit_up_days: 近 7 个交易日中涨停的日期列表
      prev_day_limit_up: 前一交易日是否涨停
    涨停判定：(close - 前收) / 前收 ≥ LIMIT_UP_PCT，前收取相邻前一根 close。
    """
    rows = [b for b in (bars or []) if b and b.get("close")]
    if not rows:
        return {}

    closes = [float(b["close"]) for b in rows]
    last = rows[-1]
    last_date = str(last.get("datetime", ""))[:10]

    ma_n = max(1, int(ma_period or 30))
    ma30 = round(sum(closes[-ma_n:]) / ma_n, 3) if len(closes) >= ma_n else None

    # 逐根算涨幅（首根无前收，跳过）
    limit_up_flags: list[tuple[str, bool]] = []
    for i in range(1, len(rows)):
        prev_close = closes[i - 1]
        if prev_close <= 0:
            limit_up_flags.append((str(rows[i].get("datetime", ""))[:10], False))
            continue
        pct = (closes[i] - prev_close) / prev_close
        limit_up_flags.append((str(rows[i].get("datetime", ""))[:10], pct >= LIMIT_UP_PCT))

    recent7 = limit_up_flags[-7:]
    limit_up_days = [d for d, up in recent7 if up]
    # 前一交易日 = 倒数第二根
    prev_day_limit_up = bool(limit_up_flags[-2][1]) if len(limit_up_flags) >= 2 else False

    return {
        "ma30": ma30,
        "ma_period": ma_n,
        "last_low": round(float(last.get("low") or 0), 3),
        "last_date": last_date,
        "limit_up_days": limit_up_days,
        "prev_day_limit_up": prev_day_limit_up,
    }


def _passes_kline_rules(metrics: dict, rules: dict) -> bool:
    """按 3 条技术规则判定。规则值为 0 / False 表示不检查该项。"""
    if not metrics:
        return False

    need_days = int(rules.get("require_limit_up_in_days", 0) or 0)
    if need_days > 0 and not metrics.get("limit_up_days"):
        return False

    ma_n = int(rules.get("require_low_above_ma", 0) or 0)
    if ma_n > 0:
        ma = metrics.get("ma30")
        last_low = metrics.get("last_low")
        if ma is None or last_low is None or last_low <= ma:
            return False

    if rules.get("exclude_prev_day_limit_up", False) and metrics.get("prev_day_limit_up"):
        return False

    return True



def build_pool(pool_name: str = "default") -> dict[str, Any]:
    """按 pool_registry 里的规则构建指定池子。约 60-120 秒，串行执行。"""
    pool_def = pool_registry.get_pool(pool_name)
    if not pool_def:
        return {"ok": False, "reason": f"池子 '{pool_name}' 不存在"}
    lock = _get_build_lock(pool_name)
    if not lock.acquire(blocking=False):
        logger.warning(f"[pool:{pool_name}] another build in progress, skip")
        return {"ok": False, "reason": "another build in progress"}
    _building.add(pool_name)
    try:
        t0 = time.time()
        if not mootdx_client.ready:
            return {"ok": False, "reason": "mootdx not ready"}
        if not ALL_MARKET_STOCKS:
            return {"ok": False, "reason": "all_market_stocks not loaded"}

        rules = pool_def["rules"]
        markets_keep = set(rules["markets"])
        exclude_st = rules.get("exclude_st", True)
        exclude_delisting = rules.get("exclude_delisting", True)
        min_price = float(rules["min_price"])
        max_price = float(rules["max_price"])
        min_cap = float(rules["min_market_cap"])
        max_cap = float(rules["max_market_cap"])
        require_limit_up_in_days = int(rules.get("require_limit_up_in_days", 0) or 0)
        require_low_above_ma = int(rules.get("require_low_above_ma", 0) or 0)
        exclude_prev_day_limit_up = bool(rules.get("exclude_prev_day_limit_up", False))
        kline_filter_on = require_limit_up_in_days > 0 or require_low_above_ma > 0 or exclude_prev_day_limit_up

        # 步骤 1：板块 + 名称过滤
        step1: list[dict] = []
        for s in ALL_MARKET_STOCKS:
            code = s.get("code", "")
            name = s.get("name") or STOCK_NAMES.get(code, code)
            seg = _classify(code)
            if not seg or seg not in markets_keep:
                continue
            if _name_excluded(name, exclude_st, exclude_delisting):
                continue
            step1.append({"code": code, "name": name, "market": s.get("market"), "segment": seg})
        logger.info(f"[pool:{pool_name}] step1 板块+ST 过滤后 {len(step1)} 只")

        # 步骤 2：价格过滤
        codes_to_quote = [s["code"] for s in step1]
        with mootdx_client._client_lock:
            client = mootdx_client._client
            if client is None:
                return {"ok": False, "reason": "mootdx client is None"}
            price_map = _fetch_prices(client, codes_to_quote)
        step2 = [s for s in step1 if min_price <= price_map.get(s["code"], 0) <= max_price]
        for s in step2:
            s["price"] = price_map[s["code"]]
        logger.info(f"[pool:{pool_name}] step2 价格 [{min_price}, {max_price}] 过滤后 {len(step2)} 只")

        # 步骤 3：流通市值过滤
        survivors: list[dict] = []
        with mootdx_client._client_lock:
            client = mootdx_client._client
            for i, s in enumerate(step2):
                liutong = _fetch_liutong(client, s["code"])
                if liutong <= 0:
                    continue
                mktcap = s["price"] * liutong
                if min_cap <= mktcap <= max_cap:
                    s["liutongshizhi"] = mktcap
                    s["liutongguben"] = liutong
                    survivors.append(s)
                if (i + 1) % 500 == 0:
                    logger.info(f"[pool:{pool_name}] step3 进度 {i + 1}/{len(step2)}, 已入选 {len(survivors)}")
        logger.info(
            f"[pool:{pool_name}] step3 流通市值 [{min_cap / 1e8:.0f}亿, {max_cap / 1e8:.0f}亿] 过滤后 {len(survivors)} 只"
        )
        step3_count = len(survivors)

        # 步骤 4：K 线技术过滤（近 N 日涨停 / 当日最低>MA / 前一日未涨停）。
        # 只对 step3 幸存者逐只拉日 K，规则全关时跳过整步。
        if kline_filter_on:
            ma_period = require_low_above_ma or 30
            kline_survivors: list[dict] = []
            with mootdx_client._client_lock:
                client = mootdx_client._client
                for i, s in enumerate(survivors):
                    bars = mootdx_client.bars(s["code"], frequency=9, count=max(40, ma_period + 10))
                    metrics = _compute_kline_metrics(bars, ma_period=ma_period)
                    if _passes_kline_rules(metrics, rules):
                        s["ma30"] = metrics.get("ma30")
                        s["last_low"] = metrics.get("last_low")
                        s["last_date"] = metrics.get("last_date")
                        s["limit_up_days"] = metrics.get("limit_up_days")
                        s["prev_day_limit_up"] = metrics.get("prev_day_limit_up")
                        kline_survivors.append(s)
                    if (i + 1) % 200 == 0:
                        logger.info(f"[pool:{pool_name}] step4 进度 {i + 1}/{len(survivors)}, 已入选 {len(kline_survivors)}")
            survivors = kline_survivors
            logger.info(
                f"[pool:{pool_name}] step4 K线过滤(近{require_limit_up_in_days}日涨停/最低>MA{ma_period}/"
                f"前日未涨停={exclude_prev_day_limit_up}) 后 {len(survivors)} 只"
            )

        result = {
            "pool_name": pool_name,
            "updated_at": datetime.now().isoformat(timespec="seconds"),
            "count": len(survivors),
            "rules": rules,
            "stats": {
                "step1_after_market_filter": len(step1),
                "step2_after_price_filter": len(step2),
                "step3_after_mktcap_filter": step3_count,
                "step4_after_kline_filter": len(survivors) if kline_filter_on else None,
                "elapsed_seconds": round(time.time() - t0, 1),
            },
            "codes": survivors,
        }
        _save(pool_name, result)
        _archive_snapshot(pool_name, result)
        _prune_history(pool_name, settings.pool_history_keep)
        logger.info(
            f"[pool:{pool_name}] build done in {result['stats']['elapsed_seconds']}s, "
            f"final {len(survivors)} stocks"
        )
        return {"ok": True, **result}
    finally:
        _building.discard(pool_name)
        lock.release()


def build_all_auto_refresh_pools() -> dict:
    """周五 cron 入口：遍历所有 autoRefresh=true 的池子顺序重建。"""
    names = pool_registry.auto_refresh_names()
    logger.info(f"[pool] cron firing: rebuild {len(names)} pools: {names}")
    results = {}
    for name in names:
        try:
            results[name] = build_pool(name).get("count", 0)
        except Exception as e:
            logger.error(f"[pool] cron build {name} failed: {e}")
            results[name] = -1
    return results


def _save(pool_name: str, payload: dict) -> None:
    path = _snapshot_path(pool_name)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def _archive_snapshot(pool_name: str, payload: dict) -> None:
    """归档到 data/pool_history/{pool_name}/{YYYY-MM-DD}.json。同一天再 build 会覆盖。"""
    date = (payload.get("updated_at") or datetime.now().isoformat())[:10]
    hist_dir = _history_dir(pool_name)
    hist_dir.mkdir(parents=True, exist_ok=True)
    path = hist_dir / f"{date}.json"
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)
    logger.info(f"[pool:{pool_name}] archived to {path.name}")


def _prune_history(pool_name: str, keep: int) -> None:
    hist_dir = _history_dir(pool_name)
    if not hist_dir.exists():
        return
    files = sorted(hist_dir.glob("*.json"), reverse=True)
    for old in files[keep:]:
        try:
            old.unlink()
            logger.info(f"[pool:{pool_name}] pruned old snapshot {old.name}")
        except Exception as e:
            logger.warning(f"[pool:{pool_name}] prune {old.name} failed: {e}")


def load_pool(pool_name: str = "default") -> dict | None:
    """读指定池子的当前快照。文件不存在返回 None。"""
    path = _snapshot_path(pool_name)
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        logger.warning(f"[pool:{pool_name}] load failed: {e}")
        return None


def pool_status(pool_name: str = "default") -> dict:
    pool_def = pool_registry.get_pool(pool_name)
    data = load_pool(pool_name)
    if not data:
        return {
            "exists": False,
            "pool_name": pool_name,
            "definition": pool_def,
            "building": pool_name in _building,
        }
    return {
        "exists": True,
        "pool_name": pool_name,
        "definition": pool_def,
        "updated_at": data.get("updated_at"),
        "count": data.get("count", 0),
        "rules": data.get("rules"),
        "stats": data.get("stats"),
        "building": pool_name in _building,
        "is_stale": _is_stale(data.get("updated_at")),
    }


def _is_stale(updated_at_iso: str | None) -> bool:
    if not updated_at_iso:
        return True
    try:
        dt = datetime.fromisoformat(updated_at_iso)
    except Exception:
        return True
    age_days = (datetime.now() - dt).total_seconds() / 86400
    return age_days > settings.pool_max_age_days


def list_history(pool_name: str = "default") -> list[dict]:
    """列出该池所有历史快照（仅元数据：日期、count）。"""
    hist_dir = _history_dir(pool_name)
    if not hist_dir.exists():
        return []
    out = []
    for p in sorted(hist_dir.glob("*.json"), reverse=True):
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
            out.append({
                "date": p.stem,
                "updated_at": data.get("updated_at"),
                "count": data.get("count", 0),
            })
        except Exception as e:
            logger.warning(f"[pool:{pool_name}] read history {p.name} failed: {e}")
    return out


def load_history(pool_name: str, date: str) -> dict | None:
    """读特定日期的历史快照完整数据。"""
    path = _history_dir(pool_name) / f"{date}.json"
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        logger.warning(f"[pool:{pool_name}] load history {date} failed: {e}")
        return None


def trigger_build_async(pool_name: str = "default") -> None:
    if pool_name in _building:
        logger.info(f"[pool:{pool_name}] build already running")
        return
    threading.Thread(target=build_pool, args=(pool_name,), name=f"pool-build-{pool_name}", daemon=True).start()


def ensure_pool_fresh_on_startup() -> None:
    """启动时迁移 + 自检：
       1. 老的 data/dynamic_pool.json 若存在迁移到 data/pools/default.json
       2. pool_registry 确保 default 池存在
       3. 各 autoRefresh 池若快照不存在或过期，异步触发重建
    """
    # 1. 老格式迁移（一次性）
    legacy = _data_root() / "dynamic_pool.json"
    if legacy.exists():
        target = _snapshot_path("default")
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(legacy, target)
            logger.info(f"[pool] migrated legacy dynamic_pool.json → {target}")
        # 不删 legacy，下次启动就直接走新路径
    # 2. 触发 registry 初始化
    pool_registry.list_pools()
    # 3. 对每个自动池检查
    for name in pool_registry.auto_refresh_names():
        data = load_pool(name)
        if not data:
            logger.info(f"[pool:{name}] no snapshot, will build on startup")
            trigger_build_async(name)
            continue
        if _is_stale(data.get("updated_at")):
            logger.info(f"[pool:{name}] stale (updated_at={data.get('updated_at')}), will rebuild")
            trigger_build_async(name)
