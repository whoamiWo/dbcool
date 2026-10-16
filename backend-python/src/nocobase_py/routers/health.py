"""健康检查端点."""

import logging
import os
from datetime import UTC, datetime

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from nocobase_py import __version__

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["health"])


@router.get("/health")
async def health() -> JSONResponse:
    """Liveness 探针 — 进程活着即 ok，不触碰任何下游依赖."""
    return JSONResponse(
        status_code=200,
        content={
            "status": "ok",
            "service": "nocobase-py",
            "version": __version__,
            "timestamp": datetime.now(UTC).isoformat(),
        },
    )


async def _check_redis() -> bool:
    """Redis ping — 失败返回 False，不抛异常."""
    try:
        from nocobase_py.redis_client import get_redis

        redis = await get_redis()
        await redis.ping()
        return True
    except Exception as exc:  # noqa: BLE001
        logger.warning("[health/ready] redis 不健康: %s", exc)
        return False


async def _check_postgres() -> bool:
    """Postgres 连接探测 — 用项目已依赖的 asyncpg（连接参数来自环境变量）."""
    import asyncpg

    try:
        host = os.environ.get("POSTGRES_HOST", "localhost")
        port = int(os.environ.get("POSTGRES_PORT", "5432"))
        dbname = os.environ.get("POSTGRES_DB", "nocobase")
        user = os.environ.get("POSTGRES_USER", "nocobase")
        password = os.environ.get("POSTGRES_PASSWORD", "")
        conn = await asyncpg.connect(
            host=host, port=port, database=dbname, user=user, password=password, timeout=3
        )
        try:
            await conn.fetchval("SELECT 1")
        finally:
            await conn.close()
        return True
    except Exception as exc:  # noqa: BLE001
        logger.warning("[health/ready] postgres 不健康: %s", exc)
        return False


@router.get("/health/ready")
async def ready() -> JSONResponse:
    """Readiness 探针 — 校验下游依赖（Redis / Postgres），任一失败返回 503.

    Compose healthcheck 依赖此端点判断容器是否真正可接流量，
    避免「进程起来了但连不上 DB」时被误判为 healthy。
    """
    components: dict[str, object] = {}
    all_ok = True

    redis_ok = await _check_redis()
    components["redis"] = "ok" if redis_ok else "down"
    all_ok &= redis_ok

    pg_ok = await _check_postgres()
    components["postgres"] = "ok" if pg_ok else "down"
    all_ok &= pg_ok

    body = {
        "status": "ok" if all_ok else "degraded",
        "service": "nocobase-py",
        "version": __version__,
        "components": components,
        "timestamp": datetime.now(UTC).isoformat(),
    }

    if all_ok:
        return JSONResponse(status_code=200, content=body)
    logger.warning("[health/ready] 组件不健康: %s", components)
    return JSONResponse(status_code=503, content=body)
