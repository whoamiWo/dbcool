"""R11 LLM 响应缓存 — 相同 prompt + model 组合命中缓存，节省 API 调用成本 (Redis 版)."""

from __future__ import annotations

import hashlib
import json
import time
from typing import Any

from nocobase_py.redis_client import get_redis


class LLMCache:
    """LRU Redis 缓存 (带 TTL)，用于 LLM 响应复用，支持多副本一致性。

    Usage:
        cache = LLMCache(max_size=256, ttl_seconds=300)
        cached = await cache.get(model="gpt-4", prompt="hello")
        if cached is not None:
            return cached
        response = await call_llm(...)
        await cache.put(model="gpt-4", prompt="hello", response=response)
    """

    def __init__(self, max_size: int = 256, ttl_seconds: int = 300) -> None:
        self.max_size = max_size
        self.ttl_seconds = ttl_seconds
        self._prefix = "llm_cache:"
        # R11: hit/miss 统计，用于监控缓存效果 + 命中率告警
        self._hits_key = "llm_cache:stats:hits"
        self._misses_key = "llm_cache:stats:misses"

    def _key(self, model: str, prompt: str) -> str:
        raw = json.dumps({"model": model, "prompt": prompt}, sort_keys=True)
        return self._prefix + hashlib.sha256(raw.encode()).hexdigest()[:16]

    async def get(self, model: str, prompt: str) -> Any | None:
        key = self._key(model, prompt)
        redis = await get_redis()
        entry = await redis.get(key)
        if entry is None:
            await redis.incr(self._misses_key)
            return None
        try:
            value, expires_at = json.loads(entry)
            if time.monotonic() > expires_at:
                await redis.delete(key)
                await redis.incr(self._misses_key)
                return None
            await redis.incr(self._hits_key)
            return value
        except Exception:
            await redis.incr(self._misses_key)
            return None

    async def put(self, model: str, prompt: str, response: Any) -> None:
        key = self._key(model, prompt)
        redis = await get_redis()

        # LRU: 如果超过 max_size，删除最旧的条目
        # 简单实现：用一个有序集合记录插入顺序
        lru_key = self._prefix + "lru"
        now = time.monotonic()

        # 检查当前大小
        current_size = await redis.zcard(lru_key)
        if current_size >= self.max_size:
            # 删除最旧的条目
            oldest = await redis.zrange(lru_key, 0, 0, withscores=True)
            if oldest:
                oldest_key = oldest[0][0]
                await redis.delete(oldest_key)
            await redis.zremrangebyrank(lru_key, 0, 0)

        # 存入数据
        expire_at = now + self.ttl_seconds
        await redis.setex(key, self.ttl_seconds, json.dumps((response, expire_at)))

        # 更新 LRU 索引
        await redis.zadd(lru_key, {key: now})
        # 设置 LRU 键的过期时间（略大于 TTL）
        await redis.expire(lru_key, self.ttl_seconds + 10)

    async def stats(self) -> dict[str, Any]:
        redis = await get_redis()
        hits = int(await redis.get(self._hits_key) or 0)
        misses = int(await redis.get(self._misses_key) or 0)
        total = hits + misses
        hit_rate = (hits / total) if total else 0.0

        # 当前缓存大小
        lru_key = self._prefix + "lru"
        size = await redis.zcard(lru_key)

        return {
            "size": size,
            "max_size": self.max_size,
            "ttl_seconds": self.ttl_seconds,
            "hits": hits,
            "misses": misses,
            "hit_rate": round(hit_rate, 4),
        }

    async def should_warn_low_hit_rate(self, threshold: float = 0.4, min_calls: int = 20) -> bool:
        """判断是否应该发出低命中率告警."""
        redis = await get_redis()
        hits = int(await redis.get(self._hits_key) or 0)
        misses = int(await redis.get(self._misses_key) or 0)
        total = hits + misses
        if total < min_calls:
            return False
        return (hits / total) < threshold

    async def clear(self) -> None:
        redis = await get_redis()
        # 获取所有缓存 key
        keys = await redis.keys(self._prefix + "*")
        if keys:
            await redis.delete(*keys)
        # 重置统计
        await redis.set(self._hits_key, 0)
        await redis.set(self._misses_key, 0)
