"""Redis 客户端 — 供限流/缓存/配额共享."""

import redis.asyncio as redis
from nocobase_py.config import get_settings

_redis: redis.Redis | None = None


async def get_redis() -> redis.Redis:
    """获取单例 Redis 客户端."""
    global _redis
    if _redis is None:
        s = get_settings()
        _redis = redis.Redis(
            host=s.redis_host,
            port=s.redis_port,
            decode_responses=True,
        )
    return _redis


async def close_redis() -> None:
    """关闭 Redis 连接."""
    global _redis
    if _redis is not None:
        await _redis.close()
        _redis = None
