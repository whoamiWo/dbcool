"""Mattermost 连接器 — Webhook + API。

兼容 Mattermost 的 Incoming Webhook 和 Bot API。
"""

import logging

import httpx

from nocobase_py.services.connectors.base import (
    BaseConnector,
    ConnectorConfig,
    register_connector,
)

logger = logging.getLogger(__name__)


@register_connector
class MattermostConnector(BaseConnector):
    """Mattermost 连接器，支持 Webhook 和 Bot API。"""

    NAME = "mattermost"
    DISPLAY_NAME = "Mattermost"

    def __init__(self, config: ConnectorConfig):
        super().__init__(config)
        self.webhook_url = getattr(config, "webhook_url", "")
        self.bot_token = getattr(config, "bot_token", "")
        self.server_url = getattr(config, "server_url", "")
        self.base_url = f"{self.server_url}/api/v4" if self.server_url else ""

    @property
    def is_configured(self) -> bool:
        return bool(self.webhook_url or (self.bot_token and self.server_url))

    async def health_check(self) -> bool:
        """健康检查：测试 API 连通性。"""
        if not self.bot_token or not self.server_url:
            return False
        try:
            async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
                resp = await client.get(
                    f"{self.base_url}/users/me",
                    headers={"Authorization": f"Bearer {self.bot_token}"},
                )
                return resp.status_code == 200
        except Exception as e:
            logger.warning("[Mattermost] 健康检查失败：%s", e)
            return False

    async def authenticate(self) -> bool:
        """认证测试。"""
        return await self.health_check()

    async def send_message(self, channel_id: str, text: str, **kwargs) -> dict:
        """发送消息到 Mattermost 频道。

        Args:
            channel_id: 频道 ID
            text: 消息文本
            **kwargs: 额外参数（如 root_id 用于回复线程）

        Returns:
            Mattermost API 响应 dict
        """
        if not self.is_configured:
            logger.warning("[Mattermost] 未配置，跳过消息发送")
            return {"ok": False, "error": "not_configured"}

        # 如果有 webhook URL，优先使用 webhook
        if self.webhook_url:
            payload = {"channel_id": channel_id, "message": text}
            if kwargs.get("root_id"):
                payload["root_id"] = kwargs["root_id"]

            async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
                resp = await client.post(
                    self.webhook_url,
                    json=payload,
                )
                return {"ok": resp.status_code == 200, "status_code": resp.status_code}

        # 否则使用 Bot API
        if self.bot_token and self.server_url:
            payload = {"channel_id": channel_id, "message": text}
            if kwargs.get("root_id"):
                payload["root_id"] = kwargs["root_id"]

            headers = {
                "Authorization": f"Bearer {self.bot_token}",
                "Content-Type": "application/json",
            }

            async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
                resp = await client.post(
                    f"{self.base_url}/posts",
                    headers=headers,
                    json=payload,
                )
                data = resp.json()
                if resp.status_code != 201:
                    logger.error("[Mattermost] 发送消息失败：%s", data)
                return {"ok": resp.status_code == 201, "post": data}

        return {"ok": False, "error": "not_configured"}
