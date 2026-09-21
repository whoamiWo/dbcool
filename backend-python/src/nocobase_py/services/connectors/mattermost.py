"""Mattermost 连接器 — Webhook 兼容适配器。

参考 Slack 连接器实现，支持 Incoming Webhook 出站发送。
"""

import logging

import httpx

logger = logging.getLogger(__name__)


class MattermostConnector:
    """Mattermost 连接器，支持 Incoming Webhook 消息发送。"""

    def __init__(self, webhook_url: str = "", bot_token: str = ""):
        self.webhook_url = webhook_url
        self.bot_token = bot_token

    @property
    def is_configured(self) -> bool:
        return bool(self.webhook_url)

    async def send_message(self, channel: str, text: str) -> dict:
        """通过 Incoming Webhook 发送消息。

        Args:
            channel: 频道名称（如 "town-square"）
            text: 消息正文

        Returns:
            Mattermost API 响应 dict
        """
        if not self.is_configured:
            logger.warning("[Mattermost] 未配置，跳过消息发送")
            return {"ok": False, "error": "not_configured"}

        payload = {
            "channel": channel,
            "text": text,
        }

        headers = {"Content-Type": "application/json"}
        if self.bot_token:
            headers["Authorization"] = f"Bearer {self.bot_token}"

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                self.webhook_url,
                headers=headers,
                json=payload,
            )
            data = resp.json()
            if not data.get("ok"):
                logger.error("[Mattermost] 发送消息失败: %s", data)
            return data
