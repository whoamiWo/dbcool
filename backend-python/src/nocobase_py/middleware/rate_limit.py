"""R11 滑动窗口限流中间件 — 防止 LLM 调成本失控 (Redis 版)."""

from __future__ import annotations

import time
from collections.abc import Callable
from typing import Any

from fastapi import HTTPException, Request, status

from nocobase_py.redis_client import get_redis


class SlidingWindowRateLimiter:
    """基于 Redis 的滑动窗口限流器(per-user)，支持多副本一致性。

    Usage:
        limiter = SlidingWindowRateLimiter(max_requests=30, window_seconds=60)
        @app.get("/api/ai/chat")
        async def chat(user: AuthUser = Depends(get_current_user)):
            await limiter.consume(user.user_id)  # 超限抛 429
            ...
    """

    def __init__(self, max_requests: int = 30, window_seconds: int = 60) -> None:
        self.max_requests = max_requests
        self.window_seconds = window_seconds

    async def consume(self, key: str) -> None:
        """消费一个配额。超限抛 HTTPException 429 并发出告警."""
        now = time.monotonic()
        cutoff = now - self.window_seconds
        redis = await get_redis()

        # 移除过期条目
        await redis.zremrangebyscore(key, "-inf", cutoff)

        # 检查当前数量
        current = await redis.zcard(key)
        if current >= self.max_requests:
            self._alert_rate_limit(key, self.max_requests, self.window_seconds)
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=f"请求过于频繁 ({self.max_requests}/{self.window_seconds}s),请稍后再试",
            )

        # 添加当前时间戳
        await redis.zadd(key, {str(now): now})
        # 设置过期时间（略大于窗口期）
        await redis.expire(key, self.window_seconds + 10)

    @staticmethod
    def _alert_rate_limit(key: str, max_requests: int, window_seconds: int) -> None:
        """限流触发告警 (可选，避免硬依赖)."""
        try:
            from nocobase_py.services.alerts import get_collector

            # key 形如 llm:user-123,提取 user 部分便于告警定位
            user_id = key.split(":", 1)[1] if ":" in key else key
            get_collector().emit(
                kind="rate_limit_exceeded",
                user_id=user_id,
                detail={
                    "limit": max_requests,
                    "window_seconds": window_seconds,
                },
            )
        except Exception:  # noqa: BLE001
            pass

    async def remaining(self, key: str) -> int:
        """返回当前窗口剩余配额."""
        now = time.monotonic()
        cutoff = now - self.window_seconds
        redis = await get_redis()

        # 移除过期条目
        await redis.zremrangebyscore(key, "-inf", cutoff)
        current = await redis.zcard(key)
        return max(0, self.max_requests - current)

    async def reset(self, key: str) -> None:
        """手动清零某 key 的计数器."""
        redis = await get_redis()
        await redis.delete(key)


def rate_limit_dependency(limiter: SlidingWindowRateLimiter) -> Callable[..., Any]:
    """返回 FastAPI Depends 可用的限流函数."""

    async def _check(request: Request) -> None:
        # 优先取 JWT 中的 user_id(已由 get_current_user 解析);fallback 到 IP
        user_id: str = getattr(request.state, "user_id", None) or request.client.host or "unknown"
        await limiter.consume(f"llm:{user_id}")

    return _check
