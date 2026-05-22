from fastapi import APIRouter, Query

from app.core.mootdx_client import mootdx_client
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
