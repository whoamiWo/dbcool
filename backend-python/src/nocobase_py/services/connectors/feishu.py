"""飞书连接器 — OAuth2 + 消息发送。

参考飞书官方文档实现。
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
class FeishuConnector(BaseConnector):
    """飞书连接器，支持 OAuth2 授权与消息发送。"""

    NAME = "feishu"
    DISPLAY_NAME = "飞书"

    def __init__(self, config: ConnectorConfig):
        super().__init__(config)
        self.app_id = getattr(config, "app_id", "")
        self.app_secret = getattr(config, "app_secret", "")
        self.base_url = "https://open.feishu.cn/open-apis"

    @property
    def is_configured(self) -> bool:
        return bool(self.app_id and self.app_secret)

    async def health_check(self) -> bool:
        """健康检查：测试 API 连通性。"""
        if not self.is_configured:
            return False
        try:
            token = await self.get_tenant_access_token()
            return bool(token)
        except Exception as e:
            logger.warning("[Feishu] 健康检查失败：%s", e)
            return False

    async def authenticate(self) -> bool:
        """认证测试。"""
        return await self.health_check()

    async def get_tenant_access_token(self) -> str:
        """获取飞书 tenant_access_token。"""
        if not self.is_configured:
            raise RuntimeError("飞书未配置")
        async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
            resp = await client.post(
                f"{self.base_url}/auth/v3/tenant_access_token/internal",
                json={"app_id": self.app_id, "app_secret": self.app_secret},
            )
            data = resp.json()
            if not data.get("tenant_access_token"):
                raise RuntimeError(f"获取 tenant_access_token 失败: {data}")
            return data["tenant_access_token"]

    async def send_message(self, open_id: str, text: str) -> dict:
        """发送消息（/im/v1/messages）。"""
        if not self.is_configured:
            raise RuntimeError("飞书未配置")

        access_token = await self.get_tenant_access_token()
        headers = {
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
        }

        payload = {
            "receive_id": open_id,
            "msg_type": "text",
            "content": json.dumps({"text": text}),
        }

        async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
            resp = await client.post(
                f"{self.base_url}/im/v1/messages?receive_id_type=open_id",
                headers=headers,
                json=payload,
            )
            data = resp.json()
            if data.get("code") != 0:
                logger.error("[Feishu] 发送消息失败：%s", data)
            return data