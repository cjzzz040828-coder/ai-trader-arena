"""
mootdx 行情客户端封装。

避坑要点（严格遵守 CLAUDE 系统提示）：
1. Quotes.factory(bestip=True) 进程内全局单例，绝不重复调用
2. APScheduler 心跳每 60s 拉一次 sh000001 验证连接，失败则后台重连
3. TTL 缓存兜底：高频请求被缓存吸收；非交易时段直接走缓存
4. 所有外部调用 try/except，异常写日志，绝不抛给路由层导致 500 失控
"""
from __future__ import annotations

import threading
import time as _time
from datetime import datetime
from typing import Any

import pandas as pd
from cachetools import TTLCache
from loguru import logger
from mootdx.quotes import Quotes

from app.config import settings
from app.core.market_clock import is_trading_now, market_status

logger.add(
    settings.log_dir / "mootdx.log",
    rotation="20 MB",
    retention="14 days",
    encoding="utf-8",
    enqueue=True,
)


# 常用A股名称映射（mootdx quotes 不返回 name，单独维护）
STOCK_NAMES: dict[str, str] = {
    "000001": "平安银行", "000002": "万科A", "000333": "美的集团",
    "000651": "格力电器", "000858": "五粮液", "002594": "比亚迪",
    "300750": "宁德时代", "600000": "浦发银行", "600036": "招商银行",
    "600276": "恒瑞医药", "600519": "贵州茅台", "600887": "伊利股份",
    "601318": "中国平安", "601398": "工商银行", "601857": "中国石油",
    "601988": "中国银行", "603259": "药明康德", "688981": "中芯国际",
}


def _detect_market(code: str) -> int:
    """0=深市, 1=沪市。沪市规则：6/9/5开头，深市：0/2/3开头。"""
    if not code:
        return 0
    return 1 if code[0] in ("5", "6", "9") else 0


