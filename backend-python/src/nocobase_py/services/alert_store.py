"""R11 告警 SQLite 持久化 + 订阅.

为 AlertCollector 提供可选的持久化后端:
- 事件落盘 SQLite,重启可恢复
- 用户订阅(按 kind),只推送订阅的告警
- 订阅也持久化
- 不引入 SQLAlchemy 等重依赖,用 stdlib sqlite3

设计:
- 单表 `alerts` + 单表 `alert_subscriptions`
- 写盘为同步(简单可靠;数据量小写库快)
- 历史容量默认 10000,超过按时间淘汰最旧的 resolved
"""

from __future__ import annotations

import json
import logging
import sqlite3
import threading
import time
from collections.abc import Iterable
from pathlib import Path
from typing import Any


logger = logging.getLogger(__name__)


_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS alerts (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    user_id TEXT,
    detail TEXT NOT NULL DEFAULT '{}',
    ts REAL NOT NULL,
    acked INTEGER NOT NULL DEFAULT 0,
    acked_by TEXT,
    acked_at REAL,
    resolved INTEGER NOT NULL DEFAULT 0,
    resolved_at REAL
);
CREATE INDEX IF NOT EXISTS idx_alerts_kind ON alerts(kind);
CREATE INDEX IF NOT EXISTS idx_alerts_ts ON alerts(ts);

