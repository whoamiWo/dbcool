"""Redis 客户端 — 供限流/缓存/配额共享。

W1 (P0) 要求：Redis 连接失败须返回明确错误，禁止无感降级到内存。
显式配置 REDIS_ENABLED=false 时，才启用内存 fallback（日志 WARN 提示单副本模式）。
"""

from __future__ import annotations

import logging
from typing import Any

import redis.asyncio as redis

from nocobase_py.config import get_settings

logger = logging.getLogger(__name__)

_redis: redis.Redis | None = None
_memory: "_MemoryRedis | None" = None


class RedisUnavailableError(RuntimeError):
    """Redis 未就绪（未显式禁用）。"""


class _MemoryRedis:
    """内存 Redis 代理（REDIS_ENABLED=false 时使用，单副本模式）。"""

    def __init__(self) -> None:
        self._strings: dict[str, str] = {}
        self._sets: dict[str, dict[str, float]] = {}  # key -> {member: score}
        self._hashes: dict[str, dict[str, str]] = {}  # key -> {field: value}

    async def ping(self) -> str:
        return "PONG"

    async def zadd(self, name: str, mapping: dict[str, float]) -> int:
        self._sets.setdefault(name, {})
        self._sets[name].update(mapping)
        return len(mapping)

    async def zcard(self, name: str) -> int:
        return len(self._sets.get(name, {}))

    async def zrange(
        self, name: str, start: int, stop: int, withscores: bool = False
    ) -> list[tuple[str, float] | str]:
        members = sorted(self._sets.get(name, {}).items(), key=lambda x: x[1])
        end = stop + 1 if stop >= 0 else None
        sliced = members[start:end]
        if withscores:
            return sliced
        return [m[0] for m in sliced]

    async def zremrangebyscore(self, name: str, min_score: float, max_score: float) -> int:
        s = self._sets.get(name, {})
        removed = [k for k, v in s.items() if min_score <= v <= max_score]
        for k in removed:
            del s[k]
        return len(removed)

    async def hgetall(self, name: str) -> dict[str, str]:
        return dict(self._hashes.get(name, {}))

    async def hincrby(self, name: str, key: str, amount: int = 1) -> int:
        h = self._hashes.setdefault(name, {})
        h[key] = str(int(h.get(key, "0")) + amount)
        return int(h[key])

    async def hset(self, name: str, key: str, value: str) -> int:
        self._hashes.setdefault(name, {})[key] = str(value)
        return 1

    async def delete(self, *names: str) -> int:
        count = 0
        for n in names:
            if n in self._strings:
                del self._strings[n]; count += 1
            if n in self._sets:
                del self._sets[n]; count += 1
            if n in self._hashes:
                del self._hashes[n]; count += 1
        return count

    async def setex(self, name: str, time: int, value: str) -> int:
        self._strings[name] = value
        return 1

    async def expire(self, name: str, time: int) -> bool:
        return name in self._strings or name in self._sets or name in self._hashes

    async def get(self, name: str) -> str | None:
        return self._strings.get(name)

    async def keys(self, pattern: str) -> list[str]:
        # 简单通配符匹配（仅支持 *）
        if pattern == "*":
            return list(self._strings.keys()) + list(self._sets.keys()) + list(self._hashes.keys())
        return [k for k in self._strings if k.startswith(pattern.replace("*", ""))]

    async def incr(self, name: str) -> int:
        self._strings[name] = str(int(self._strings.get(name, "0")) + 1)
        return int(self._strings[name])


async def get_redis() -> redis.Redis | _MemoryRedis:
    """获取单例 Redis 客户端。

    - 默认：连接失败抛 RedisUnavailableError（禁止静默降级）。
    - REDIS_ENABLED=false：返回 _MemoryRedis（单副本模式，日志 WARN）。
    """
    global _redis, _memory
    s = get_settings()

    if not s.redis_enabled:
        if _memory is None:
            _memory = _MemoryRedis()
            logger.warning(
                "REDIS_ENABLED=false -> 限流/缓存/配额降级为单进程内存模式，"
                "多副本部署将失去一致性（单副本模式）。"
            )
        return _memory

    if _redis is not None:
        return _redis

    try:
        client = redis.Redis(host=s.redis_host, port=s.redis_port, decode_responses=True)
        await client.ping()
    except Exception as e:
        raise RedisUnavailableError(
            f"Redis 连接失败 (host={s.redis_host}, port={s.redis_port}): {e}"
        ) from e

    _redis = client
    return _redis


async def close_redis() -> None:
    """关闭 Redis 连接。"""
    global _redis, _memory
    if _redis is not None:
        await _redis.close()
        _redis = None
    _memory = None