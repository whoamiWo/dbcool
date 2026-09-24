"""R11 轻量后台调度器 — 用于告警巡检等周期性任务.

不引入 APScheduler,基于 threading.Event 实现:
- 支持周期性任务(interval)
- 支持延迟一次性任务(delay)
- 支持 cancel
- 守护线程,Python 退出时不阻塞
"""

from __future__ import annotations

import logging
import threading
import time
import traceback
from collections.abc import Callable
from typing import Any


logger = logging.getLogger(__name__)


class _Task:
    """内部任务定义."""

    __slots__ = ("name", "fn", "interval", "run_at", "cancelled", "last_run")

    def __init__(
        self,
        name: str,
        fn: Callable[[], Any],
        interval: float | None,
        run_at: float | None,
    ) -> None:
        self.name = name
        self.fn = fn
        self.interval = interval
        self.run_at = run_at
        self.cancelled = False
        self.last_run: float = 0.0


class BackgroundScheduler:
    """单线程后台调度器(够用且零依赖).

    Usage:
        sched = BackgroundScheduler()
        sched.start()
        sched.every(60.0, lambda: check_hit_rate(), name="hit_rate_check")
        sched.delay(5.0, lambda: warmup())
        ...
        sched.stop()
    """

    def __init__(self) -> None:
        self._tasks: dict[str, _Task] = {}
        self._cron_tasks: dict[str, Any] = {}  # name -> CronSchedule
        self._cron_fns: dict[str, Callable[[], Any]] = {}
        self._lock = threading.Lock()
        self._wake = threading.Event()
        self._thread: threading.Thread | None = None
        self._stopped = False

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stopped = False
        self._thread = threading.Thread(target=self._loop, name="bg-scheduler", daemon=True)
        self._thread.start()
        logger.info("BackgroundScheduler 已启动")

    def stop(self) -> None:
        self._stopped = True
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout=2.0)
            self._thread = None
        logger.info("BackgroundScheduler 已停止")

    def every(self, interval: float, fn: Callable[[], Any], name: str) -> None:
        """注册周期任务."""
        if interval <= 0:
            raise ValueError("interval 必须 > 0")
        with self._lock:
            self._tasks[name] = _Task(name=name, fn=fn, interval=interval, run_at=None)
        self._wake.set()

    def delay(self, seconds: float, fn: Callable[[], Any], name: str) -> None:
        """注册一次性延迟任务."""
        if seconds < 0:
            raise ValueError("seconds 必须 >= 0")
        with self._lock:
            self._tasks[name] = _Task(
                name=name,
                fn=fn,
                interval=None,
                run_at=time.monotonic() + seconds,
            )
        self._wake.set()

    def cancel(self, name: str) -> bool:
        """取消 every/delay 任务. cron 任务请用 cancel_cron()."""
        with self._lock:
            t = self._tasks.pop(name, None)
            cron_removed = self._cron_tasks.pop(name, None) is not None
            self._cron_fns.pop(name, None)
        return t is not None or cron_removed

    # ── cron 支持 ───────────────────────────────────────────
    def cron(self, expr: str, fn: Callable[[], Any], name: str) -> None:
        """注册 cron 表达式任务(5 字段:`分 时 日 月 周`).

        Examples:
            sched.cron("*/5 * * * *", task, name="every-5-min")   # 每 5 分钟
            sched.cron("0 9 * * 1-5", task, name="weekday-9am")   # 工作日 9 点
        """
        from nocobase_py.services.cron import parse_cron

        schedule = parse_cron(expr)
        with self._lock:
            self._cron_tasks[name] = schedule
            self._cron_fns[name] = fn
        logger.info("已注册 cron 任务: name=%s expr=%s", name, expr)

    def cancel_cron(self, name: str) -> bool:
        with self._lock:
            t = self._cron_tasks.pop(name, None)
            self._cron_fns.pop(name, None)
        return t is not None

    def task_names(self) -> list[str]:
        with self._lock:
            return list(self._tasks.keys()) + list(self._cron_tasks.keys())

    def _loop(self) -> None:
        from datetime import datetime, timezone

        # 上次检查 cron 的"分钟桶"(避免同分钟内重复触发)
        last_cron_minute: tuple[int, int, int, int, int] | None = None
        while not self._stopped:
            now = time.monotonic()
            next_wake = self._tick(now)
            # cron 检查(每分钟最多一次)
            now_wall = datetime.now(timezone.utc)
            bucket = (now_wall.year, now_wall.month, now_wall.day, now_wall.hour, now_wall.minute)
            if bucket != last_cron_minute:
                last_cron_minute = bucket
                self._tick_cron(now_wall)
            wait = max(0.05, next_wake - now)
            self._wake.wait(timeout=wait)
            self._wake.clear()

    def _tick_cron(self, dt_now: Any) -> None:
        """执行当前分钟应当触发的 cron 任务."""
        with self._lock:
            cron_due: list[tuple[str, Callable[[], Any]]] = []
            for name, schedule in self._cron_tasks.items():
                if schedule.matches(dt_now):
                    fn = self._cron_fns.get(name)
                    if fn is not None:
                        cron_due.append((name, fn))
        for name, fn in cron_due:
            try:
                fn()
            except Exception:  # noqa: BLE001
                logger.warning("cron 任务 '%s' 失败:\n%s", name, traceback.format_exc())

    def _tick(self, now: float) -> float:
        """执行到期任务,返回下一次唤醒时间."""
        with self._lock:
            due: list[_Task] = []
            to_remove: list[str] = []
            for name, t in self._tasks.items():
                if t.cancelled:
                    to_remove.append(name)
                    continue
                if t.interval is not None:
                    if t.last_run == 0.0 or now - t.last_run >= t.interval:
                        due.append(t)
                elif t.run_at is not None and now >= t.run_at:
                    due.append(t)
                    to_remove.append(name)
            for n in to_remove:
                self._tasks.pop(n, None)

        for t in due:
            try:
                t.fn()
            except Exception:  # noqa: BLE001
                logger.warning("调度任务 '%s' 失败:\n%s", t.name, traceback.format_exc())
            if t.interval is not None:
                t.last_run = time.monotonic()

        return time.monotonic() + 0.5


