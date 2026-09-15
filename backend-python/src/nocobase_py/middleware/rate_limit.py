"""R11 滑动窗口限流中间件 — 防止 LLM 调成本失控."""

from __future__ import annotations

import time
from collections import defaultdict
from collections.abc import Callable
from typing import Any

from fastapi import HTTPException, Request, status


class SlidingWindowRateLimiter:
    """基于内存的滑动窗口限流器(per-user).

    Usage:
        limiter = SlidingWindowRateLimiter(max_requests=30, window_seconds=60)
        @app.get("/api/ai/chat")
        async def chat(user: AuthUser = Depends(get_current_user)):
            limiter.consume(user.user_id)  # 超限抛 429
            ...
    """

    def __init__(self, max_requests: int = 30, window_seconds: int = 60) -> None:
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._buckets: dict[str, list[float]] = defaultdict(list)

    def consume(self, key: str) -> None:
        """消费一个配额。超限抛 HTTPException 429 并发出告警."""
        now = time.monotonic()
        cutoff = now - self.window_seconds
        self._buckets[key] = [t for t in self._buckets[key] if t > cutoff]
        if len(self._buckets[key]) >= self.max_requests:
            self._alert_rate_limit(key, self.max_requests, self.window_seconds)
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=f"请求过于频繁 ({self.max_requests}/{self.window_seconds}s),请稍后再试",
            )
        self._buckets[key].append(now)

    @staticmethod
    def _alert_rate_limit(key: str, max_requests: int, window_seconds: int) -> None:
        """限流触发告警(可选,避免硬依赖)."""
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

    def remaining(self, key: str) -> int:
        """返回当前窗口剩余配额."""
        now = time.monotonic()
        cutoff = now - self.window_seconds
        current = sum(1 for t in self._buckets[key] if t > cutoff)
        return max(0, self.max_requests - current)

    def reset(self, key: str) -> None:
        """手动清零某 key 的计数器."""
        self._buckets.pop(key, None)


def rate_limit_dependency(limiter: SlidingWindowRateLimiter) -> Callable[..., Any]:
    """返回 FastAPI Depends 可用的限流函数."""

    async def _check(request: Request) -> None:
        # 优先取 JWT 中的 user_id(已由 get_current_user 解析);fallback 到 IP
        user_id: str = getattr(request.state, "user_id", None) or request.client.host or "unknown"
        limiter.consume(f"llm:{user_id}")

    return _check
