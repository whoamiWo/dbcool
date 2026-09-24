"""R11 用户 LLM 配额追踪 — 按日统计 token / 调用次数 (Redis 版)."""

from __future__ import annotations

import calendar
import json
import time
from dataclasses import dataclass, asdict

from nocobase_py.redis_client import get_redis


@dataclass
class UserQuota:
    daily_calls: int = 0
    daily_tokens: int = 0
    monthly_calls: int = 0
    monthly_tokens: int = 0
    last_call_ts: float = 0.0


class QuotaService:
    """基于 Redis 的用户配额服务，支持多副本一致性。

    Usage:
        quota = QuotaService(daily_call_limit=100, daily_token_limit=50000, ...)
        await quota.consume(user_id, calls=1, tokens=1000)  # 超限抛异常
        remaining = await quota.remaining(user_id)
    """

    def __init__(
        self,
        daily_call_limit: int = 100,
        daily_token_limit: int = 50000,
        monthly_call_limit: int = 3000,
        monthly_token_limit: int = 1500000,
    ) -> None:
        self.daily_call_limit = daily_call_limit
        self.daily_token_limit = daily_token_limit
        self.monthly_call_limit = monthly_call_limit
        self.monthly_token_limit = monthly_token_limit
        self._prefix = "llm_quota:"

    def _daily_key(self, user_id: str, date: str | None = None) -> str:
        if date is None:
            date = time.strftime("%Y-%m-%d")
        return f"{self._prefix}{user_id}:daily:{date}"

    def _monthly_key(self, user_id: str, month: str | None = None) -> str:
        if month is None:
            month = time.strftime("%Y-%m")
        return f"{self._prefix}{user_id}:monthly:{month}"

    async def consume(self, user_id: str, calls: int = 1, tokens: int = 0) -> None:
        """消费配额。超限抛 ValueError."""
        now = time.time()
        redis = await get_redis()

        daily_key = self._daily_key(user_id)
        monthly_key = self._monthly_key(user_id)

        # 获取当前配额
        d = await self._get_daily(redis, user_id)
        m = await self._get_monthly(redis, user_id)

        # 检查限制
        new_daily_calls = d.daily_calls + calls
        new_daily_tokens = d.daily_tokens + tokens
        new_monthly_calls = m.monthly_calls + calls
        new_monthly_tokens = m.monthly_tokens + tokens

        if new_daily_calls > self.daily_call_limit:
            raise ValueError(f"超出每日调用次数限制 ({self.daily_call_limit})")
        if new_daily_tokens > self.daily_token_limit:
            raise ValueError(f"超出每日 token 限制 ({self.daily_token_limit})")
        if new_monthly_calls > self.monthly_call_limit:
            raise ValueError(f"超出每月调用次数限制 ({self.monthly_call_limit})")
        if new_monthly_tokens > self.monthly_token_limit:
            raise ValueError(f"超出每月 token 限制 ({self.monthly_token_limit})")

        # 更新配额
        await self._update_daily(redis, user_id, calls, tokens, now)
        await self._update_monthly(redis, user_id, calls, tokens)

    async def _get_daily(self, redis, user_id: str) -> UserQuota:
        """获取或初始化日配额."""
        daily_key = self._daily_key(user_id)
        data = await redis.hgetall(daily_key)
        if not data:
            return UserQuota()
        # 检查日期是否过期
        today = time.strftime("%Y-%m-%d")
        if data.get("date") != today:
            return UserQuota()
        return UserQuota(
            daily_calls=int(data.get("calls", 0)),
            daily_tokens=int(data.get("tokens", 0)),
            last_call_ts=float(data.get("last_ts", 0.0)),
        )

    async def _get_monthly(self, redis, user_id: str) -> UserQuota:
        """获取或初始化月配额."""
        monthly_key = self._monthly_key(user_id)
        data = await redis.hgetall(monthly_key)
        if not data:
            return UserQuota()
        # 检查月份是否过期
        this_month = time.strftime("%Y-%m")
        if data.get("month") != this_month:
            return UserQuota()
        return UserQuota(
            monthly_calls=int(data.get("calls", 0)),
            monthly_tokens=int(data.get("tokens", 0)),
            last_call_ts=float(data.get("last_ts", 0.0)),
        )

    async def _update_daily(self, redis, user_id: str, calls: int, tokens: int, now: float) -> None:
        """原子累加日配额 (INCR + SETEX)."""
        daily_key = self._daily_key(user_id)
        today = time.strftime("%Y-%m-%d")

        # 原子累加 calls 和 tokens
        await redis.hincrby(daily_key, "calls", calls)
        await redis.hincrby(daily_key, "tokens", tokens)
        await redis.hset(daily_key, "date", today)
        await redis.hset(daily_key, "last_ts", str(now))
        # 过期时间设为 2 天，确保跨日可见
        await redis.expire(daily_key, 86400 * 2)

    async def _update_monthly(self, redis, user_id: str, calls: int, tokens: int) -> None:
        """原子累加月配额 (INCR + SETEX)."""
        monthly_key = self._monthly_key(user_id)
        this_month = time.strftime("%Y-%m")

        # 原子累加 calls 和 tokens
        await redis.hincrby(monthly_key, "calls", calls)
        await redis.hincrby(monthly_key, "tokens", tokens)
        await redis.hset(monthly_key, "month", this_month)
        await redis.hset(monthly_key, "last_ts", str(time.time()))

        # 计算到下个月 1 号的秒数
        year, month = map(int, this_month.split("-"))
        _, last_day = calendar.monthrange(year, month)
        day = int(time.strftime("%d"))
        expire_at = 86400 * (last_day - day + 2)
        await redis.expire(monthly_key, expire_at)

    async def remaining(self, user_id: str) -> dict:
        """返回剩余配额."""
        redis = await get_redis()
        d = await self._get_daily(redis, user_id)
        m = await self._get_monthly(redis, user_id)

        return {
            "daily_calls_remaining": max(0, self.daily_call_limit - d.daily_calls),
            "daily_tokens_remaining": max(0, self.daily_token_limit - d.daily_tokens),
            "monthly_calls_remaining": max(0, self.monthly_call_limit - m.monthly_calls),
            "monthly_tokens_remaining": max(0, self.monthly_token_limit - m.monthly_tokens),
            "last_call_ts": max(d.last_call_ts, m.last_call_ts),
        }

    async def reset_daily(self, user_id: str) -> None:
        """手动重置日配额."""
        redis = await get_redis()
        daily_key = self._daily_key(user_id)
        await redis.delete(daily_key)

    async def reset_monthly(self, user_id: str) -> None:
        """手动重置月配额."""
        redis = await get_redis()
        monthly_key = self._monthly_key(user_id)
        await redis.delete(monthly_key)
