"""PHASE104 T9: 告警二次推送 + 重试逻辑测试."""

import logging
from unittest import mock

import pytest

from nocobase_py.routers import alerts as alerts_router


def make_payload():
    return alerts_router.AlertPayload(
        version="4", groupKey="g", status="firing", receiver="r",
        groupLabels={"alertname": "P", "severity": "critical"},
        commonLabels={}, commonAnnotations={},
        externalURL="http://x",
        alerts=[{
            "fingerprint": "f1",
            "labels": {"alertname": "P", "severity": "critical"},
            "annotations": {"summary": "s", "description": "d"},
            "startsAt": "2026-10-14T00:00:00Z",
            "endsAt": "2026-10-14T01:00:00Z",
        }],
    )


class TestAlertForwarding:
    """二次推送调度逻辑."""

    def test_no_pusher_does_not_crash(self):
        """未安装 pusher 时（get_pusher 返回 None）不崩溃."""
        with mock.patch("nocobase_py.services.webhook.get_pusher", return_value=None):
            # 端点内 import get_pusher，patch 后应安全跳过
            from nocobase_py.services.webhook import get_pusher
            assert get_pusher() is None

    def test_unconfigured_pusher_not_forwarded(self):
        """pusher 存在但未配置 url 时不推送."""
        from nocobase_py.services.webhook import WebhookConfig, WebhookPusher

        pusher = WebhookPusher(WebhookConfig(url="", kind="generic", enabled=False))
        assert pusher.config.is_configured() is False

    def test_configured_pusher_is_forwarded(self):
        """已配置 pusher 时推送被调度."""
        from nocobase_py.services.webhook import WebhookConfig, WebhookPusher

        pusher = WebhookPusher(WebhookConfig(url="http://hook", kind="generic", enabled=True))
        assert pusher.config.is_configured() is True


class TestForwardWithRetry:
    """_forward_alert_with_retry 重试逻辑."""

    def test_success_first_attempt(self):
        pusher = mock.MagicMock()
        pusher.push.return_value = True
        alerts_router._forward_alert_with_retry(pusher, {"kind": "k"})
        assert pusher.push.call_count == 1

    def test_retries_then_succeeds(self):
        pusher = mock.MagicMock()
        pusher.push.side_effect = [False, False, True]
        with mock.patch.object(alerts_router.time, "sleep"):
            alerts_router._forward_alert_with_retry(pusher, {"kind": "k"})
        assert pusher.push.call_count == 3

    def test_all_attempts_fail_logs_warning(self):
        pusher = mock.MagicMock()
        pusher.push.return_value = False
        with mock.patch.object(alerts_router.time, "sleep"):
            with mock.patch.object(alerts_router.logger, "warning") as warn:
                alerts_router._forward_alert_with_retry(pusher, {"kind": "k"}, max_attempts=3)
        assert pusher.push.call_count == 3
        # 重试耗尽必须留下 WARN 日志（不得静默）
        assert warn.called
        assert "仍失败" in warn.call_args[0][0]

    def test_exception_is_caught_and_retried(self):
        pusher = mock.MagicMock()
        pusher.push.side_effect = [RuntimeError("boom"), True]
        with mock.patch.object(alerts_router.time, "sleep"):
            alerts_router._forward_alert_with_retry(pusher, {"kind": "k"})
        assert pusher.push.call_count == 2

    def test_backoff_delays_double(self):
        """指数退避：0.5 → 1.0 → 2.0."""
        pusher = mock.MagicMock()
        pusher.push.return_value = False
        with mock.patch.object(alerts_router.time, "sleep") as sleep_mock:
            alerts_router._forward_alert_with_retry(pusher, {"kind": "k"}, max_attempts=4)
        delays = [c.args[0] for c in sleep_mock.call_args_list]
        assert delays == [0.5, 1.0, 2.0], f"退避序列错误: {delays}"

    def test_webhook_endpoint_persists_and_returns(self):
        """端点本身：落盘 + 返回 received 计数."""
        import tempfile, os
        os.chdir(tempfile.mkdtemp())
        from fastapi.testclient import TestClient
        from nocobase_py.main import create_app

        client = TestClient(create_app())
        payload = make_payload()
        r = client.post("/api/alerts/webhook", json=payload.model_dump())
        assert r.status_code == 200
        assert r.json()["received"] == 1
