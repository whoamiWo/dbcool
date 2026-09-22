"""钉钉连接器 — 机器人 + 消息发送。

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
class DingTalkConnector(BaseConnector):
    """钉钉机器人连接器，支持加签安全设置。"""

    NAME = "dingtalk"
    DISPLAY_NAME = "钉钉"

    def __init__(self, config: ConnectorConfig):
        super().__init__(config)
        self.webhook_url = getattr(config, "webhook_url", "")
        self.secret = getattr(config, "secret", "")  # 加签密钥

    @property
    def is_configured(self) -> bool:
        return bool(self.webhook_url)

    async def health_check(self) -> bool:
        """健康检查：测试 Webhook 连通性。"""
        if not self.is_configured:
            return False
        try:
            # 发送一条测试消息
            result = await self.send_message("test", "health check")
            return result.get("ok", False)
        except Exception as e:
            logger.warning("[DingTalk] 健康检查失败：%s", e)
            return False

    async def authenticate(self) -> bool:
        """认证测试。"""
        return await self.health_check()

    def _sign(self, timestamp: str) -> str:
        """生成钉钉加签签名。"""
        if not self.secret:
            return ""
        string_to_sign = f"{timestamp}\n{self.secret}"
        hmac_code = hmac.new(
            self.secret.encode(),
            string_to_sign.encode(),
            hashlib.sha256,
        ).digest()
        return hmac_code.hex()

    async def send_message(self, text: str, **kwargs) -> dict:
        """发送消息到钉钉机器人。

        Args:
            text: 消息文本
            **kwargs: 额外参数（如 at_mobiles, is_at_all）

        Returns:
            钉钉 API 响应 dict
        """
        if not self.is_configured:
            logger.warning("[DingTalk] 未配置，跳过消息发送")
            return {"ok": False, "error": "not_configured"}

        # 构建 URL（带签名）
        url = self.webhook_url
        if self.secret:
            timestamp = str(round(time.time() * 1000))
            sign = self._sign(timestamp)
            url = f"{url}&timestamp={timestamp}&sign={sign}"

        payload = {
            "msgtype": "text",
            "text": {"content": text},
        }

        # 添加@人配置
        if kwargs.get("at_mobiles"):
            payload["at"] = {
                "atMobiles": kwargs["at_mobiles"],
                "isAtAll": kwargs.get("is_at_all", False),
            }

        headers = {"Content-Type": "application/json; charset=utf-8"}

        async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
            resp = await client.post(url, headers=headers, json=payload)
            data = resp.json()
            if data.get("errcode", 0) != 0:
                logger.error("[DingTalk] 发送消息失败：%s", data)
            return {"ok": data.get("errcode", 0) == 0, "data": data}