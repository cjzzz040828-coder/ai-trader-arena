from fastapi import APIRouter

from app.core.dynamic_pool import pool_status, trigger_build_async
from app.core.selfstock import load_selfstock, list_groups

router = APIRouter(prefix="/watchlist", tags=["watchlist"])


@router.get("")
def watchlist(pool: str | None = None):
    """读取自选股。当传 pool=xxx 时改为加载对应池子的快照（多 trader 各绑各池场景）。
    pool 缺失或为空时走默认 watchlist 数据源（settings.watchlist_mode 决定）。
    pool 指定但快照不存在时会回退到 sel，前端 / 上游需要自己处理空池场景。"""
    data = load_selfstock(pool_name=pool) if pool else load_selfstock()
    return {"count": len(data), "data": data, "groups": list_groups() if not pool else {pool: [r["code"] for r in data]}}


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


@router.get("/pool/status")
def get_pool_status():
    """[兼容旧接口] 默认池子的状态。新代码请改调 GET /pool/{name}/status。"""
    return pool_status()


@router.post("/pool/rebuild")
def rebuild_pool():
    """[兼容旧接口] 重建默认池。新代码请改调 POST /pool/{name}/rebuild。"""
    trigger_build_async()
    return {"ok": True, "message": "构建已在后台启动，可通过 GET /watchlist/pool/status 查看进度"}
