from contextlib import asynccontextmanager
from datetime import datetime

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.interval import IntervalTrigger
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger

from app.api import account, quote, trade, watchlist
from app.config import settings
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
    scheduler.start()
    logger.info(f"[main] scheduler started, heartbeat={settings.heartbeat_interval_seconds}s")
    yield
    scheduler.shutdown(wait=False)
    logger.info("[main] scheduler stopped")


app = FastAPI(title=settings.app_name, lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
app.add_middleware(TokenAuthMiddleware)

app.include_router(quote.router)
app.include_router(trade.router)
app.include_router(account.router)
app.include_router(watchlist.router)


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
