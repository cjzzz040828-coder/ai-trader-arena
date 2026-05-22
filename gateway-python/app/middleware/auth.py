from fastapi import Request
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse, Response

from app.config import settings

PUBLIC_PATHS = {"/", "/health", "/docs", "/openapi.json", "/redoc"}


class TokenAuthMiddleware(BaseHTTPMiddleware):
    """简单的 X-Gateway-Token 校验，防 cpolar 公网URL被恶意爆刷。"""

    async def dispatch(self, request: Request, call_next) -> Response:
        if request.url.path in PUBLIC_PATHS or request.url.path.startswith("/docs"):
            return await call_next(request)

        token = request.headers.get("X-Gateway-Token", "")
        if token != settings.gateway_token:
            return JSONResponse(status_code=401, content={"detail": "invalid gateway token"})
        return await call_next(request)
