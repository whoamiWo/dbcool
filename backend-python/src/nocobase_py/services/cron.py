"""R11 cron 表达式(5 字段极简实现).

支持格式:`分 时 日 月 周`
字段范围:
- 分:0-59
- 时:0-23
- 日:1-31
- 月:1-12
- 周:0-6(0 = 周日)

支持语法:
- `*` 任意
- `5` 数字
- `1,5,10` 列表
- `1-10` 范围
- `*/5` 步长
- `1-30/5` 范围步长

仅够 R11 告警巡检 / 周期性任务使用,不替代完整 cron 库.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone


@dataclass
class CronSchedule:
    """cron 解析结果."""

    minutes: list[int]
    hours: list[int]
    days: list[int]
    months: list[int]
    weekdays: list[int]
    raw: str

    def matches(self, dt: datetime | None = None) -> bool:
        dt = dt or datetime.now(timezone.utc)
        return (
            dt.minute in self.minutes
            and dt.hour in self.hours
            and dt.day in self.days
            and dt.month in self.months
            and (dt.weekday() + 1) % 7 in self.weekdays  # 周一=1, 周日=7 -> 我们的 0-6
        )

    def next_fire_after(self, now: datetime | None = None) -> datetime:
        """计算下一次触发时间(简化:扫描未来 366 天)."""
        now = now or datetime.now(timezone.utc)
        # 替换秒/微秒为 0
        candidate = now.replace(second=0, microsecond=0)
        for _ in range(366 * 24 * 60):
            candidate = candidate + timedelta(minutes=1)
            if self.matches(candidate):
                return candidate
        # 不太可能,兜底返回 1 天后
        return now + timedelta(days=1)

    def seconds_until_next(self, now: datetime | None = None) -> float:
        next_fire = self.next_fire_after(now)
        now = now or datetime.now(timezone.utc)
        return max(0.0, (next_fire - now).total_seconds())


_RANGE_LIMITS = {
    "minute": (0, 59),
    "hour": (0, 23),
    "day": (1, 31),
    "month": (1, 12),
    "weekday": (0, 6),
}


def _parse_field(field: str, name: str) -> list[int]:
    """解析单个 cron 字段,返回允许的整数列表."""
    lo, hi = _RANGE_LIMITS[name]
    result: set[int] = set()
    for part in field.split(","):
        part = part.strip()
        if not part:
            continue
        if "/" in part:
            base, step_s = part.split("/", 1)
            step = int(step_s)
            if step <= 0:
                raise ValueError(f"step must be > 0: {part}")
            if base == "*":
                start, end = lo, hi
            elif "-" in base:
                start_s, end_s = base.split("-", 1)
                start, end = int(start_s), int(end_s)
            else:
                start = int(base)
                end = hi
            for v in range(start, end + 1, step):
                if lo <= v <= hi:
                    result.add(v)
        elif part == "*":
            for v in range(lo, hi + 1):
                result.add(v)
        elif "-" in part:
            start_s, end_s = part.split("-", 1)
            start, end = int(start_s), int(end_s)
            if start > end:
                raise ValueError(f"invalid range in {name}: {part}")
            for v in range(start, end + 1):
                if lo <= v <= hi:
                    result.add(v)
        else:
            v = int(part)
            if v < lo or v > hi:
                raise ValueError(f"{name} value out of range [{lo},{hi}]: {v}")
            result.add(v)
    if not result:
        raise ValueError(f"{name} parsed to empty set: {field!r}")
    return sorted(result)


def parse_cron(expr: str) -> CronSchedule:
    """解析 5 字段 cron 表达式.

    Example:
        "*/5 * * * *"   → 每 5 分钟
        "0 9 * * 1-5"   → 工作日 9 点
        "30 0 1 * *"    → 每月 1 日 0:30
        "0 0 * * 0"     → 每周日 0 点
    """
    parts = expr.strip().split()
    if len(parts) != 5:
        raise ValueError(f"cron 表达式需 5 字段,得到 {len(parts)}: {expr!r}")
    minutes = _parse_field(parts[0], "minute")
    hours = _parse_field(parts[1], "hour")
    days = _parse_field(parts[2], "day")
    months = _parse_field(parts[3], "month")
    weekdays = _parse_field(parts[4], "weekday")
    return CronSchedule(
        minutes=minutes,
        hours=hours,
        days=days,
        months=months,
        weekdays=weekdays,
        raw=expr,
    )
