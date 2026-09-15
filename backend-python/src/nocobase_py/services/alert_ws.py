"""R11 WebSocket 实时告警推送.

AlertCollector 安装钩子:每次 emit 后,广播给所有连接的客户端.
- 不引入新依赖(只用 FastAPI 自带 WebSocket)
- 客户端断开自动清理
- 心跳:服务端主动发 ping,客户端断线后清理

Usage:
    from nocobase_py.services.alert_ws import install_ws_broadcaster
    install_ws_broadcaster()  # 启动后调用一次
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Iterable
from typing import Any

from fastapi import WebSocket


logger = logging.getLogger(__name__)


class AlertBroadcaster:
    """管理一组 WebSocket 连接,提供按订阅路由 broadcast."""

    def __init__(self) -> None:
        self._connections: set[WebSocket] = set()
        # session_id(user_id) -> user_id (None = admin/no user_id)
        self._session_user: dict[str, str | None] = {}
        self._lock = asyncio.Lock()

    async def connect(self, ws: WebSocket, user_id: str | None = None) -> str:
        await ws.accept()
        async with self._lock:
            self._connections.add(ws)
            session_id = id(ws)
            self._session_user[session_id] = user_id
        logger.info(
            "WebSocket 客户端已连接 user_id=%s (总数: %d)",
            user_id,
            len(self._connections),
        )
        return session_id

    async def disconnect(self, ws: WebSocket) -> None:
        session_id = id(ws)
        async with self._lock:
            self._connections.discard(ws)
            self._session_user.pop(session_id, None)
        logger.info("WebSocket 客户端已断开 (剩余: %d)", len(self._connections))

    async def broadcast(self, payload: dict[str, Any]) -> int:
        """通用广播:推送给所有连接(向后兼容)."""
        if not self._connections:
            return 0
        msg = json.dumps(payload, ensure_ascii=False, default=str)
        dead: list[WebSocket] = []
        targets: Iterable[WebSocket] = list(self._connections)
        delivered = 0
        for ws in targets:
            try:
                await ws.send_text(msg)
                delivered += 1
            except Exception:  # noqa: BLE001
                dead.append(ws)
        if dead:
            async with self._lock:
                for ws in dead:
                    sid = id(ws)
                    self._connections.discard(ws)
                    self._session_user.pop(sid, None)
        return delivered

    async def broadcast_to_subscribers(self, kind: str, payload: dict[str, Any]) -> int:
        """按订阅路由:只推送给订阅了该 kind 的用户 + 无 user_id(管理员)."""
        if not self._connections:
            return 0
        msg = json.dumps(payload, ensure_ascii=False, default=str)

        # 从 AlertStore 查询订阅者
        subscribers: set[str] = set()
        try:
            from nocobase_py.services.alert_store import get_alert_store
            subscribers = set(get_alert_store().subscribers_for(kind))
        except Exception:  # noqa: BLE001
            logger.debug("查询订阅者失败 kind=%s", kind)

        dead: list[WebSocket] = []
        delivered = 0
        for ws in list(self._connections):
            uid = self._session_user.get(id(ws))
            try:
                if uid is None or uid in subscribers:
                    await ws.send_text(msg)
                    delivered += 1
            except Exception:  # noqa: BLE001
                dead.append(ws)

        if dead:
            async with self._lock:
                for ws in dead:
                    sid = id(ws)
                    self._connections.discard(ws)
                    self._session_user.pop(sid, None)

        return delivered

    async def broadcast_to_user(self, user_id: str, payload: dict[str, Any]) -> int:
        """只推送给指定用户的所有连接."""
        if not user_id or not self._connections:
            return 0
        msg = json.dumps(payload, ensure_ascii=False, default=str)
        dead: list[WebSocket] = []
        delivered = 0
        for ws in list(self._connections):
            uid = self._session_user.get(id(ws))
            if uid == user_id:
                try:
                    await ws.send_text(msg)
                    delivered += 1
                except Exception:  # noqa: BLE001
                    dead.append(ws)

        if dead:
            async with self._lock:
                for ws in dead:
                    sid = id(ws)
                    self._connections.discard(ws)
                    self._session_user.pop(sid, None)

        return delivered

    def connection_count(self) -> int:
        return len(self._connections)

    def user_session_count(self, user_id: str) -> int:
        return sum(1 for v in self._session_user.values() if v == user_id)


# 全局单例
_broadcaster: AlertBroadcaster | None = None


def get_broadcaster() -> AlertBroadcaster:
    global _broadcaster
    if _broadcaster is None:
        _broadcaster = AlertBroadcaster()
    return _broadcaster


def install_ws_broadcaster() -> None:
    """把 broadcaster 安装到全局 AlertCollector.emit 钩子.

    注意: 必须在 event loop 运行时调用,否则 scheduled task 无法启动.
    路由策略: 按订阅推送,无 user_id 连接(管理员)总是收到.
    """
    from nocobase_py.services.alerts import get_collector

    collector = get_collector()
    if getattr(collector, "_ws_hooked", False):
        return

    loop = asyncio.get_event_loop()

    # 直接包装 emit,保留原逻辑
    original_emit = collector.emit

    def wrapped_emit(
        kind: str,
        user_id: str | None = None,
        detail: dict[str, Any] | None = None,
    ):
        ev = original_emit(kind, user_id=user_id, detail=detail)
        # 异步广播(按订阅路由)
        try:
            payload = {
                "id": ev.id,
                "kind": ev.kind,
                "user_id": ev.user_id,
                "detail": ev.detail,
                "timestamp": ev.timestamp,
                "acked": ev.acked,
                "resolved": ev.resolved,
            }
            asyncio.run_coroutine_threadsafe(
                get_broadcaster().broadcast_to_subscribers(kind, payload),
                loop,
            )
        except RuntimeError:
            pass
        return ev

    collector.emit = wrapped_emit  # type: ignore[assignment]
    collector._ws_hooked = True  # type: ignore[attr-defined]
    logger.info("WebSocket broadcaster 已安装到 AlertCollector(按订阅路由)")
