"""Slack 连接器 — Events API + chat.postMessage。

复用 WebhookPusher 的 HMAC 签名模式，
与 Java 侧通过 Redis 或内部 HTTP 调用。
"""

import hashlib
import hmac
import json
import logging
import time

import httpx

from nocobase_py.services.connectors.base import (
    BaseConnector,
    ConnectorConfig,
    register_connector,
)

logger = logging.getLogger(__name__)


@register_connector
class SlackConnector(BaseConnector):
    """Slack 连接器，支持 Events API 签名验证与消息发送。"""

    NAME = "slack"
    DISPLAY_NAME = "Slack"

    def __init__(self, config: ConnectorConfig):
        super().__init__(config)
        self.signing_secret = getattr(config, "signing_secret", "")
        self.bot_token = getattr(config, "bot_token", "")
        self.team_id = getattr(config, "team_id", "")
        self.base_url = "https://slack.com/api"

    @property
    def is_configured(self) -> bool:
        return bool(self.signing_secret and self.bot_token)

    async def health_check(self) -> bool:
        """健康检查：测试 API 连通性。"""
        if not self.is_configured:
            return False
        try:
            async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
                resp = await client.get(
                    f"{self.base_url}/auth.test",
                    headers={"Authorization": f"Bearer {self.bot_token}"},
                )
                return resp.status_code == 200
        except Exception as e:
            logger.warning("[Slack] 健康检查失败：%s", e)
            return False

    async def authenticate(self) -> bool:
        """认证测试。"""
        return await self.health_check()

    def verify_signature(self, timestamp: int, signature: str, body: bytes) -> bool:
        """校验 Slack Events API 请求签名。

        算法：HMAC-SHA256，message = "v0:" + timestamp + ":" + body
        """
        if not self.signing_secret:
            return False
        if abs(time.time() - timestamp) > 300:
            logger.warning("[Slack] 时间戳过期或未来时间：%s", timestamp)
            return False

        msg = f"v0:{timestamp}:".encode() + body
        digest = hmac.new(
            self.signing_secret.encode(),
            msg,
            hashlib.sha256,
        ).hexdigest()
        expected = f"v0:{digest}"
        return hmac.compare_digest(expected, signature)

    async def send_message(self, channel: str, text: str, thread_ts: str | None = None) -> dict:
        """发送消息到 Slack 频道（chat.postMessage）。

        Args:
            channel: 频道 ID 或名称（如 "C12345" 或 "general"）
            text: 消息正文（支持 markdown 斜体 *text*）
            thread_ts: 可选线程根消息 timestamp

        Returns:
            Slack API 响应 dict，包含 ok/ts/channel 等字段
        """
        if not self.is_configured:
            logger.warning("[Slack] 未配置，跳过消息发送")
            return {"ok": False, "error": "not_configured"}

        payload = {"channel": channel, "text": text}
        if thread_ts:
            payload["thread_ts"] = thread_ts

        headers = {
            "Authorization": f"Bearer {self.bot_token}",
            "Content-Type": "application/json; charset=utf-8",
        }

        async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
            resp = await client.post(
                f"{self.base_url}/chat.postMessage",
                headers=headers,
                json=payload,
            )
            data = resp.json()
            if not data.get("ok"):
                logger.error("[Slack] chat.postMessage 失败：%s", data)
            return data

    async def reply(self, channel: str, thread_ts: str, text: str) -> dict:
        """回复线程消息（thread_ts 必填）。"""
        return await self.send_message(channel, text, thread_ts=thread_ts)
