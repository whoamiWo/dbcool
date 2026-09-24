"""P0-2a 单测 — 飞书/Mattermost 入站 verify 签名校验。

不依赖 Redis/网络,纯本地 HMAC 计算验证。
"""
import hashlib
import hmac
import json

import pytest

from nocobase_py.services.connectors.base import ConnectorConfig
from nocobase_py.services.connectors.feishu import FeishuConnector
from nocobase_py.services.connectors.mattermost import MattermostConnector


def _feishu_connector() -> FeishuConnector:
    cfg = ConnectorConfig(
        name="feishu-test", timeout_seconds=1, app_id="cli_test", app_secret="secret123"
    )
    # ConnectorConfig 用 kwargs;此处手填 __dict__ 兼容 dataclass
    return FeishuConnector.__new__(FeishuConnector)._build_for_test(app_id="cli_test", app_secret="secret123") if hasattr(FeishuConnector, "_build_for_test") else _feishu_via_init(cfg)


def _feishu_via_init(cfg) -> FeishuConnector:
    c = FeishuConnector.__new__(FeishuConnector)
    # 跳过 BaseConnector.__init__ 中的网络调用,手填必要字段
    object.__setattr__(c, "config", cfg)
    object.__setattr__(c, "app_id", "cli_test")
    object.__setattr__(c, "app_secret", "secret123")
    object.__setattr__(c, "base_url", "https://open.feishu.cn/open-apis")
    object.__setattr__(c, "_mem_token_cache", {})
    return c


def _mattermost_connector(token: str = "") -> MattermostConnector:
    c = MattermostConnector.__new__(MattermostConnector)
    cfg = ConnectorConfig(
        name="mm-test", timeout_seconds=1, webhook_url="", bot_token="",
        server_url="", webhook_token=token,
    )
    object.__setattr__(c, "config", cfg)
    object.__setattr__(c, "webhook_url", "")
    object.__setattr__(c, "bot_token", "")
    object.__setattr__(c, "server_url", "")
    object.__setattr__(c, "base_url", "")
    return c


# ================== 飞书签名测试 ==================


def test_feishu_signature_valid():
    """正确签名应通过校验。"""
    c = _feishu_via_init(ConnectorConfig(name="f", timeout_seconds=1))
    body = b'{"event":{"type":"message"}}'
    timestamp = "1700000000"
    nonce = "abc123"
    expected = hmac.new(
        c.app_secret.encode(),
        (timestamp + nonce + body.decode()).encode(),
        hashlib.sha256,
    ).hexdigest()
    assert c.verify_signature_hex(expected, timestamp, nonce, body) is True


def test_feishu_signature_invalid():
    """错误签名应被拒。"""
    c = _feishu_via_init(ConnectorConfig(name="f", timeout_seconds=1))
    assert c.verify_signature_hex("deadbeef" * 8, "1700000000", "abc", b"{}") is False
    # 时间戳不匹配
    body = b"{}"
    sig = hmac.new(c.app_secret.encode(), "wrong_ts" + "abc" + "{}", hashlib.sha256).hexdigest()
    assert c.verify_signature_hex(sig, "1700000000", "abc", body) is False


def test_feishu_signature_unconfigured():
    """未配置 app_secret 时应返回 False（拒绝）。"""
    c = _feishu_via_init(ConnectorConfig(name="f", timeout_seconds=1))
    object.__setattr__(c, "app_secret", "")
    assert c.verify_signature_hex("any", "ts", "nonce", b"{}") is False


def test_feishu_token_cache_isolated():
    """两个 app_id 的缓存应隔离。"""
    c1 = _feishu_via_init(ConnectorConfig(name="f", timeout_seconds=1))
    c2 = _feishu_via_init(ConnectorConfig(name="f", timeout_seconds=1))
    object.__setattr__(c2, "app_id", "cli_other")
    assert c1._TOKEN_CACHE_PREFIX + c1.app_id != c2._TOKEN_CACHE_PREFIX + c2.app_id


# ================== Mattermost token 校验测试 ==================


def test_mattermost_token_match():
    """token 完全匹配应通过。"""
    c = _mattermost_connector(token="super-secret")
    assert c.verify_webhook_token("super-secret") is True


def test_mattermost_token_mismatch():
    """token 不匹配应被拒。"""
    c = _mattermost_connector(token="super-secret")
    assert c.verify_webhook_token("attacker-guess") is False


def test_mattermost_token_unconfigured():
    """未配置 expected token 时应拒绝（fail-closed）。"""
    c = _mattermost_connector(token="")
    assert c.verify_webhook_token("any") is False


def test_mattermost_token_empty_input():
    """客户端没传 token 应被拒。"""
    c = _mattermost_connector(token="super-secret")
    assert c.verify_webhook_token("") is False


if __name__ == "__main__":
    pytest.main([__file__, "-v"])