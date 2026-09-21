"""飞书连接器 — OAuth2 + 消息发送。

参考飞书官方文档实现。
"""

import hashlib
import hmac
import logging
import time

import httpx

logger = logging.getLogger(__name__)


class FeishuConnector:
    """飞书连接器，支持 OAuth2 授权与消息发送。"""

    def __init__(self, app_id: str = "", app_secret: str = ""):
        self.app_id = app_id
        self.app_secret = app_secret
        self.base_url = "https://open.feishu.cn/open-apis"

    @property
    def is_configured(self) -> bool:
        return bool(self.app_id and self.app_secret)

    async def get_tenant_access_token(self) -> str:
        """获取飞书 tenant_access_token。"""
        if not self.is_configured:
            raise RuntimeError("飞书未配置")
        async with httpx.AsyncClient(timeout=10.0) as client:
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
        import json

        payload = {
            "receive_id": open_id,
            "msg_type": "text",
            "content": json.dumps({"text": text}),
        }

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                f"{self.base_url}/im/v1/messages?receive_id_type=open_id",
                headers=headers,
                json=payload,
            )
            data = resp.json()
            if data.get("code") != 0:
                logger.error("[Feishu] 发送消息失败: %s", data)
            return data
