"""R11 LLM 成本控制 — 限流 + 缓存 + 配额测试."""

import asyncio
import time

import pytest
from fastapi.testclient import TestClient

from nocobase_py.main import create_app
from nocobase_py.middleware.rate_limit import SlidingWindowRateLimiter
from nocobase_py.services.llm_cache import LLMCache
from nocobase_py.services.quota import QuotaService


@pytest.fixture
def client():
    app = create_app()
    with TestClient(app) as c:
        yield c


class TestSlidingWindowRateLimiter:
    @pytest.mark.asyncio
    async def test_consume_within_limit(self):
        limiter = SlidingWindowRateLimiter(max_requests=3, window_seconds=60)
        await limiter.consume("u1")
        await limiter.consume("u1")
        await limiter.consume("u1")
        assert await limiter.remaining("u1") == 0

    @pytest.mark.asyncio
    async def test_consume_exceeds_limit_raises(self):
        limiter = SlidingWindowRateLimiter(max_requests=2, window_seconds=60)
        await limiter.consume("u1")
        await limiter.consume("u1")
        with pytest.raises(Exception) as exc:
            await limiter.consume("u1")
        assert "429" in str(exc.value) or "频繁" in str(exc.value)

    @pytest.mark.asyncio
    async def test_isolated_between_users(self):
        limiter = SlidingWindowRateLimiter(max_requests=1, window_seconds=60)
        await limiter.consume("u1")
        await limiter.consume("u2")
        assert await limiter.remaining("u2") == 0
        assert await limiter.remaining("u1") == 0

    @pytest.mark.asyncio
    async def test_reset_clears_state(self):
        limiter = SlidingWindowRateLimiter(max_requests=2, window_seconds=60)
        await limiter.consume("u1")
        await limiter.consume("u1")
        await limiter.reset("u1")
        assert await limiter.remaining("u1") == 2


class TestLLMCache:
    @pytest.mark.asyncio
    async def test_put_and_get(self):
        cache = LLMCache(max_size=10, ttl_seconds=60)
        await cache.put("gpt-4", "hello", "response1")
        result = await cache.get("gpt-4", "hello")
        assert result == "response1"

    @pytest.mark.asyncio
    async def test_miss_returns_none(self):
        cache = LLMCache(max_size=10, ttl_seconds=60)
        result = await cache.get("gpt-4", "nonexistent")
        assert result is None

    @pytest.mark.asyncio
    async def test_stats_tracking(self):
        cache = LLMCache(max_size=10, ttl_seconds=60)
        await cache.put("gpt-4", "test", "resp")
        await cache.get("gpt-4", "test")  # hit
        await cache.get("gpt-4", "other")  # miss
        stats = await cache.stats()
        assert stats["hits"] >= 1
        assert stats["misses"] >= 1


class TestQuotaService:
    @pytest.mark.asyncio
    async def test_consume_within_limit(self):
        quota = QuotaService(daily_call_limit=10, daily_token_limit=5000)
        await quota.consume("u1", calls=1, tokens=100)
        remaining = await quota.remaining("u1")
        assert remaining["daily_calls_remaining"] == 9

    @pytest.mark.asyncio
    async def test_consume_exceeds_daily_call_limit(self):
        quota = QuotaService(daily_call_limit=2, daily_token_limit=5000)
        await quota.consume("u1", calls=1)
        await quota.consume("u1", calls=1)
        with pytest.raises(ValueError) as exc:
            await quota.consume("u1", calls=1)
        assert "每日调用次数限制" in str(exc.value)

    @pytest.mark.asyncio
    async def test_consume_exceeds_daily_token_limit(self):
        quota = QuotaService(daily_call_limit=10, daily_token_limit=100)
        await quota.consume("u1", calls=1, tokens=50)
        await quota.consume("u1", calls=1, tokens=50)
        with pytest.raises(ValueError) as exc:
            await quota.consume("u1", calls=1, tokens=1)
        assert "每日 token 限制" in str(exc.value)

    @pytest.mark.asyncio
    async def test_monthly_limits(self):
        quota = QuotaService(monthly_call_limit=5, monthly_token_limit=1000)
        await quota.consume("u1", calls=3, tokens=200)
        remaining = await quota.remaining("u1")
        assert remaining["monthly_calls_remaining"] == 2
        await quota.consume("u1", calls=2, tokens=300)
        with pytest.raises(ValueError) as exc:
            await quota.consume("u1", calls=1)
        assert "每月调用次数限制" in str(exc.value)

    @pytest.mark.asyncio
    async def test_reset_daily(self):
        quota = QuotaService(daily_call_limit=2, daily_token_limit=5000)
        await quota.consume("u1", calls=2)
        await quota.reset_daily("u1")
        remaining = await quota.remaining("u1")
        assert remaining["daily_calls_remaining"] == 2


# ---- W1 (P0) 新增：Redis 不可用须明确报错 + 内存 fallback ----
class TestRedisUnavailable:
    @pytest.mark.asyncio
    async def test_redis_unavailable_raises(self, monkeypatch):
        """Redis 连接失败须抛 RedisUnavailableError，禁止静默降级。"""
        from nocobase_py import redis_client as rc
        from nocobase_py.config import get_settings

        # 强制启用 Redis，但指向不可达端口
        monkeypatch.setattr(get_settings(), "redis_enabled", True)
        monkeypatch.setattr(get_settings(), "redis_host", "127.0.0.1")
        monkeypatch.setattr(get_settings(), "redis_port", 1)
        # 重置单例
        rc._redis = None
        with pytest.raises(rc.RedisUnavailableError) as exc:
            await rc.get_redis()
        assert "Redis 连接失败" in str(exc.value)
        rc._redis = None

    @pytest.mark.asyncio
    async def test_memory_fallback_when_disabled(self, monkeypatch):
        """REDIS_ENABLED=false 时返回 _MemoryRedis，不抛错。"""
        from nocobase_py import redis_client as rc
        from nocobase_py.config import get_settings

        monkeypatch.setattr(get_settings(), "redis_enabled", False)
        rc._redis = None
        rc._memory = None
        r = await rc.get_redis()
        assert isinstance(r, rc._MemoryRedis)
        await r.setex("k", 10, "v")
        assert await r.get("k") == "v"
        rc._memory = None

    @pytest.mark.asyncio
    async def test_memory_fallback_concurrent_incr(self, monkeypatch):
        """内存 fallback 并发 INCR 一致性（单进程内正确）。"""
        from nocobase_py import redis_client as rc
        from nocobase_py.config import get_settings

        monkeypatch.setattr(get_settings(), "redis_enabled", False)
        rc._redis = None
        rc._memory = None
        r = await rc.get_redis()

        async def inc(n):
            for _ in range(n):
                await r.incr("counter")

        await asyncio.gather(inc(10), inc(10), inc(10))
        assert await r.get("counter") == "30"
        rc._memory = None
