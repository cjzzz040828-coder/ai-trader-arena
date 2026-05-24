"""
easytrader 同花顺PC客户端封装。

【已退役】阶段二改为 Java 端虚拟撮合（见 backend-java 的 OrderService + MatchEngine），
本模块及 /trade/* /account/* 路由不再被 Java 后端调用，requirements.txt 已移除 easytrader。
保留代码供未来真实下单链路回滚参考；connect() 时 import easytrader 失败已 try/except 兜底，
不会影响行情通道。

避坑要点（如重新启用）：
1. 全局单例，避免重复 connect 导致窗口被多份控制
2. 每次 buy/sell/cancel 之前强制 refresh + active_window
3. 所有调用 try/except，记录详细日志，预留 alert_hook
4. 与 easytrader 的所有交互加大锁，避免 UI 自动化并发冲突
"""
from __future__ import annotations

import threading
from typing import Any, Callable

from loguru import logger

from app.config import settings

logger.add(
    settings.log_dir / "trade.log",
    rotation="20 MB",
    retention="30 days",
    encoding="utf-8",
    enqueue=True,
)


def _default_alert(exc: Exception, context: str) -> None:
    logger.error(f"[trade-alert] context={context} exc={exc!r}")


class EasyTraderClient:
    _instance: "EasyTraderClient | None" = None
    _lock = threading.Lock()

    def __new__(cls) -> "EasyTraderClient":
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self) -> None:
        if getattr(self, "_initialized", False):
            return
        self._initialized = True

        self._user: Any | None = None
        self._user_lock = threading.RLock()
        self._alert_hook: Callable[[Exception, str], None] = _default_alert
        self._connected = False

    def set_alert_hook(self, hook: Callable[[Exception, str], None]) -> None:
        self._alert_hook = hook

    def connect(self) -> bool:
        """延迟连接：首次下单/查询时调用。失败不抛，返回 False。"""
        if self._connected:
            return True
        try:
            import easytrader

            with self._user_lock:
                self._user = easytrader.use("ths")
                self._user.connect(settings.ths_exe_path)
                self._connected = True
                logger.info(f"[trade] connected to ths at {settings.ths_exe_path}")
                return True
        except Exception as e:
            self._alert_hook(e, "connect")
            return False

    def _ensure(self) -> bool:
        return self._connected or self.connect()

    def _prepare_window(self) -> None:
        try:
            self._user.refresh()
            self._user.active_window()
        except Exception as e:
            logger.warning(f"[trade] prepare_window failed: {e}")

    # -------- 业务方法 --------
    def balance(self) -> dict:
        if not self._ensure():
            return {"error": "trader not connected"}
        try:
            with self._user_lock:
                self._prepare_window()
                return {"data": self._user.balance}
        except Exception as e:
            self._alert_hook(e, "balance")
            return {"error": str(e)}

    def position(self) -> dict:
        if not self._ensure():
            return {"error": "trader not connected"}
        try:
            with self._user_lock:
                self._prepare_window()
                return {"data": self._user.position}
        except Exception as e:
            self._alert_hook(e, "position")
            return {"error": str(e)}

    def buy(self, code: str, price: float, amount: int) -> dict:
        if not self._ensure():
            return {"error": "trader not connected"}
        try:
            with self._user_lock:
                self._prepare_window()
                result = self._user.buy(code, price=price, amount=amount)
                logger.info(f"[trade] BUY code={code} price={price} amount={amount} -> {result}")
                return {"data": result}
        except Exception as e:
            self._alert_hook(e, f"buy {code}@{price}x{amount}")
            return {"error": str(e)}

    def sell(self, code: str, price: float, amount: int) -> dict:
        if not self._ensure():
            return {"error": "trader not connected"}
        try:
            with self._user_lock:
                self._prepare_window()
                result = self._user.sell(code, price=price, amount=amount)
                logger.info(f"[trade] SELL code={code} price={price} amount={amount} -> {result}")
                return {"data": result}
        except Exception as e:
            self._alert_hook(e, f"sell {code}@{price}x{amount}")
            return {"error": str(e)}

    def cancel(self, entrust_no: str) -> dict:
        if not self._ensure():
            return {"error": "trader not connected"}
        try:
            with self._user_lock:
                self._prepare_window()
                result = self._user.cancel_entrust(entrust_no)
                logger.info(f"[trade] CANCEL entrust_no={entrust_no} -> {result}")
                return {"data": result}
        except Exception as e:
            self._alert_hook(e, f"cancel {entrust_no}")
            return {"error": str(e)}


easytrader_client = EasyTraderClient()
