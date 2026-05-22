from fastapi import APIRouter

from app.core.easytrader_client import easytrader_client

router = APIRouter(prefix="/account", tags=["account"])


@router.get("/balance")
def balance():
    return easytrader_client.balance()


@router.get("/position")
def position():
    return easytrader_client.position()