CREATE TABLE IF NOT EXISTS alert_subscriptions (
    user_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    created_at REAL NOT NULL,
    PRIMARY KEY (user_id, kind)
);
"""


class AlertStore:
    """告警事件 + 订阅的 SQLite 持久化存储.

    Usage:
        store = AlertStore(path="alerts.db")
        store.open()
        store.insert_alert({...})
        for ev in store.iter_alerts(limit=50):
            ...
        store.subscribe(user_id="u1", kind="rate_limit_exceeded")
    """

    def __init__(
        self,
        path: str | Path = "alerts.db",
        max_alerts: int = 10_000,
    ) -> None:
        self.path = Path(path)
        self.max_alerts = max_alerts
        self._lock = threading.Lock()
        self._conn: sqlite3.Connection | None = None

    def open(self) -> None:
        with self._lock:
            if self._conn is not None:
                return
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self._conn = sqlite3.connect(str(self.path), check_same_thread=False)
            self._conn.row_factory = sqlite3.Row
            self._conn.executescript(_SCHEMA_SQL)
            self._conn.commit()
            logger.info("AlertStore opened: %s", self.path)

    def close(self) -> None:
        with self._lock:
            if self._conn is not None:
                self._conn.close()
                self._conn = None

    def _ensure_open(self) -> sqlite3.Connection:
        if self._conn is None:
            self.open()
        assert self._conn is not None
        return self._conn

    # ── 事件 CRUD ─────────────────────────────────────────────
    def insert_alert(self, ev: dict[str, Any]) -> None:
        conn = self._ensure_open()
        conn.execute(
            "INSERT OR REPLACE INTO alerts (id, kind, user_id, detail, ts, acked, acked_by, acked_at, resolved, resolved_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                ev["id"],
                ev["kind"],
                ev.get("user_id"),
                json.dumps(ev.get("detail") or {}),
                ev["timestamp"],
                int(bool(ev.get("acked"))),
                ev.get("acked_by"),
                ev.get("acked_at"),
                int(bool(ev.get("resolved"))),
                ev.get("resolved_at"),
            ),
        )
        conn.commit()
        self._trim_if_needed()

    def update_alert(
        self,
        event_id: str,
        *,
        acked: bool | None = None,
        acked_by: str | None = None,
        acked_at: float | None = None,
        resolved: bool | None = None,
        resolved_at: float | None = None,
    ) -> bool:
        conn = self._ensure_open()
        sets: list[str] = []
        vals: list[Any] = []
        if acked is not None:
            sets.append("acked = ?")
            vals.append(int(acked))
        if acked_by is not None:
            sets.append("acked_by = ?")
            vals.append(acked_by)
        if acked_at is not None:
            sets.append("acked_at = ?")
            vals.append(acked_at)
        if resolved is not None:
            sets.append("resolved = ?")
            vals.append(int(resolved))
        if resolved_at is not None:
            sets.append("resolved_at = ?")
            vals.append(resolved_at)
        if not sets:
            return False
        vals.append(event_id)
        cur = conn.execute(
            f"UPDATE alerts SET {', '.join(sets)} WHERE id = ?", vals
        )
        conn.commit()
        return cur.rowcount > 0

    def iter_alerts(
        self,
        limit: int = 100,
        *,
        include_resolved: bool = True,
        kinds: Iterable[str] | None = None,
    ) -> list[dict[str, Any]]:
        conn = self._ensure_open()
        sql = "SELECT * FROM alerts"
        where: list[str] = []
        params: list[Any] = []
        if not include_resolved:
            where.append("resolved = 0")
        if kinds:
            placeholders = ",".join("?" for _ in kinds)
            where.append(f"kind IN ({placeholders})")
            params.extend(list(kinds))
        if where:
            sql += " WHERE " + " AND ".join(where)
        sql += " ORDER BY ts DESC LIMIT ?"
        params.append(limit)
        rows = conn.execute(sql, params).fetchall()
        return [_row_to_dict(r) for r in rows]

    def count_alerts(self, *, include_resolved: bool = True) -> dict[str, int]:
        conn = self._ensure_open()
        sql = "SELECT kind, COUNT(*) AS n FROM alerts"
        if not include_resolved:
            sql += " WHERE resolved = 0"
        sql += " GROUP BY kind"
        return {r["kind"]: r["n"] for r in conn.execute(sql).fetchall()}

    def unresolved_count(self) -> int:
        conn = self._ensure_open()
        return conn.execute("SELECT COUNT(*) AS n FROM alerts WHERE resolved = 0").fetchone()["n"]

    def _trim_if_needed(self) -> None:
        """超过 max_alerts 时,删除最旧的 resolved 行."""
        conn = self._ensure_open()
        cur = conn.execute("SELECT COUNT(*) AS n FROM alerts")
        n = cur.fetchone()["n"]
        if n <= self.max_alerts:
            return
        excess = n - self.max_alerts
        conn.execute(
            "DELETE FROM alerts WHERE id IN ("
            "  SELECT id FROM alerts WHERE resolved = 1 ORDER BY ts ASC LIMIT ?"
            ")",
            (excess,),
        )
        conn.commit()

    # ── 订阅 CRUD ────────────────────────────────────────────
    def subscribe(self, user_id: str, kind: str) -> bool:
        """返回是否新增(True)或已存在(False)."""
        conn = self._ensure_open()
        cur = conn.execute(
            "INSERT OR IGNORE INTO alert_subscriptions (user_id, kind, created_at) VALUES (?, ?, ?)",
            (user_id, kind, time.time()),
        )
        conn.commit()
        return cur.rowcount > 0

    def unsubscribe(self, user_id: str, kind: str) -> bool:
        conn = self._ensure_open()
        cur = conn.execute(
            "DELETE FROM alert_subscriptions WHERE user_id = ? AND kind = ?",
            (user_id, kind),
        )
        conn.commit()
        return cur.rowcount > 0

    def subscriptions_for(self, user_id: str) -> list[str]:
        conn = self._ensure_open()
        rows = conn.execute(
            "SELECT kind FROM alert_subscriptions WHERE user_id = ? ORDER BY created_at",
            (user_id,),
        ).fetchall()
        return [r["kind"] for r in rows]

    def subscribers_for(self, kind: str) -> list[str]:
        """获取订阅了某 kind 的所有用户(用于推送过滤)."""
        conn = self._ensure_open()
        rows = conn.execute(
            "SELECT user_id FROM alert_subscriptions WHERE kind = ?",
            (kind,),
        ).fetchall()
        return [r["user_id"] for r in rows]


def _row_to_dict(r: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": r["id"],
        "kind": r["kind"],
        "user_id": r["user_id"],
        "detail": json.loads(r["detail"] or "{}"),
        "timestamp": r["ts"],
        "acked": bool(r["acked"]),
        "acked_by": r["acked_by"],
        "acked_at": r["acked_at"],
        "resolved": bool(r["resolved"]),
        "resolved_at": r["resolved_at"],
    }


# ── 全局单例 ──────────────────────────────────────────────────────
_store: AlertStore | None = None


def get_store(
    path: str | Path | None = None,
    max_alerts: int | None = None,
    auto_attach: bool = True,
) -> AlertStore:
    """获取/初始化全局 AlertStore.

    第一次调用时可传 path/max_alerts;后续调用忽略.
    auto_attach=True 时自动附加到 AlertCollector(单例,只生效一次).
    """
    global _store
    if _store is None:
        if path is None:
            path = "alerts.db"
        if max_alerts is None:
            max_alerts = 10_000
        _store = AlertStore(path=path, max_alerts=max_alerts)
        _store.open()
        if auto_attach:
            try:
                from nocobase_py.services.alerts import get_collector

                get_collector().attach_store(_store)
            except Exception:  # noqa: BLE001
                logger.warning("自动 attach 到 AlertCollector 失败", exc_info=True)
        logger.info("全局 AlertStore 初始化完成")
    return _store


def reset_store_for_tests() -> None:
    """测试辅助:重置全局单例."""
    global _store
    if _store is not None:
        try:
            _store.close()
        except Exception:  # noqa: BLE001
            pass
    _store = None
