from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.core.easytrader_client import easytrader_client

router = APIRouter(prefix="/trade", tags=["trade"])


class OrderRequest(BaseModel):
    code: str = Field(..., description="6位股票代码")
    price: float = Field(..., gt=0)
    amount: int = Field(..., gt=0, description="股数，必须100倍数")


class CancelRequest(BaseModel):
    entrust_no: str


@router.post("/buy")
def buy(req: OrderRequest):
    return easytrader_client.buy(req.code, req.price, req.amount)


@router.post("/sell")
def sell(req: OrderRequest):
    return easytrader_client.sell(req.code, req.price, req.amount)


@router.post("/cancel")
def cancel(req: CancelRequest):
    return easytrader_client.cancel(req.entrust_no)
