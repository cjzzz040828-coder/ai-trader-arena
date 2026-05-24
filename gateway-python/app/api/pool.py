"""
多池子（dynamic stock pool）HTTP 接口。

每个池子是一份独立的筛选规则（板块、价格、市值），可以并存多个。Java 端
trader 选定一个 poolName 后，调度时拉这个池子作为 watchlist 来源。

路由总览：
  GET    /pool                  列出所有池子定义
  POST   /pool                  创建池子（body 同 pool_registry.create_pool）
  PUT    /pool/{name}           更新池子 displayName/rules/autoRefresh
  DELETE /pool/{name}           删除池子（default 池受保护，删除时连带清理快照/历史）
  GET    /pool/{name}/status    池子快照状态（updated_at、count、stats、is_stale、building）
  POST   /pool/{name}/rebuild   异步触发重建，立即返回
  GET    /pool/{name}/history   列出该池所有历史快照（日期+count）
  GET    /pool/{name}/history/{date}  读特定日期的历史快照完整数据

向后兼容旧接口 GET /watchlist/pool/status / POST /watchlist/pool/rebuild
依旧操作 default 池，前端老逻辑无需调整。
"""
from fastapi import APIRouter, HTTPException

from app.core import pool_registry
from app.core.dynamic_pool import (
    list_history,
    load_history,
    pool_status,
    trigger_build_async,
)

router = APIRouter(prefix="/pool", tags=["pool"])


@router.get("")
def list_pools():
    """列出所有池子定义。前端用来给 trader 选池下拉填充选项。"""
    return {"pools": pool_registry.list_pools()}


@router.post("")
def create_pool(payload: dict):
    """创建池子。body 形如：
       {name, displayName?, rules:{markets, exclude_st?, exclude_delisting?,
                                    min_price, max_price, min_market_cap, max_market_cap},
        autoRefresh?}"""
    try:
        new_pool = pool_registry.create_pool(payload)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return new_pool


@router.put("/{name}")
def update_pool(name: str, payload: dict):
    """更新池子。payload 中只更新存在的键（displayName/rules/autoRefresh）。"""
    try:
        return pool_registry.update_pool(name, payload)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.delete("/{name}")
def delete_pool(name: str):
    """删除池子（连带 data/pools/{name}.json 和 data/pool_history/{name}/ 一起清理）。"""
    try:
        pool_registry.delete_pool(name)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return {"ok": True}


@router.get("/{name}/status")
def get_status(name: str):
    """池子快照状态：updated_at、count、rules、stats、is_stale、building。
    池子不存在时返回 exists=false 加 definition=null。"""
    return pool_status(name)


@router.post("/{name}/rebuild")
def rebuild(name: str):
    """异步触发重建。立即返回，前端轮询 status 看进度。约 60-120 秒。"""
    if not pool_registry.get_pool(name):
        raise HTTPException(status_code=404, detail=f"池子 '{name}' 不存在")
    trigger_build_async(name)
    return {"ok": True, "message": f"池子 '{name}' 构建已启动，可通过 GET /pool/{name}/status 查看进度"}


@router.get("/{name}/history")
def history_list(name: str):
    """列出该池所有历史快照的元数据（日期+count），按日期倒序。"""
    if not pool_registry.get_pool(name):
        raise HTTPException(status_code=404, detail=f"池子 '{name}' 不存在")
    return {"name": name, "history": list_history(name)}


@router.get("/{name}/history/{date}")
def history_one(name: str, date: str):
    """读特定日期（YYYY-MM-DD）的历史快照完整数据。"""
    data = load_history(name, date)
    if not data:
        raise HTTPException(status_code=404, detail=f"池子 '{name}' 的 {date} 快照不存在")
    return data