class MootdxClient:
    _instance: "MootdxClient | None" = None
    _lock = threading.Lock()

    def __new__(cls) -> "MootdxClient":
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self) -> None:
        if getattr(self, "_initialized", False):
            return
        self._initialized = True

        self._client: Any | None = None
        self._client_lock = threading.RLock()
        self._cache: TTLCache = TTLCache(maxsize=2048, ttl=settings.quote_cache_ttl_seconds)
        self._bars_cache: TTLCache = TTLCache(maxsize=512, ttl=2)
        self._snapshot_fallback: dict[str, dict] = {}
        self._last_ok_ts: float = 0.0
        self._ready = threading.Event()

        # 后台异步初始化：HTTP 服务立刻可用，mootdx 在背后连接
        threading.Thread(target=self._connect_with_retry, kwargs={"initial": True}, daemon=True).start()

    # -------- 连接管理 --------
    def _build(self) -> None:
        """直接用配置里的稳定 IP 列表，按顺序尝试，避免 bestip=True 的 60-90s ping。"""
        last_err = None
        for ip, port in settings.tdx_servers:
            try:
                logger.info(f"[mootdx] connecting {ip}:{port} ...")
                t0 = _time.time()
                client = Quotes.factory(market="std", bestip=False, server=(ip, port), timeout=5)
                # 探活：拉一笔行情验证 socket 真的能通
                _ = client.quotes(symbol=["000001"])
                cost = (_time.time() - t0) * 1000
                logger.info(f"[mootdx] connected to {ip}:{port} in {cost:.0f}ms")
                self._client = client
                return
            except Exception as e:
                last_err = e
                logger.warning(f"[mootdx] connect {ip}:{port} failed: {e}")
        raise RuntimeError(f"all tdx servers failed, last_err={last_err}")

    def _connect_with_retry(self, initial: bool = False) -> None:
        backoff = 1
        attempt = 0
        while True:
            attempt += 1
            try:
                with self._client_lock:
                    self._build()
                    df = self._client.quotes(symbol=["000001"])
                if df is not None and not (isinstance(df, pd.DataFrame) and df.empty):
                    self._last_ok_ts = _time.time()
                    self._ready.set()
                    logger.info(f"[mootdx] ready on attempt {attempt}")
                    # 异步加载全市场股票名表，不阻塞主流程
                    threading.Thread(target=self._load_stock_names, daemon=True).start()
                    return
                raise RuntimeError("empty probe result")
            except Exception as e:
                logger.warning(f"[mootdx] connect failed attempt={attempt} err={e}")
                if initial and attempt >= 3:
                    logger.error("[mootdx] initial connect failed 3 times, will keep retrying in background")
                    return
                _time.sleep(backoff)
                backoff = min(backoff * 2, settings.reconnect_max_backoff_seconds)

    def _load_stock_names(self) -> None:
        """从 mootdx 拉全市场股票名表，扩充 STOCK_NAMES 字典。"""
        try:
            with self._client_lock:
                df_sz = self._client.stocks(market=0)
                df_sh = self._client.stocks(market=1)
            count = 0
            for df in (df_sz, df_sh):
                if df is None or df.empty:
                    continue
                for _, row in df.iterrows():
                    code = str(row.get("code", "")).zfill(6)
                    name = str(row.get("name", "")).strip()
                    if code and name and code not in STOCK_NAMES:
                        STOCK_NAMES[code] = name
                        count += 1
            logger.info(f"[mootdx] loaded {count} stock names, total={len(STOCK_NAMES)}")
        except Exception as e:
            logger.warning(f"[mootdx] load_stock_names error: {e}")

    def heartbeat(self) -> None:
        try:
            with self._client_lock:
                df = self._client.quotes(symbol=["000001"]) if self._client else None
            if df is None or (isinstance(df, pd.DataFrame) and df.empty):
                if is_trading_now():
                    logger.warning("[mootdx] heartbeat empty in trading hours, reconnecting...")
                    self._connect_with_retry()
            else:
                self._last_ok_ts = _time.time()
        except Exception as e:
            logger.error(f"[mootdx] heartbeat error: {e}, reconnecting...")
            self._connect_with_retry()

    # -------- 业务方法 --------
    def snapshot(self, codes: list[str]) -> list[dict]:
        """获取一组股票的实时五档盘口（含名称、涨跌幅）。"""
        codes = [c.strip() for c in codes if c and c.strip()]
        if not codes:
            return []

        if not self._ready.is_set():
            logger.debug("[mootdx] not ready, returning fallback or empty")
            return [self._snapshot_fallback[c] for c in codes if c in self._snapshot_fallback]

        cache_key = ("snapshot", tuple(codes))
        cached = self._cache.get(cache_key)
        if cached is not None:
            return cached

        status = market_status()
        if status != "OPEN":
            fb = [self._snapshot_fallback[c] for c in codes if c in self._snapshot_fallback]
            if fb:
                logger.debug(f"[mootdx] market={status} return fallback x{len(fb)}")
                return fb

        try:
            with self._client_lock:
                df = self._client.quotes(symbol=codes)
        except Exception as e:
            logger.error(f"[mootdx] snapshot error codes={codes} err={e}")
            return [self._snapshot_fallback[c] for c in codes if c in self._snapshot_fallback]

        if df is None or (isinstance(df, pd.DataFrame) and df.empty):
            logger.debug(f"[mootdx] empty df codes={codes} market={status}")
            return [self._snapshot_fallback[c] for c in codes if c in self._snapshot_fallback]

        result = self._df_to_records(df)
        for r in result:
            code = r.get("code")
            if code:
                self._snapshot_fallback[code] = r
        self._cache[cache_key] = result
        self._last_ok_ts = _time.time()
        return result

    def bars(self, code: str, frequency: int = 9, count: int = 240) -> list[dict]:
        """
        获取K线数据。
        frequency: 0=5分 1=15分 2=30分 3=60分 4=日 5=周 6=月 7=年 8=1分 9=日(默认) 10=季 11=年
                   常用：8=1分钟（分时）、9=日K
        count: 数量，默认 240（约一个交易日的1分钟数）
        """
        if not self._ready.is_set():
            return []

        cache_key = ("bars", code, frequency, count)
        cached = self._bars_cache.get(cache_key)
        if cached is not None:
            return cached

        try:
            with self._client_lock:
                market = _detect_market(code)
                df = self._client.bars(symbol=code, frequency=frequency, offset=count, market=market)
        except Exception as e:
            logger.error(f"[mootdx] bars error code={code} freq={frequency} err={e}")
            return []

        if df is None or (isinstance(df, pd.DataFrame) and df.empty):
            return []

        df = df.reset_index() if "datetime" not in df.columns else df
        records = df.fillna(0).to_dict(orient="records")
        for r in records:
            if "datetime" in r:
                r["datetime"] = str(r["datetime"])
        self._bars_cache[cache_key] = records
        return records

    @staticmethod
    def _df_to_records(df: pd.DataFrame) -> list[dict]:
        records = df.fillna(0).to_dict(orient="records")
        ts = datetime.now().isoformat(timespec="seconds")
        for r in records:
            r["fetched_at"] = ts
            code = str(r.get("code", "")).zfill(6) if r.get("code") else ""
            r["code"] = code
            r["name"] = STOCK_NAMES.get(code, code)
            last_close = float(r.get("last_close") or 0)
            price = float(r.get("price") or 0)
            if last_close > 0:
                r["change"] = round(price - last_close, 3)
                r["change_pct"] = round((price - last_close) / last_close * 100, 2)
            else:
                r["change"] = 0
                r["change_pct"] = 0
        return records

    @property
    def last_ok_ago_seconds(self) -> float:
        return _time.time() - self._last_ok_ts if self._last_ok_ts else -1

    @property
    def ready(self) -> bool:
        return self._ready.is_set()


mootdx_client = MootdxClient()
