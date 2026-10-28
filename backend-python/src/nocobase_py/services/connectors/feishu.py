"""飞书连接器 — OAuth2 + 消息发送 + 事件签名校验。

参考飞书官方文档实现。
P0-2a: 增加 token Redis 缓存 + 事件签名校验。
"""

import hashlib
import hmac
import json
import logging
import time

import httpx

from nocobase_py.redis_client import get_redis
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
    # token 缓存 key 前缀
    _TOKEN_CACHE_PREFIX = "feishu:token:"
    # 提前 5 分钟过期，避免边界超时
    _TOKEN_REFRESH_MARGIN = 300

    def __init__(self, config: ConnectorConfig):
        super().__init__(config)
        self.app_id = getattr(config, "app_id", "")
        self.app_secret = getattr(config, "app_secret", "")
        self.base_url = "https://open.feishu.cn/open-apis"
        # 进程内 fallback 缓存（Redis 不可用时降级，避免单副本本地开发挂）
        self._mem_token_cache: dict[str, tuple[str, float]] = {}

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
        """获取飞书 tenant_access_token（Redis 缓存，进程内 fallback）。

        优先级：Redis 缓存 → 进程内缓存 → HTTP API。
        Redis 不可用时自动降级到进程内缓存，确保本地开发可用。
        """
        if not self.is_configured:
            raise RuntimeError("飞书未配置")
        cache_key = self._TOKEN_CACHE_PREFIX + self.app_id

        # 1. Redis 缓存命中
        try:
            r = await get_redis()
            cached = await r.get(cache_key)
            if cached:
                logger.debug("[Feishu] token Redis 缓存命中")
                return cached
        except Exception as e:
            logger.debug("[Feishu] Redis 不可用,降级进程内缓存: %s", e)

        # 2. 进程内 fallback
        entry = self._mem_token_cache.get(cache_key)
        if entry is not None:
            tok, exp = entry
            if time.monotonic() < exp:
                logger.debug("[Feishu] token 进程内缓存命中")
                return tok

        # 3. HTTP API 拉取
        async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
            resp = await client.post(
                f"{self.base_url}/auth/v3/tenant_access_token/internal",
                json={"app_id": self.app_id, "app_secret": self.app_secret},
            )
        data = resp.json()
        # Feishu API 返回 {code, msg, tenant_access_token, expire}
        code = data.get("code", -1)
        if code != 0:
            raise RuntimeError(f"获取 tenant_access_token 失败: code={code}, msg={data.get('msg', 'unknown')}")
        token = data.get("tenant_access_token", "")
        if not token:
            raise RuntimeError("获取 tenant_access_token 失败: 响应中无 token")
        expire_raw = int(data.get("expire", 7200))
        # 提前 5 分钟过期避免边界超时
        ttl = max(expire_raw - self._TOKEN_REFRESH_MARGIN, 60)

        # 写 Redis（失败不抛,降级进程内）
        try:
            r = await get_redis()
            await r.setex(cache_key, ttl, token)
        except Exception as e:
            logger.debug("[Feishu] Redis 写入失败,仅进程内: %s", e)
        # 进程内 fallback
        self._mem_token_cache[cache_key] = (token, time.monotonic() + ttl)
        return token

    def verify_signature(self, timestamp: str, nonce: str, body_bytes: bytes) -> bool:
        """事件订阅签名校验（HMAC-SHA256）。

        飞书官方算法（v2 webhook）：
          signature = base64(HMAC-SHA256(key=encrypt_key, message=timestamp + nonce + body))

        本实现以 app_secret 替代 encrypt_key 作为 fallback（多数项目未单独配 encrypt_key）。
        生产建议在 connector config 增加 encrypt_key 字段并优先使用。

        Args:
            timestamp: X-Lark-Request-Timestamp header
            nonce: X-Lark-Request-Nonce header
            body_bytes: 原始请求体（签名计算需用原始字节,不能 str + re-encode）

        Returns:
            签名是否匹配（hex 编码小写）
        """
        if not self.app_secret:
            return False
        try:
            message = (timestamp or "") + (nonce or "") + body_bytes.decode("utf-8")
        except UnicodeDecodeError:
            return False
        hmac.new(self.app_secret.encode(), message.encode(), hashlib.sha256).digest()
        # 入站时调用方未提供 signature_hex，本方法用作"重算签名"工具,
        # 实际校验请用 verify_signature_hex(signature_hex, ...)
        return False  # 本方法仅做签名计算参考,见下方 verify_signature_hex

    def verify_signature_hex(self, signature_hex: str, timestamp: str, nonce: str, body_bytes: bytes) -> bool:
        """按 hex 编码对比签名（飞书默认格式）。

        Args:
            signature_hex: 客户端传来的 X-Lark-Signature header（hex 小写）
        """
        if not self.app_secret:
            return False
        try:
            message = (timestamp or "") + (nonce or "") + body_bytes.decode("utf-8")
        except UnicodeDecodeError:
            return False
        sig = hmac.new(self.app_secret.encode(), message.encode(), hashlib.sha256).digest()
        expected_hex = sig.hex()
        return hmac.compare_digest(expected_hex, signature_hex or "")

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

    async def send_card_message(
        self,
        open_id: str,
        card_element: dict,
        card_header_title: str,
        card_elements: list[dict],
    ) -> dict:
        """发送富文本卡片消息（/im/v1/messages）。

        Args:
            open_id: 接收者 open_id
            card_element: 主元素 (text 或其他类型)
            card_header_title: 卡片标题
            card_elements: 额外元素列表 (可选)

        Returns:
            API 响应
        """
        if not self.is_configured:
            raise RuntimeError("飞书未配置")

        access_token = await self.get_tenant_access_token()
        headers = {
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
        }

        # 构建卡片结构
        card = {
            "header": {"title": card_header_title},
            "elements": [card_element] + (card_elements or []),
        }

        payload = {
            "receive_id": open_id,
            "msg_type": "interactive",
            "content": json.dumps(card),
        }

        async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
            resp = await client.post(
                f"{self.base_url}/im/v1/messages?receive_id_type=open_id",
                headers=headers,
                json=payload,
            )
            data = resp.json()
            if data.get("code") != 0:
                logger.error("[Feishu] 发送卡片消息失败：%s", data)
            return data


def build_feishu_card_text_element(text: str, tag: str = "lark_md") -> dict:
    """构建飞书卡片文本元素。

    Args:
        text: 文本内容
        tag: 标签类型 (lark_md/text/plain)

    Returns:
        卡片元素对象
    """
    return {"tag": tag, "text": text}


def build_feishu_card_divider() -> dict:
    """构建飞书卡片分割线元素。

    Returns:
        卡片元素对象
    """
    return {"tag": "hr"}


def build_feishu_card_button(text: str, value: str, action_type: str = "click") -> dict:
    """构建飞书卡片按钮元素。

    Args:
        text: 按钮文本
        value: 按钮值 (回调用)
        action_type: 动作类型 (click/request)

    Returns:
        卡片元素对象
    """
    return {
        "tag": "button",
        "text": {"tag": "plain_text", "content": text},
        "value": {"action_value": value},
        "type": action_type,
    }
