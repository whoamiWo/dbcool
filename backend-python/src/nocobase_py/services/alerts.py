"""R11 告警中心 — 三层防护触发的告警事件统一上报.

提供 AlertEvent 数据模型 + AlertCollector 收集器:
- 限流超限 → rate_limit_exceeded
- 配额超限 → quota_exceeded
- 缓存命中率低 → cache_hit_rate_low(运维)

不引入重型告警依赖(Prometheus client / Sentry),先用内存实现,
后续可替换为 webhook / 邮件 / 企业 IM 推送.
"""

from __future__ import annotations

import logging
import time
import uuid
from collections import deque
from collections.abc import Iterable
from dataclasses import dataclass, field
from threading import Lock
from typing import Any


logger = logging.getLogger(__name__)


@dataclass
class AlertEvent:
    """单条告警事件."""

    id: str
    kind: str
    user_id: str | None
    detail: dict[str, Any]
    timestamp: float = field(default_factory=time.time)
    acked: bool = False
    acked_by: str | None = None
    acked_at: float | None = None
    resolved: bool = False
    resolved_at: float | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "kind": self.kind,
            "user_id": self.user_id,
            "timestamp": self.timestamp,
            "detail": self.detail,
            "acked": self.acked,
            "acked_by": self.acked_by,
            "acked_at": self.acked_at,
            "resolved": self.resolved,
            "resolved_at": self.resolved_at,
        }


class AlertCollector:
    """线程安全的告警收集器,支持 ack / resolve.

    Usage:
        collector = AlertCollector(max_events=1000)
        ev = collector.emit("rate_limit_exceeded", user_id="u1", detail={"limit": 30})
        collector.ack(ev.id, by="admin")
        ...
        collector.resolve(ev.id)
    """

    def __init__(self, max_events: int = 1000) -> None:
        self.max_events = max_events
        self._events: deque[AlertEvent] = deque(maxlen=max_events)
        self._lock = Lock()
        self._store: Any = None  # 可选 AlertStore

    def attach_store(self, store: Any) -> None:
        """附加 SQLite 后端(可选).事件 ack/resolve 都同步落盘,启动时从 store 恢复."""
        self._store = store
        store.open()
        try:
            for ev_dict in store.iter_alerts(limit=self.max_events):
                self._events.append(
                    AlertEvent(
                        id=ev_dict["id"],
                        kind=ev_dict["kind"],
                        user_id=ev_dict.get("user_id"),
                        detail=ev_dict.get("detail") or {},
                        timestamp=ev_dict["timestamp"],
                        acked=ev_dict.get("acked", False),
                        acked_by=ev_dict.get("acked_by"),
                        acked_at=ev_dict.get("acked_at"),
                        resolved=ev_dict.get("resolved", False),
                        resolved_at=ev_dict.get("resolved_at"),
                    )
                )
            logger.info("AlertCollector 从 store 恢复了 %d 条事件", len(self._events))
        except Exception:  # noqa: BLE001
            logger.warning("从 store 恢复事件失败", exc_info=True)

    def emit(
        self,
        kind: str,
        user_id: str | None = None,
        detail: dict[str, Any] | None = None,
    ) -> AlertEvent:
        event = AlertEvent(
            id=uuid.uuid4().hex[:12],
            kind=kind,
            user_id=user_id,
            detail=detail or {},
        )
        with self._lock:
            self._events.append(event)
        if self._store is not None:
            try:
                self._store.insert_alert(event.to_dict())
            except Exception:  # noqa: BLE001
                logger.warning("告警事件落库失败 id=%s", event.id, exc_info=True)
        logger.warning("[alert] %s id=%s user=%s detail=%s", kind, event.id, user_id, detail)
        return event

    def get(self, event_id: str) -> AlertEvent | None:
        with self._lock:
            for ev in self._events:
                if ev.id == event_id:
                    return ev
        return None

    def ack(self, event_id: str, by: str | None = None) -> bool:
        """标记告警已确认.返回是否存在该 id."""
        acked_at = time.time()
        with self._lock:
            for ev in self._events:
                if ev.id == event_id:
                    ev.acked = True
                    ev.acked_by = by
                    ev.acked_at = acked_at
                    logger.info("[alert] ack id=%s by=%s", event_id, by)
                    break
            else:
                return False
        if self._store is not None:
            try:
                self._store.update_alert(event_id, acked=True, acked_by=by, acked_at=acked_at)
            except Exception:  # noqa: BLE001
                logger.warning("告警 ack 落库失败 id=%s", event_id, exc_info=True)
        return True

    def resolve(self, event_id: str) -> bool:
        """标记告警已解决.返回是否存在该 id."""
        resolved_at = time.time()
        with self._lock:
            for ev in self._events:
                if ev.id == event_id:
                    ev.resolved = True
                    ev.resolved_at = resolved_at
                    logger.info("[alert] resolve id=%s", event_id)
                    break
            else:
                return False
        if self._store is not None:
            try:
                self._store.update_alert(event_id, resolved=True, resolved_at=resolved_at)
            except Exception:  # noqa: BLE001
                logger.warning("告警 resolve 落库失败 id=%s", event_id, exc_info=True)
        return True

    def recent(
        self,
        limit: int = 100,
        *,
        include_resolved: bool = True,
    ) -> list[dict[str, Any]]:
        with self._lock:
            snapshot = list(self._events)
        if not include_resolved:
            snapshot = [ev for ev in snapshot if not ev.resolved]
        return [ev.to_dict() for ev in snapshot[-limit:]]

    def count_by_kind(self, *, include_resolved: bool = True) -> dict[str, int]:
        out: dict[str, int] = {}
        with self._lock:
            for ev in self._events:
                if not include_resolved and ev.resolved:
                    continue
                out[ev.kind] = out.get(ev.kind, 0) + 1
        return out

    def unresolved_count(self) -> int:
        with self._lock:
            return sum(1 for ev in self._events if not ev.resolved)

    def clear(self) -> None:
        with self._lock:
            self._events.clear()


# 全局单例
_global_collector = AlertCollector()


def get_collector() -> AlertCollector:
    """获取全局 AlertCollector(简单单例,够用)."""
    return _global_collector
