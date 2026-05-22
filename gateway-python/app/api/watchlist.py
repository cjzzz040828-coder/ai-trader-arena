from fastapi import APIRouter

from app.core.selfstock import load_selfstock, list_groups

router = APIRouter(prefix="/watchlist", tags=["watchlist"])


@router.get("")
def watchlist():
    """读取同花顺PC客户端的自选股。文件未变时走缓存。"""
    data = load_selfstock()
    return {"count": len(data), "data": data}


@router.get("/reload")
def reload_watchlist():
    """强制重新读取文件（在同花顺改了自选股之后调一次）。"""
    data = load_selfstock(force=True)
    groups = list_groups()
    return {
        "count": len(data),
        "groups": {k: len(v) for k, v in groups.items()},
        "data": data,
    }


@router.get("/groups")
def watchlist_groups():
    """返回所有自选股分组及每组的代码列表。"""
    load_selfstock()
    return list_groups()
