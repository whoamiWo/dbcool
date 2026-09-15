"""R11 用户 LLM 配额追踪 — 按日统计 token / 调用次数."""

from __future__ import annotations

import time
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any


@dataclass
class UserQuota:
    daily_calls: int = 0
    daily_tokens: int = 0
    monthly_calls: int = 0
    monthly_tokens: int = 0
    last_call_ts: float = 0.0


class QuotaService:
    """内存级用户配额跟踪(可扩展到持久化).

    Usage:
        quota = QuotaService(daily_call_limit=100, daily_token_limit=50000)
        quota.consume(user_id, tokens=120)  # 超限抛 ValueError
    """

    def __init__(
        self,
        daily_call_limit: int = 100,
        daily_token_limit: int = 50_000,
        monthly_call_limit: int = 2000,
        monthly_token_limit: int = 1_000_000,
    ) -> None:
        self.daily_call_limit = daily_call_limit
        self.daily_token_limit = daily_token_limit
        self.monthly_call_limit = monthly_call_limit
        self.monthly_token_limit = monthly_token_limit
        self._quotas: dict[str, UserQuota] = defaultdict(UserQuota)
        # key = user_id, value per-period tracker
        self._day_start: dict[str, float] = {}
        self._month_start: dict[str, float] = {}

    @staticmethod
    def _today() -> float:
        """返回当天 00:00 的时间戳(秒)."""
        import datetime as _dt

        now = _dt.datetime.now(_dt.timezone.utc)
        day = _dt.datetime(now.year, now.month, now.day, tzinfo=_dt.timezone.utc)
        return day.timestamp()

    @staticmethod
    def _this_month() -> float:
        import datetime as _dt

        now = _dt.datetime.now(_dt.timezone.utc)
        first = _dt.datetime(now.year, now.month, 1, tzinfo=_dt.timezone.utc)
        return first.timestamp()

    def _ensure_period(self, user_id: str) -> None:
        now = time.time()
        day = self._today()
        month = self._this_month()
        if self._day_start.get(user_id, 0) < day:
            self._day_start[user_id] = day
            q = self._quotas[user_id]
            q.daily_calls = 0
            q.daily_tokens = 0
        if self._month_start.get(user_id, 0) < month:
            self._month_start[user_id] = month
            q = self._quotas[user_id]
            q.monthly_calls = 0
            q.monthly_tokens = 0

    def consume(self, user_id: str, tokens: int = 0) -> None:
        """消耗配额;超限抛 ValueError 并发出告警."""
        self._ensure_period(user_id)
        q = self._quotas[user_id]
        q.daily_calls += 1
        q.daily_tokens += tokens
        q.monthly_calls += 1
        q.monthly_tokens += tokens
        q.last_call_ts = time.time()

        # R11: 超限前先做告警,再抛出
        if q.daily_calls > self.daily_call_limit:
            self._alert_quota(user_id, "daily_calls", q.daily_calls, self.daily_call_limit)
            raise ValueError(f"每日调用已达上限 ({self.daily_call_limit})")
        if q.daily_tokens > self.daily_token_limit:
            self._alert_quota(user_id, "daily_tokens", q.daily_tokens, self.daily_token_limit)
            raise ValueError(f"每日 token 已达上限 ({self.daily_token_limit})")
        if q.monthly_calls > self.monthly_call_limit:
            self._alert_quota(user_id, "monthly_calls", q.monthly_calls, self.monthly_call_limit)
            raise ValueError(f"每月调用已达上限 ({self.monthly_call_limit})")
        if q.monthly_tokens > self.monthly_token_limit:
            self._alert_quota(user_id, "monthly_tokens", q.monthly_tokens, self.monthly_token_limit)
            raise ValueError(f"每月 token 已达上限 ({self.monthly_token_limit})")

    @staticmethod
    def _alert_quota(user_id: str, scope: str, current: int, limit: int) -> None:
        """配额超限告警(可选,避免硬依赖)."""
        try:
            from nocobase_py.services.alerts import get_collector

            get_collector().emit(
                kind="quota_exceeded",
                user_id=user_id,
                detail={"scope": scope, "current": current, "limit": limit},
            )
        except Exception:  # noqa: BLE001
            # 告警链路故障不应阻塞业务
            pass

    def remaining(self, user_id: str) -> dict[str, Any]:
        """返回当前用户剩余额度."""
        self._ensure_period(user_id)
        q = self._quotas[user_id]
        return {
            "daily_calls_remaining": max(0, self.daily_call_limit - q.daily_calls),
            "daily_tokens_remaining": max(0, self.daily_token_limit - q.daily_tokens),
            "monthly_calls_remaining": max(0, self.monthly_call_limit - q.monthly_calls),
            "monthly_tokens_remaining": max(0, self.monthly_token_limit - q.monthly_tokens),
        }
