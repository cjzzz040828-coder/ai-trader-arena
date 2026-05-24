from contextlib import asynccontextmanager
from datetime import datetime

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger

from app.api import account, pool, quote, trade, watchlist
from app.config import settings
from app.core.dynamic_pool import build_all_auto_refresh_pools, ensure_pool_fresh_on_startup
from app.core.market_clock import market_status
from app.core.mootdx_client import mootdx_client
from app.middleware.auth import TokenAuthMiddleware

scheduler = BackgroundScheduler(timezone="Asia/Shanghai")


@asynccontextmanager
async def lifespan(app: FastAPI):
    scheduler.add_job(
        mootdx_client.heartbeat,
        trigger=IntervalTrigger(seconds=settings.heartbeat_interval_seconds),
        id="mootdx_heartbeat",
        max_instances=1,
        coalesce=True,
    )
    # 周五收盘后重建所有 autoRefresh=true 的池子（A 股 15:00 收盘，等 30 分钟数据稳定）
    scheduler.add_job(
        build_all_auto_refresh_pools,
        trigger=CronTrigger(
            day_of_week=settings.pool_cron_day_of_week,
            hour=settings.pool_cron_hour,
            minute=settings.pool_cron_minute,
        ),
        id="rebuild_dynamic_pool",
        max_instances=1,
        coalesce=True,
    )
    scheduler.start()
    logger.info(
        f"[main] scheduler started, heartbeat={settings.heartbeat_interval_seconds}s, "
        f"pool_cron={settings.pool_cron_day_of_week} {settings.pool_cron_hour:02d}:{settings.pool_cron_minute:02d}"
    )
    # 启动时若 pool 文件不存在 / 过期，异步触发一次构建（不阻塞 HTTP 启动）
    ensure_pool_fresh_on_startup()
    yield
    scheduler.shutdown(wait=False)
    logger.info("[main] scheduler stopped")


app = FastAPI(title=settings.app_name, lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins_list,
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["X-Gateway-Token", "Content-Type", "Authorization"],
)
app.add_middleware(TokenAuthMiddleware)

app.include_router(quote.router)
app.include_router(trade.router)
app.include_router(account.router)
app.include_router(watchlist.router)
app.include_router(pool.router)


@app.get("/")
def root():
    return {"app": settings.app_name, "docs": "/docs"}


@app.get("/health")
def health():
    return {
        "status": "ok",
        "server_time": datetime.now().isoformat(timespec="seconds"),
        "market_status": market_status(),
        "mootdx_ready": mootdx_client.ready,
        "mootdx_last_ok_ago_seconds": mootdx_client.last_ok_ago_seconds,
    }
