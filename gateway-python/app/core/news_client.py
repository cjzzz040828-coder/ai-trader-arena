"""新闻数据客户端: 包装 akshare 的个股新闻 + 财联社电报, 带 TTL 缓存 + 字段兜底.

akshare 本质是对东方财富/财联社公开页面的封装, 字段名偶尔会变, 因此:
  - 输出 schema 统一为 {title, content, time, source, url}, 来源字段缺失时降级为 ""
  - 拉取失败时返回 [] 而不是抛, 让 LLM strategy 能容忍
"""
from __future__ import annotations

import threading
from datetime import datetime
from typing import Any, Dict, List

from cachetools import TTLCache
from loguru import logger

try:
    import akshare as ak
except ImportError:
    ak = None  # type: ignore[assignment]

_STOCK_NEWS_TTL = 30 * 60     # 个股新闻 30 分钟缓存 (新闻更新频率不高)
_CLS_TTL = 5 * 60             # 财联社电报 5 分钟缓存 (情绪流要新鲜)
_STOCK_NEWS_MAX = 512         # 缓存最多 512 只股票
_CLS_MAX = 4                  # 财联社只缓存 "全部" / "重点"

_stock_news_cache: TTLCache = TTLCache(maxsize=_STOCK_NEWS_MAX, ttl=_STOCK_NEWS_TTL)
_cls_cache: TTLCache = TTLCache(maxsize=_CLS_MAX, ttl=_CLS_TTL)
_lock = threading.Lock()


def _norm_str(v: Any) -> str:
    if v is None:
        return ""
    s = str(v).strip()
    return "" if s.lower() in ("nan", "none") else s


def _row_to_news(row: Dict[str, Any]) -> Dict[str, str]:
    """字段兜底: akshare 不同版本字段名可能变, 容错地映射到统一 schema."""
    title = _norm_str(row.get("新闻标题") or row.get("标题"))
    content = _norm_str(row.get("新闻内容") or row.get("内容") or row.get("摘要"))
    pub_date = _norm_str(row.get("发布日期"))
    pub_time = _norm_str(row.get("发布时间"))
    if pub_date and pub_time and len(pub_time) <= 8:
        time_str = f"{pub_date} {pub_time}"
    else:
        time_str = pub_time or pub_date
    return {
        "title": title,
        "content": content,
        "time": time_str,
        "source": _norm_str(row.get("文章来源") or row.get("来源")),
        "url": _norm_str(row.get("新闻链接") or row.get("链接")),
    }


def fetch_stock_news(code: str, limit: int = 10) -> List[Dict[str, str]]:
    """拉某只股票的新闻, 返回最新 N 条 (akshare 单次约 10 条).

    code: 6 位股票代码字符串.
    """
    if ak is None:
        logger.warning("[news] akshare not installed; returning []")
        return []
    code = code.strip()
    if not code or not code.isdigit() or len(code) != 6:
        return []

    cache_key = f"stock:{code}"
    cached = _stock_news_cache.get(cache_key)
    if cached is not None:
        return cached[:limit]

    with _lock:
        cached = _stock_news_cache.get(cache_key)
        if cached is not None:
            return cached[:limit]
        try:
            df = ak.stock_news_em(symbol=code)
        except Exception as e:
            logger.warning(f"[news] stock_news_em({code}) failed: {type(e).__name__}: {e}")
            _stock_news_cache[cache_key] = []
            return []

        items: List[Dict[str, str]] = []
        try:
            for _, row in df.iterrows():
                items.append(_row_to_news(row.to_dict()))
        except Exception as e:
            logger.warning(f"[news] parse stock_news_em({code}) failed: {e}")

        _stock_news_cache[cache_key] = items
        logger.info(f"[news] stock_news_em({code}) -> {len(items)} items")
        return items[:limit]


def fetch_cls_telegraph(symbol: str = "全部", limit: int = 30) -> List[Dict[str, str]]:
    """拉财联社电报全市场情绪流.

    symbol: "全部" 或 "重点".
    """
    if ak is None:
        logger.warning("[news] akshare not installed; returning []")
        return []
    symbol = (symbol or "全部").strip()
    if symbol not in ("全部", "重点"):
        symbol = "全部"

    cache_key = f"cls:{symbol}"
    cached = _cls_cache.get(cache_key)
    if cached is not None:
        return cached[:limit]

    with _lock:
        cached = _cls_cache.get(cache_key)
        if cached is not None:
            return cached[:limit]
        try:
            df = ak.stock_info_global_cls(symbol=symbol)
        except Exception as e:
            logger.warning(f"[news] stock_info_global_cls({symbol}) failed: {type(e).__name__}: {e}")
            _cls_cache[cache_key] = []
            return []

        items: List[Dict[str, str]] = []
        try:
            for _, row in df.iterrows():
                items.append(_row_to_news(row.to_dict()))
        except Exception as e:
            logger.warning(f"[news] parse stock_info_global_cls failed: {e}")

        _cls_cache[cache_key] = items
        logger.info(f"[news] stock_info_global_cls({symbol}) -> {len(items)} items")
        return items[:limit]


def cache_stats() -> Dict[str, Any]:
    return {
        "stock_news_cached_codes": len(_stock_news_cache),
        "stock_news_ttl_seconds": _STOCK_NEWS_TTL,
        "cls_cached_keys": len(_cls_cache),
        "cls_ttl_seconds": _CLS_TTL,
        "akshare_available": ak is not None,
        "now": datetime.now().isoformat(timespec="seconds"),
    }
