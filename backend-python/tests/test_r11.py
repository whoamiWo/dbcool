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
