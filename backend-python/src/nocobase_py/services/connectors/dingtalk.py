"""钉钉连接器 — OAuth2 + 通讯录同步 + 应用消息。

参考钉钉官方文档实现，复用 HMAC 签名模式。
"""

import hashlib
import hmac
import logging
import time

import httpx

logger = logging.getLogger(__name__)


class DingTalkConnector:
    """钉钉连接器，支持 OAuth2 授权与消息发送。"""

    def __init__(self, app_key: str = "", app_secret: str = "", agent_id: str = ""):
        self.app_key = app_key
        self.app_secret = app_secret
        self.agent_id = agent_id
        self.base_url = "https://api.dingtalk.com"

    @property
    def is_configured(self) -> bool:
        return bool(self.app_key and self.app_secret and self.agent_id)

    async def get_access_token(self) -> str:
        """获取钉钉 access_token（新版 POST /v1.0/oauth2/accessToken）。"""
        if not self.is_configured:
            raise RuntimeError("钉钉未配置")
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                f"{self.base_url}/v1.0/oauth2/accessToken",
                json={"appKey": self.app_key, "appSecret": self.app_secret},
            )
            data = resp.json()
            access_token = data.get("accessToken")
            if not access_token:
                raise RuntimeError(f"获取 access_token 失败: {data}")
            return access_token

    async def send_message(self, user_id: str, text: str, msg_type: str = "text") -> dict:
        """发送应用消息（/message/send）。"""
        if not self.is_configured:
            raise RuntimeError("钉钉未配置")

        access_token = await self.get_access_token()
        headers = {"Content-Type": "application/json"}
        payload = {
            "touser": user_id,
            "msgtype": msg_type,
            msg_type: {"content": text},
            "agentid": int(self.agent_id),
        }

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                f"{self.base_url}/message/send?access_token={access_token}",
                headers=headers,
                json=payload,
            )
            data = resp.json()
            if data.get("errcode") != 0:
                logger.error("[DingTalk] 发送消息失败: %s", data)
            return data
