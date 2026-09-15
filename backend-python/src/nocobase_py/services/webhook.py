"""R11 告警 webhook 推送 — 把 AlertCollector 事件推送到外部系统.

支持飞书/Slack/通用 webhook 三种格式:
- feishu:   {"msg_type": "text", "content": {"text": ...}}
- slack:    {"text": ...}
- generic:  {"kind": ..., "user_id": ..., "detail": {...}, "timestamp": ...}

设计要点:
- httpx 可选,默认用 urllib(零依赖同步 POST)
- 推送失败不阻塞主流程(异常 catch 掉,只 logger.warning)
- 可配置 timeout + 重试次数
- 单元测试可注入 fake transport
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import threading
import time
from dataclasses import dataclass
from typing import Any, Protocol


logger = logging.getLogger(__name__)

# 签名 header 名(兼容 Slack/通用 webhook 命名)
SIGNATURE_HEADER = "X-Webhook-Signature"
TIMESTAMP_HEADER = "X-Webhook-Timestamp"


def compute_signature(secret: str, body: bytes, timestamp: str | None = None) -> str:
    """计算 HMAC-SHA256 签名.

    格式:`sha256=<hex>`.可选 timestamp 防重放(拼到 body 前).
    """
    if timestamp:
        msg = timestamp.encode("utf-8") + b"." + body
    else:
        msg = body
    digest = hmac.new(secret.encode("utf-8"), msg, hashlib.sha256).hexdigest()
    return f"sha256={digest}"


def verify_signature(
    secret: str, body: bytes, signature: str, timestamp: str | None = None
) -> bool:
    """验证签名是否匹配(用于接收端校验)."""
    expected = compute_signature(secret, body, timestamp)
    return hmac.compare_digest(expected, signature)


class _PostFn(Protocol):
    """可注入的 HTTP POST 函数(便于测试)."""

    def __call__(
        self,
        url: str,
        payload: dict[str, Any],
        timeout: float,
        headers: dict[str, str],
    ) -> bool: ...


def _default_post(
    url: str,
    payload: dict[str, Any],
    timeout: float,
    headers: dict[str, str] | None = None,
) -> bool:
    """使用 urllib 进行 POST,失败返回 False."""
    import urllib.error
    import urllib.request

    data = json.dumps(payload).encode("utf-8")
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=data, headers=req_headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return 200 <= resp.status < 300
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        logger.warning("webhook 推送失败 url=%s err=%s", url, e)
        return False


@dataclass
class WebhookConfig:
    """Webhook 配置."""

    url: str = ""
    kind: str = "generic"  # generic / feishu / slack
    enabled: bool = False
    timeout: float = 2.0
    max_retries: int = 1
    secret: str = ""  # 非空时启用 HMAC 签名
    include_timestamp: bool = True  # 签名中是否包含 timestamp
    batch: bool = False  # 是否启用批量模式
    batch_size: int = 10  # 批量触发的最大条数
    batch_window_seconds: float = 5.0  # 批量窗口(秒),到达窗口或 size 才 flush

    def is_configured(self) -> bool:
        return self.enabled and bool(self.url)

    def signing_enabled(self) -> bool:
        return bool(self.secret)


class WebhookPusher:
    """告警 webhook 推送器.

    Usage:
        pusher = WebhookPusher(WebhookConfig(url="...", kind="feishu", enabled=True))
        pusher.push({"kind": "rate_limit_exceeded", "user_id": "u1", ...})
    """

    def __init__(self, config: WebhookConfig, post_fn: _PostFn | None = None) -> None:
        self.config = config
        self._post = post_fn or _default_post
        # 统计
        self.success_count: int = 0
        self.failure_count: int = 0

    def _format_payload(self, event: dict[str, Any]) -> dict[str, Any]:
        if self.config.kind == "feishu":
            text = (
                f"[alert] {event.get('kind')} "
                f"user={event.get('user_id')} "
                f"detail={event.get('detail')}"
            )
            return {"msg_type": "text", "content": {"text": text}}
        if self.config.kind == "slack":
            return {
                "text": f"[{event.get('kind')}] user={event.get('user_id')} detail={event.get('detail')}"
            }
        # generic
        return event

    def push(self, event: dict[str, Any]) -> bool:
        """同步推送单条告警."""
        if not self.config.is_configured():
            return False
        payload = self._format_payload(event)
        body = json.dumps(payload).encode("utf-8")
        headers = self._build_headers(body)
        last_ok = False
        for attempt in range(max(1, self.config.max_retries)):
            last_ok = self._post(self.config.url, payload, self.config.timeout, headers)
            if last_ok:
                self.success_count += 1
                logger.info(
                    "webhook 推送成功 kind=%s signed=%s",
                    event.get("kind"),
                    self.config.signing_enabled(),
                )
                return True
            time.sleep(0.05 * (attempt + 1))
        self.failure_count += 1
        return False

    def _build_headers(self, body: bytes) -> dict[str, str]:
        """构造签名 header(若启用)."""
        headers: dict[str, str] = {}
        if not self.config.signing_enabled():
            return headers
        ts = str(int(time.time())) if self.config.include_timestamp else None
        sig = compute_signature(self.config.secret, body, ts)
        headers[SIGNATURE_HEADER] = sig
        if ts:
            headers[TIMESTAMP_HEADER] = ts
        return headers

    def stats(self) -> dict[str, int]:
        return {
            "success": self.success_count,
            "failure": self.failure_count,
            "enabled": int(self.config.enabled),
            "configured": int(self.config.is_configured()),
            "signing": int(self.config.signing_enabled()),
        }


# ── AlertCollector 钩子集成 ────────────────────────────────────────
_pusher: WebhookPusher | None = None


def install_pusher(pusher: WebhookPusher) -> None:
    """将 pusher 安装为 AlertCollector 的钩子,告警 emit 后自动推送."""
    global _pusher
    _pusher = pusher
    from nocobase_py.services.alerts import get_collector

    original_emit = get_collector().emit

    def wrapped_emit(
        kind: str, user_id: str | None = None, detail: dict[str, Any] | None = None
    ):
        ev = original_emit(kind, user_id=user_id, detail=detail)
        if _pusher is not None:
            try:
                _pusher.push(ev.to_dict())
            except Exception:  # noqa: BLE001
                logger.warning("webhook 推送异常,不影响告警采集", exc_info=True)
        return ev

    get_collector().emit = wrapped_emit  # type: ignore[assignment]


def uninstall_pusher() -> None:
    """卸载 pusher(测试用)."""
    global _pusher
    _pusher = None


# ── 批量推送器 ──────────────────────────────────────────────────────
class BatchWebhookPusher:
    """批量聚合推送器 — 多个告警事件合为单次 HTTP 调用,减少 IM 噪音.

    Usage:
        pusher = BatchWebhookPusher(config)
        pusher.push(event_dict)  # 异步累积
        pusher.flush()           # 手动 flush
        # ... 或依赖自动窗口触发
    """

    def __init__(self, config: WebhookConfig, post_fn: _PostFn | None = None) -> None:
        self.config = config
        self._post = post_fn or _default_post
        self._buffer: list[dict[str, Any]] = []
        self._last_flush = time.monotonic()
        self._lock = threading.Lock()
        self.success_count: int = 0
        self.failure_count: int = 0
        self.flush_count: int = 0

    def push(self, event: dict[str, Any]) -> bool:
        """把事件加入缓冲区;若到达阈值则立即 flush."""
        if not self.config.is_configured():
            return False
        should_flush = False
        with self._lock:
            self._buffer.append(event)
            if len(self._buffer) >= self.config.batch_size:
                should_flush = True
            elif (
                time.monotonic() - self._last_flush
            ) >= self.config.batch_window_seconds:
                should_flush = True
        if should_flush:
            return self.flush()
        return True

    def flush(self) -> bool:
        """强制 flush 当前缓冲."""
        with self._lock:
            if not self._buffer:
                return True
            payload_batch = list(self._buffer)
            self._buffer.clear()
            self._last_flush = time.monotonic()
        return self._send_batch(payload_batch)

    def _send_batch(self, events: list[dict[str, Any]]) -> bool:
        body = json.dumps(
            {"batch_size": len(events), "events": events, "flushed_at": time.time()}
        ).encode("utf-8")
        headers: dict[str, str] = {}
        if self.config.signing_enabled():
            ts = str(int(time.time())) if self.config.include_timestamp else None
            headers[SIGNATURE_HEADER] = compute_signature(self.config.secret, body, ts)
            if ts:
                headers[TIMESTAMP_HEADER] = ts
        ok = self._post(self.config.url, {"batch_size": len(events), "events": events}, self.config.timeout, headers)
        self.flush_count += 1
        if ok:
            self.success_count += 1
            logger.info("batch webhook 推送成功 size=%d", len(events))
        else:
            self.failure_count += 1
            logger.warning("batch webhook 推送失败 size=%d", len(events))
        return ok

    def stats(self) -> dict[str, int]:
        with self._lock:
            buffered = len(self._buffer)
        return {
            "success": self.success_count,
            "failure": self.failure_count,
            "flushes": self.flush_count,
            "buffered": buffered,
            "enabled": int(self.config.enabled),
            "configured": int(self.config.is_configured()),
            "signing": int(self.config.signing_enabled()),
            "batch_mode": 1,
        }
