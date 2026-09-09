"""健康检查端点."""

from datetime import UTC, datetime

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from nocobase_py import __version__

router = APIRouter(prefix="/api", tags=["health"])


@router.get("/health")
async def health() -> JSONResponse:
    """服务健康检查."""
    return JSONResponse(
        status_code=200,
        content={
            "status": "ok",
            "service": "nocobase-py",
            "version": __version__,
            "timestamp": datetime.now(UTC).isoformat(),
        },
    )
