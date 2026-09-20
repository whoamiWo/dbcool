"""企业微信连接器 — OAuth2 + 通讯录同步 + 应用消息。

参考钉钉连接器实现，复用 HMAC 签名模式。
"""

import hashlib
import hmac
import logging
import time

import httpx

logger = logging.getLogger(__name__)


class WeComConnector:
    """企业微信连接器，支持 OAuth2 授权与消息发送。"""

    def __init__(self, corp_id: str = "", corp_secret: str = "", agent_id: str = ""):
        self.corp_id = corp_id
        self.corp_secret = corp_secret
        self.agent_id = agent_id
        self.base_url = "https://qyapi.weixin.qq.com/cgi-bin"

    @property
    def is_configured(self) -> bool:
        return bool(self.corp_id and self.corp_secret and self.agent_id)

    async def get_access_token(self) -> str:
        """获取企业微信 access_token。"""
        if not self.is_configured:
            raise RuntimeError("企业微信未配置")
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(
                f"{self.base_url}/gettoken",
                params={"corpid": self.corp_id, "corpsecret": self.corp_secret},
            )
            data = resp.json()
            if not data.get("access_token"):
                raise RuntimeError(f"获取 access_token 失败: {data}")
            return data["access_token"]

    async def send_message(self, user_id: str, text: str, msg_type: str = "text") -> dict:
        """发送应用消息（/message/send）。

        Args:
            user_id: 接收者 userid
            text: 消息正文
            msg_type: 消息类型（text/markdown/image 等）
        """
        if not self.is_configured:
            raise RuntimeError("企业微信未配置")

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
                logger.error("[WeCom] 发送消息失败: %s", data)
            return data