# ── 全局单例 + 预置任务 ────────────────────────────────────────────
_scheduler: BackgroundScheduler | None = None


def get_scheduler() -> BackgroundScheduler:
    """获取全局调度器(按需启动)."""
    global _scheduler
    if _scheduler is None:
        _scheduler = BackgroundScheduler()
        _scheduler.start()
    return _scheduler


def install_default_alert_checks(
    cache_getter: Callable[[], Any],
    interval: float = 60.0,
) -> None:
    """安装默认告警巡检任务.

    Args:
        cache_getter: 返回 LLMCache 实例的可调用对象(避免循环依赖)
        interval: 巡检间隔(秒)
    """
    sched = get_scheduler()
    name = "alert.cache_hit_rate_check"

    def _check() -> None:
        cache = cache_getter()
        if cache is None:
            return
        import asyncio
        try:
            hit_low = asyncio.run(cache.should_warn_low_hit_rate())
        except Exception:
            hit_low = False
        if hit_low:
            from nocobase_py.services.alerts import get_collector

            try:
                stats = asyncio.run(cache.stats())
            except Exception:
                stats = {"hit_rate": 0, "hits": 0, "misses": 0}
            get_collector().emit(
                kind="cache_hit_rate_low",
                user_id=None,
                detail={
                    "hit_rate": stats.get("hit_rate", 0),
                    "hits": stats.get("hits", 0),
                    "misses": stats.get("misses", 0),
                    "threshold": 0.4,
                },
            )

    sched.every(interval, _check, name=name)
    logger.info("已安装默认告警巡检: %s interval=%.1fs", name, interval)
