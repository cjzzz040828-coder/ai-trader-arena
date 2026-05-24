"""
读取同花顺PC客户端的自选股。

数据源优先级：
1. ths_sel_export_path 指向的 .sel 文件（用户从 THS 里"自选股→导出"得到，覆盖全分组）
2. mx_<user>/stockblock.ini    [BLOCK_STOCK_CONTEXT] 段（仅完整导出当前激活分组）
3. mx_<user>/custom_block/330  (stockblock.ini 同步出来的副本)
4. mx_<user>/SelfStockInfo.json (最近添加索引，只有 ~40 只)

.sel 格式：2 字节头 + N×8 字节记录，每条 = [07, market_byte, ASCII6]
  market_byte: 0x11=沪市, 0x21=深市

stockblock.ini 格式：
[BLOCK_STOCK_CONTEXT]
14A=33:000036,33:000586,17:600076,...
其中 33=深市, 17=沪市，6位股票代码
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Optional

from loguru import logger

from app.config import settings
from app.core.mootdx_client import ALL_MARKET_STOCKS, STOCK_NAMES

_cache: list[dict] | None = None
_cache_mtime: float = 0
_cache_groups: dict[str, list[str]] = {}

CODE_PATTERN = re.compile(r'\b(33|17):(\d{6})\b')


def _classify_market_segment(code: str) -> str | None:
    """根据 6 位代码判定板块。返回 settings.filter_markets_set 里的标签或 None（非主流 A 股）。

    沪市：600/601/603/605 → 主板；688 → 科创板
    深市：000 → 主板；002 → 中小板；300 → 创业板
    其它（83/87/8/4/9 等）= 北交所/B 股/其它，本期不接入
    """
    if not code or len(code) != 6 or not code.isdigit():
        return None
    p2 = code[:2]
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
    if p2 == "00":  # 00 开头但非 002/000 的少数情况，统归深市主板
        return "MAIN_SZ"
    return None


def _is_excluded_by_name(name: str) -> bool:
    """名称命中 ST / *ST / 退 等风险标记则排除。"""
    if not name:
        return False
    upper = name.upper()
    if settings.filter_exclude_st and ("ST" in upper):
        return True
    if settings.filter_exclude_delisting and ("退" in name):
        return True
    return False


def _load_by_filter() -> tuple[list[dict], dict[str, list[str]]]:
    """从 mootdx 全市场名表按 settings 配置的规则筛选。返回 (records, groups)。

    若 mootdx 名表还没加载完（_load_stock_names 是后台线程），返回空让上层回退到 sel。
    """
    if not ALL_MARKET_STOCKS:
        logger.warning("[selfstock] filter mode: ALL_MARKET_STOCKS not loaded yet")
        return [], {}

    markets_keep = settings.filter_markets_set
    max_count = max(0, int(settings.filter_max_count or 0))

    matched: list[dict] = []
    groups_by_segment: dict[str, list[str]] = {}
    for s in ALL_MARKET_STOCKS:
        code = s.get("code", "")
        name = s.get("name") or STOCK_NAMES.get(code, code)
        seg = _classify_market_segment(code)
        if not seg or seg not in markets_keep:
            continue
        if _is_excluded_by_name(name):
            continue
        matched.append({
            "code": code,
            "name": name,
            "market": "SH" if code[0] in ("5", "6", "9") else "SZ",
            "added_price": 0,
            "added_date": "",
            "_segment": seg,
        })

    # 按代码升序稳定排序后截断
    matched.sort(key=lambda r: r["code"])
    if max_count and len(matched) > max_count:
        matched = matched[:max_count]

    for r in matched:
        seg = r.pop("_segment")
        groups_by_segment.setdefault(seg, []).append(r["code"])
    logger.info(
        f"[selfstock] filter mode: {len(matched)} stocks, "
        f"segments=" + ", ".join(f"{k}:{len(v)}" for k, v in groups_by_segment.items())
    )
    return matched, groups_by_segment


def _load_by_pool(pool_name: str | None = None) -> tuple[list[dict], dict[str, list[str]]]:
    """读 data/pools/{name}.json，转成 watchlist 记录格式。文件不存在/为空时返回 (空, 空) 让上层回退。

    pool_name=None 时走 default 池。
    """
    from app.core.dynamic_pool import load_pool  # 延迟 import 避免循环
    name = pool_name or "default"
    data = load_pool(name)
    if not data or not data.get("codes"):
        logger.warning(f"[selfstock] pool '{name}' empty/missing")
        return [], {}
    records: list[dict] = []
    for s in data["codes"]:
        code = s.get("code", "")
        if not code:
            continue
        records.append({
            "code": code,
            "name": s.get("name") or STOCK_NAMES.get(code, code),
            "market": s.get("market") or ("SH" if code[0] in ("5", "6", "9") else "SZ"),
            "added_price": s.get("price", 0),
            "added_date": (data.get("updated_at") or "")[:10],
        })
    groups = {name: [r["code"] for r in records]}
    logger.info(f"[selfstock] pool '{name}' loaded {len(records)} stocks (updated_at={data.get('updated_at')})")
    return records, groups


def _resolve_ths_user_dir() -> Optional[Path]:
    """找到 mx_<user_id> 目录。"""
    p_json = Path(settings.ths_selfstock_json)
    if p_json.exists():
        return p_json.parent

    ths_root = Path(settings.ths_exe_path).parent
    if not ths_root.exists():
        return None

    candidates = []
    for sub in ths_root.glob("mx_*"):
        if sub.is_dir() and (sub / "stockblock.ini").exists():
            candidates.append(sub)
    if not candidates:
        for sub in ths_root.glob("thsguest_*"):
            if sub.is_dir() and (sub / "stockblock.ini").exists():
                candidates.append(sub)
    if not candidates:
        return None
    return max(candidates, key=lambda x: x.stat().st_mtime)


def _parse_sel_file(path: Path) -> list[str]:
    """解析 THS 导出的 .sel 二进制自选股文件，返回去重后的代码列表。"""
    data = path.read_bytes()
    if len(data) < 2 or (len(data) - 2) % 8 != 0:
        logger.warning(f"[selfstock] .sel size suspicious: {len(data)}")
    codes: list[str] = []
    seen: set[str] = set()
    i = 2  # 跳过 2 字节头
    while i + 8 <= len(data):
        rec = data[i:i + 8]
        try:
            code = rec[2:8].decode("ascii")
        except UnicodeDecodeError:
            i += 8
            continue
        if code.isdigit() and code not in seen:
            seen.add(code)
            codes.append(code)
        i += 8
    return codes


def _parse_stockblock_ini(path: Path) -> tuple[list[str], dict[str, list[str]]]:
    """返回 (所有去重的代码列表, 分组名->代码列表)。"""
    with open(path, encoding="gbk", errors="replace") as f:
        text = f.read()

    groups: dict[str, list[str]] = {}
    in_context = False
    for line in text.splitlines():
        line = line.strip()
        if line == "[BLOCK_STOCK_CONTEXT]":
            in_context = True
            continue
        if line.startswith("[") and line.endswith("]"):
            in_context = False
            continue
        if not in_context or "=" not in line:
            continue
        key, _, value = line.partition("=")
        codes = [m[1] for m in CODE_PATTERN.findall(value)]
        if codes:
            groups[key.strip()] = codes

    # 去重 + 保持顺序
    seen = set()
    all_codes: list[str] = []
    for codes in groups.values():
        for c in codes:
            if c not in seen:
                seen.add(c)
                all_codes.append(c)
    return all_codes, groups


def _parse_selfstock_json(path: Path) -> list[dict]:
    """fallback：读取 SelfStockInfo.json。"""
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    result = []
    for item in raw:
        code = item.get("C", "")
        if not code:
            continue
        result.append({
            "code": code,
            "market": "SH" if item.get("M") == "17" else "SZ",
            "added_price": float(item.get("P", 0) or 0),
            "added_date": item.get("T", ""),
        })
    return result


def load_selfstock(force: bool = False, pool_name: str | None = None) -> list[dict]:
    """主入口：返回完整自选股列表 [{code, name, market, added_price, added_date}, ...]

    三种模式（settings.watchlist_mode）：
      sel    : 从 .sel 文件 / stockblock.ini / SelfStockInfo.json 解析（默认）
      filter : 从 mootdx 全市场名表按规则筛选（板块代码段 + 去 ST/退市 + 截断到 N 只）
      pool   : 从 data/pools/{pool_name}.json 读"打板候选池"
               pool_name 显式传入时无视全局 watchlist_mode 直接走 pool 模式
               文件缺失或为空时回退到 sel 模式
    """
    global _cache, _cache_mtime, _cache_groups

    mode = (settings.watchlist_mode or "sel").lower()
    # 调用方显式传 pool_name 时直接进 pool 模式（不要被全局 mode 限制 — 多 trader 各绑各池）
    if pool_name is not None:
        records, groups = _load_by_pool(pool_name)
        if records:
            return records
        logger.info(f"[selfstock] pool '{pool_name}' empty, fallback to sel sources")
        # 注意：显式池子模式下 fallback 后不写缓存，避免污染默认 watchlist 缓存

    # ---- pool 模式（默认 default 池）----
    if mode == "pool":
        records, groups = _load_by_pool(None)
        if records:
            new_mtime = float(len(records))
            if not force and _cache is not None and _cache_mtime == new_mtime:
                return _cache
            _cache = records
            _cache_mtime = new_mtime
            _cache_groups = groups
            return records
        logger.info("[selfstock] default pool empty, fallback to sel sources")

    # ---- filter 模式：直接从全市场名表筛 ----
    if mode == "filter":
        records, groups = _load_by_filter()
        if records:
            # filter 模式的"mtime"用名表长度做版本号即可：名表更新会自动反映
            new_mtime = float(len(records))
            if not force and _cache is not None and _cache_mtime == new_mtime:
                return _cache
            _cache = records
            _cache_mtime = new_mtime
            _cache_groups = groups
            return records
        logger.info("[selfstock] filter mode empty, fallback to sel sources")

    # ---- sel 模式（默认）----
    sel_path = Path(settings.ths_sel_export_path)
    extra_paths = settings.sel_extra_paths_list
    user_dir = _resolve_ths_user_dir()

    stockblock_path = (user_dir / "stockblock.ini") if user_dir else None
    selfstock_path = (user_dir / "SelfStockInfo.json") if user_dir else None

    mtimes = []
    if sel_path.exists():
        mtimes.append(sel_path.stat().st_mtime)
    for p in extra_paths:
        if p.exists():
            mtimes.append(p.stat().st_mtime)
    if stockblock_path and stockblock_path.exists():
        mtimes.append(stockblock_path.stat().st_mtime)
    if selfstock_path and selfstock_path.exists():
        mtimes.append(selfstock_path.stat().st_mtime)
    if not mtimes:
        logger.warning("[selfstock] no source files found")
        return []
    cur_mtime = max(mtimes)

    if not force and _cache is not None and cur_mtime == _cache_mtime:
        return _cache

    # 读 SelfStockInfo.json 拿额外元数据（加入价/日期）
    extra: dict[str, dict] = {}
    if selfstock_path and selfstock_path.exists():
        try:
            for item in _parse_selfstock_json(selfstock_path):
                extra[item["code"]] = item
        except Exception as e:
            logger.warning(f"[selfstock] read SelfStockInfo.json failed: {e}")

    groups: dict[str, list[str]] = {}

    # 数据源 1：.sel 主文件 + 额外 .sel 文件，各自作为独立分组
    sel_files: list[Path] = []
    if sel_path.exists():
        sel_files.append(sel_path)
    for p in extra_paths:
        if p.exists() and p.resolve() != sel_path.resolve():
            sel_files.append(p)

    for p in sel_files:
        try:
            codes = _parse_sel_file(p)
            groups[p.stem] = codes
            logger.info(f"[selfstock] {p.name}: {len(codes)} stocks")
        except Exception as e:
            logger.warning(f"[selfstock] read {p} failed: {e}")

    # 数据源 2：stockblock.ini（仅在没有任何 .sel 时回退）
    if not groups and stockblock_path and stockblock_path.exists():
        try:
            _, ini_groups = _parse_stockblock_ini(stockblock_path)
            groups = ini_groups
            logger.info(f"[selfstock] stockblock.ini: {sum(len(v) for v in groups.values())} stocks across {len(groups)} groups")
        except Exception as e:
            logger.warning(f"[selfstock] read stockblock.ini failed: {e}")

    # 跨组去重合并，保持首次出现顺序
    seen: set[str] = set()
    all_codes: list[str] = []
    for codes in groups.values():
        for c in codes:
            if c not in seen:
                seen.add(c)
                all_codes.append(c)

    # 兜底：用 SelfStockInfo.json 的代码
    if not all_codes and extra:
        all_codes = list(extra.keys())
        logger.info(f"[selfstock] fallback to SelfStockInfo.json: {len(all_codes)} stocks")

    result = []
    for code in all_codes:
        ex = extra.get(code, {})
        result.append({
            "code": code,
            "name": STOCK_NAMES.get(code, code),
            "market": ex.get("market") or ("SH" if code.startswith(("5", "6", "9")) else "SZ"),
            "added_price": ex.get("added_price", 0),
            "added_date": ex.get("added_date", ""),
        })

    _cache = result
    _cache_mtime = cur_mtime
    _cache_groups = groups
    return result


def list_groups() -> dict[str, list[str]]:
    if not _cache_groups:
        load_selfstock()
    return _cache_groups
