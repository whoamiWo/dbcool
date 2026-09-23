"""企业微信连接器 — 机器人 + 消息发送。

复用 WebhookPusher 的 HMAC 签名模式。
"""

import hashlib
import hmac
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
class WeComConnector(BaseConnector):
    """企业微信机器人连接器。"""

    NAME = "wecom"
    DISPLAY_NAME = "企业微信"

    def __init__(self, config: ConnectorConfig):
        super().__init__(config)
        self.webhook_url = getattr(config, "webhook_url", "")
        self.corp_id = getattr(config, "corp_id", "")
        self.agent_id = getattr(config, "agent_id", "")
        self.corp_secret = getattr(config, "corp_secret", "")
        self.access_token = None
        self.token_expire_time = 0
        self.cached_token = None
        self.cached_token_expire_at = 0

    @property
    def is_configured(self) -> bool:
        return bool(self.webhook_url or (self.corp_id and self.agent_id and self.corp_secret))

    async def health_check(self) -> bool:
        """健康检查：测试 API 连通性。"""
        if not self.is_configured:
            return False
        try:
            # 尝试发送测试消息
            if self.webhook_url:
                result = await self.send_webhook_message("health check")
                return result.get("ok", False)
            else:
                token = await self.get_access_token()
                return bool(token)
        except Exception as e:
            logger.warning("[WeCom] 健康检查失败：%s", e)
            return False

    async def authenticate(self) -> bool:
        """认证测试。"""
        return await self.health_check()

    async def get_access_token(self) -> str:
        """获取企业微信 access_token（带缓存）。"""
        now = time.time()
        if self.cached_token and now < self.cached_token_expire_at:
            return self.cached_token
        
        if not self.corp_id or not self.corp_secret:
            raise RuntimeError("企业微信未配置")

        async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
            resp = await client.get(
                f"https://qyapi.weixin.qq.com/cgi-bin/gettoken",
                params={"corpid": self.corp_id, "corpsecret": self.corp_secret},
            )
            data = resp.json()
            if data.get("errcode") != 0:
                raise RuntimeError(f"获取 access_token 失败：{data}")
            self.cached_token = data["access_token"]
            self.cached_token_expire_at = now + data.get("expires_in", 7200) - 300
            return self.cached_token

    async def send_webhook_message(self, text: str) -> dict:
        """通过 Webhook 发送消息。"""
        if not self.webhook_url:
            return {"ok": False, "error": "no_webhook"}

        payload = {"text": {"content": text}}

        async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
            resp = await client.post(self.webhook_url, json=payload)
            data = resp.json()
            if data.get("errcode", 0) != 0:
                logger.error("[WeCom] Webhook 发送失败：%s", data)
            return {"ok": data.get("errcode", 0) == 0, "data": data}

    async def send_message(self, text: str, **kwargs) -> dict:
        """通过 Bot API 发送消息。

        Args:
            text: 消息文本
            **kwargs: 额外参数（如 touser, toparty）

        Returns:
            企业微信 API 响应 dict
        """
        if not self.corp_id or not self.agent_id or not self.corp_secret:
            logger.warning("[WeCom] 未配置 Bot API，跳过消息发送")
            return {"ok": False, "error": "not_configured"}

        token = await self.get_access_token()
        payload = {
            "touser": kwargs.get("touser", "@all"),
            "msgtype": "text",
            "agentid": self.agent_id,
            "text": {"content": text},
            "safe": 0,
        }

        headers = {"Content-Type": "application/json"}

        async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
            resp = await client.post(
                f"https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token={token}",
                headers=headers,
                json=payload,
            )
            data = resp.json()
            if data.get("errcode", 0) != 0:
                logger.error("[WeCom] 发送消息失败：%s", data)
            return {"ok": data.get("errcode", 0) == 0, "data": data}