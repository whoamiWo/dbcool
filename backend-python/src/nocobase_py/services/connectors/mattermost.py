"""Mattermost 连接器 — Incoming Webhook + Bot Token。

支持通过 Webhook 发送消息，兼容 Mattermost 4.x+。
"""

import logging

import httpx

logger = logging.getLogger(__name__)


class MattermostConnector:
    """Mattermost 连接器，支持 Webhook 和 Bot Token 两种模式。"""

    def __init__(self, webhook_url: str = "", bot_token: str = "", team_id: str = ""):
        self.webhook_url = webhook_url
        self.bot_token = bot_token
        self.team_id = team_id
        self.base_url = "https://mattermost.com/api/v4"  # Placeholder, actual URL depends on instance

    @property
    def is_configured(self) -> bool:
        return bool(self.webhook_url or (self.bot_token and self.team_id))

    async def send_message(self, channel: str, text: str, username: str | None = None) -> dict:
        """发送消息到 Mattermost 频道。

        Args:
            channel: 频道 ID 或名称（如 "general" 或 "C12345"）
            text: 消息正文（支持 Markdown）
            username: 可选用户名覆盖

        Returns:
            dict{ok:bool, id:str, channel_id:str}
        """
        if self.webhook_url:
            return await self._send_via_webhook(text)
        elif self.bot_token and self.team_id:
            return await self._send_via_bot_api(channel, text, username)
        else:
            logger.warning("[Mattermost] 未配置")
            return {"ok": False, "error": "not_configured"}

    async def _send_via_webhook(self, text: str) -> dict:
        """通过 Incoming Webhook 发送消息（最简单，无需鉴权）。"""
        payload = {"text": text}
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(self.webhook_url, json=payload)
            if resp.status_code == 200:
                return {"ok": True, "id": resp.json().get("id", "")}
            else:
                logger.error("[Mattermost] Webhook 失败: %s", resp.text)
                return {"ok": False, "error": f"status {resp.status_code}"}

    async def _send_via_bot_api(self, channel: str, text: str, username: str | None = None) -> dict:
        """通过 Bot Token + API v4 发送消息。"""
        headers = {
            "Authorization": f"Bearer {self.bot_token}",
            "Content-Type": "application/json; charset=utf-8",
        }
        payload = {"channel_id": channel, "message": text}
        if username:
            payload["username"] = username

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                f"{self.base_url}/posts",
                headers=headers,
                json=payload,
            )
            data = resp.json()
            if not data.get("id"):
                logger.error("[Mattermost] Bot API 失败: %s", data)
                return {"ok": False, "error": data.get("message", "unknown")}
            return {"ok": True, "id": data["id"], "channel_id": data.get("channel_id", "")}
