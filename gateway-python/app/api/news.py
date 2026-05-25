from fastapi import APIRouter, Query

from app.core.news_client import cache_stats, fetch_cls_telegraph, fetch_stock_news

router = APIRouter(prefix="/news", tags=["news"])


@router.get("/stock/{code}")
def stock_news(code: str, limit: int = Query(10, ge=1, le=50)):
    items = fetch_stock_news(code, limit=limit)
    return {
        "code": code,
        "count": len(items),
        "items": items,
    }


@router.get("/cls")
def cls_telegraph(
    symbol: str = Query("全部", description="'全部' 或 '重点'"),
    limit: int = Query(30, ge=1, le=100),
):
    items = fetch_cls_telegraph(symbol=symbol, limit=limit)
    return {
        "symbol": symbol,
        "count": len(items),
        "items": items,
    }


@router.get("/stats")
def stats():
    return cache_stats()
