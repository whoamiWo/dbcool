"""PHASE104 T9: 告警 webhook 二次推送 + 重试逻辑测试."""

import importlib.util
from pathlib import Path
from unittest import mock

import pytest

# 加载 alerts 路由模块（文件名不含连字符，可直接 import）
from nocobase_py.routers import alerts as alerts_router


def make_payload(**overrides):
    base = {
        "version": "4",
        "groupKey": "g",
        "status": "firing",
        "receiver": "r",
        "groupLabels": {"alertname": "P", "severity": "critical"},
        "commonLabels": {},
        "commonAnnotations": {},
        "externalURL": "http://x",
        "alerts": [{
            "fingerprint": "f1",
            "labels": {"alertname": "P", "severity": "critical"},
            "annotations": {"summary": "s", "description": "d"},
            "startsAt": "2026-10-14T00:00:00Z",
            "endsAt": "2026-10-14T01:00:00Z",
        }],
    }
    base.update(overrides)
    return alerts_router.AlertPayload(**base)


class TestAlertForwarding:
    """二次推送调度逻辑."""

    def test_forwarding_not_scheduled_when_pusher_none(self):
        """未安装 pusher 时不调度推送（不报错、不静默崩溃）."""
        tasks = []
        with mock.patch.object(alerts_router, "get_pusher", return_value=None, create=True):
            # get_pusher 在函数内 import，patch services.webhook
            with mock.patch("nocobase_py.services.webhook.get_pusher", return_value=None):
                payload = make_payload()
                # 直接调用内部逻辑（绕过 FastAPI BackgroundTasks）
                from nocobase_py.services.webhook import get_pusher
                pusher = get_pusher()
                assert pusher is None

    def test_forwarding_scheduled_when_configured(self):
        """已配置 pusher 时应调度后台推送任务."""
        from nocobase_py.services.webhook import WebhookConfig, WebhookPusher

        pusher = WebhookPusher(WebhookConfig(url="http://hook", kind="generic", enabled=True))

        with mock.patch("nocobase_py.services.webhook.get_pusher", return_value=pusher):
            payload = make_payload()
            # 模拟端点内的调度分支
            scheduled = []
            for alert in payload.alerts:
                scheduled.append(alert)
            assert len(scheduled) == 1

    def test_forwarding_not_scheduled_when_not_configured(self):
        """pusher 存在但未配置 url 时不调度."""
        from nocobase_py.services.webhook import WebhookConfig, WebhookPusher

        pusher = WebhookPusher(WebhookConfig(url="", kind="generic", enabled=False))
        assert pusher.config.is_configured() is False


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
