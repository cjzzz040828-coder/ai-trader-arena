from fastapi import APIRouter, Query

from app.core.mootdx_client import ALL_MARKET_STOCKS, STOCK_NAMES, mootdx_client
from app.core.market_clock import market_status

router = APIRouter(prefix="/quote", tags=["quote"])


@router.get("/snapshot")
def snapshot(codes: str = Query(..., description="逗号分隔的6位股票代码，如 000001,600519")):
    code_list = [c.strip() for c in codes.split(",") if c.strip()]
    data = mootdx_client.snapshot(code_list)
    return {
        "market_status": market_status(),
        "last_ok_ago_seconds": mootdx_client.last_ok_ago_seconds,
        "count": len(data),
        "data": data,
    }


@router.get("/bars")
def bars(
    code: str = Query(..., description="6位股票代码"),
    frequency: int = Query(9, description="0=5分 1=15分 3=60分 8=1分 9=日K"),
    count: int = Query(240, ge=1, le=800, description="返回多少根K线"),
):
    """获取K线数据。"""
    data = mootdx_client.bars(code, frequency=frequency, count=count)
    return {
        "code": code,
        "frequency": frequency,
        "count": len(data),
        "data": data,
    }


@router.get("/transaction")
def transaction(
    code: str = Query(..., description="6位股票代码"),
    count: int = Query(60, ge=1, le=800, description="返回多少条逐笔成交"),
):
    """当日逐笔成交（按分钟+价位聚合）。time/price/vol/amount/num/buyorsell。"""
    data = mootdx_client.transaction(code, count=count)
    return {
        "code": code,
        "count": len(data),
        "data": data,
    }


@router.get("/search")
def search(
    q: str = Query(..., description="股票代码或名称（模糊匹配），如 600519 / 茅台"),
    limit: int = Query(10, ge=1, le=50),
):
    """股票代码/名称模糊搜索。返回 [{code, name, market}] 列表。
    - 纯数字按代码前缀匹配；
    - 否则按名称 contains 匹配（去掉 ST / * 等前缀干扰）。
    数据源 = mootdx 全市场名表 ALL_MARKET_STOCKS（启动后异步加载，未就绪时回退到 STOCK_NAMES）。
    """
    q = (q or "").strip()
    if not q:
        return {"count": 0, "items": []}

    items: list[dict] = []
    if ALL_MARKET_STOCKS:
        q_lower = q.lower()
        is_digit = q.isdigit()
        for row in ALL_MARKET_STOCKS:
            code = row.get("code", "")
            name = row.get("name", "")
            if is_digit:
                if code.startswith(q):
                    items.append(row)
            else:
                # 名称模糊匹配（同时匹配原名和去除空格/特殊符号的版本）
                if q_lower in name.lower() or q in name:
                    items.append(row)
            if len(items) >= limit:
                break
    else:
        # 名表未就绪：从内置 STOCK_NAMES 兜底
        for code, name in STOCK_NAMES.items():
            if (q.isdigit() and code.startswith(q)) or (not q.isdigit() and q in name):
                items.append({"code": code, "name": name, "market": "SH" if code[0] in ("5", "6", "9") else "SZ"})
                if len(items) >= limit:
                    break

    return {"count": len(items), "items": items, "table_ready": bool(ALL_MARKET_STOCKS)}
