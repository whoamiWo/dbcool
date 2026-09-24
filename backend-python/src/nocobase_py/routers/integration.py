"""集成连接器路由 — 钉钉/企微/Slack/飞书。

复用 WebhookPusher 的 HMAC 签名模式，与 Java 侧通过 Redis 事件通道。
"""

import json
import logging
import time
from typing import Any

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import PlainTextResponse
from jose import jwt
from pydantic import BaseModel, Field

from nocobase_py.config import get_settings
from nocobase_py.security import AuthUser, get_current_user
from nocobase_py.services.connectors.dingtalk import DingTalkConnector
from nocobase_py.services.connectors.feishu import FeishuConnector
from nocobase_py.services.connectors.mattermost import MattermostConnector
from nocobase_py.services.connectors.slack import SlackConnector
from nocobase_py.services.connectors.wecom import WeComConnector

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/integration", tags=["integration"])

# 幂等去重：单机 TTL 集合（生产环境应换 Redis）
_seen_event_ids: dict[str, float] = {}
_IDEMPOTENCY_TTL = 300.0  # 5 min


def _dedup(event_id: str) -> bool:
    """已处理返回 True（去重命中）。"""
    now = time.time()
    # 清理过期条目
    expired = [k for k, v in _seen_event_ids.items() if now - v > _IDEMPOTENCY_TTL]
    for k in expired:
        _seen_event_ids.pop(k, None)
    if event_id in _seen_event_ids:
        return True
    _seen_event_ids[event_id] = now
    return False


def _service_token() -> str:
    """签发服务间 JWT（与 Java 共享密钥）。"""
    s = get_settings()
    payload = {"sub": "nocobase-py", "service": True, "iat": int(time.time())}
    return jwt.encode(payload, s.jwt_secret, algorithm=s.jwt_algorithm)


async def _forward_to_java(event: dict) -> None:
    """将入站事件转发到 Java /api/im/messages 落地。"""
    s = get_settings()
    channel_id = s.slack_event_channel_id
    if not channel_id:
        logger.warning("[Slack] slack_event_channel_id 未配置，跳过转发")
        raise HTTPException(status_code=500, detail="slack_event_channel_id 未配置")
    url = f"{s.java_backend_url}/api/im/messages"
    token = _service_token()
    async with httpx.AsyncClient(timeout=10.0) as client:
        resp = await client.post(
            url,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            },
            json={
                "channelId": channel_id,
                "content": event.get("text") or "",
                "contentType": "slack_event",
                "meta": json.dumps({"slack_event_id": event.get("event_id"), "source": "slack"}),
            },
        )
    if resp.status_code >= 400:
        raise HTTPException(status_code=502, detail=f"Java 转发失败: {resp.status_code}")


def _slack_connector() -> SlackConnector:
    """基于全局配置构造 Slack 连接器。"""
    s = get_settings()
    return SlackConnector(
        signing_secret=s.slack_signing_secret,
        bot_token=s.slack_bot_token,
        team_id=s.slack_team_id,
    )


def _wecom_connector() -> WeComConnector:
    """基于全局配置构造企业微信连接器。"""
    s = get_settings()
    return WeComConnector(
        corp_id=s.wecom_corp_id if hasattr(s, "wecom_corp_id") else "",
        corp_secret=s.wecom_corp_secret if hasattr(s, "wecom_corp_secret") else "",
        agent_id=s.wecom_agent_id if hasattr(s, "wecom_agent_id") else "",
    )


def _dingtalk_connector() -> DingTalkConnector:
    """基于全局配置构造钉钉连接器。"""
    s = get_settings()
    return DingTalkConnector(
        app_key=s.dingtalk_app_key if hasattr(s, "dingtalk_app_key") else "",
        app_secret=s.dingtalk_app_secret if hasattr(s, "dingtalk_app_secret") else "",
        agent_id=s.dingtalk_agent_id if hasattr(s, "dingtalk_agent_id") else "",
    )


def _feishu_connector() -> FeishuConnector:
    """基于全局配置构造飞书连接器。"""
    s = get_settings()
    return FeishuConnector(
        app_id=s.feishu_app_id if hasattr(s, "feishu_app_id") else "",
        app_secret=s.feishu_app_secret if hasattr(s, "feishu_app_secret") else "",
    )


class SlackMessagePayload(BaseModel):
    """Slack 消息发送请求。"""
    channel: str = Field(..., description="频道 ID 或名称")
    text: str = Field(..., description="消息正文")
    thread_ts: str | None = Field(default=None, description="线程根消息 timestamp")


class SlackConfigPayload(BaseModel):
    """Slack 连接器配置。"""
    signing_secret: str = Field(..., description="Signing Secret")
    bot_token: str = Field(..., description="Bot User OAuth Token")
    team_id: str = Field(default="", description="Team ID")


@router.post("/slack/config")
async def slack_config(
    cfg: SlackConfigPayload,
    user: AuthUser = Depends(get_current_user),
) -> dict[str, Any]:
    """配置 Slack 连接器（signing_secret + bot_token）。"""
    # 持久化配置（实际应存数据库或 Vault）
    logger.info("[integration] Slack 配置更新: team_id=%s", cfg.team_id)
    return {"code": 0, "message": "success", "data": {"configured": True}}


@router.post("/slack/send")
async def slack_send(
    payload: SlackMessagePayload,
    user: AuthUser = Depends(get_current_user),
) -> dict[str, Any]:
    """发送消息到 Slack 频道。"""
    connector = _slack_connector()
    if not connector.is_configured:
        raise HTTPException(status_code=400, detail="Slack 未配置")

    result = await connector.send_message(
        channel=payload.channel,
        text=payload.text,
        thread_ts=payload.thread_ts,
    )
    return {"code": 0 if result.get("ok") else 1, "data": result}


@router.post("/slack/verify")
async def slack_verify(
    request: Request,
) -> PlainTextResponse | dict[str, Any]:
    """验证 Slack Events API 请求签名并处理 challenge。
    
    修复：先验签再返 challenge（Slack 要求先返回 challenge 完成握手）。
    不需要用户鉴权（Slack 事件推送无需 JWT）。
    """
    connector = _slack_connector()
    body_bytes = await request.body()
    timestamp = int(request.headers.get("X-Slack-Request-Timestamp", 0))
    signature = request.headers.get("X-Slack-Signature", "")
    
    # 先验签
    valid = connector.verify_signature(timestamp, signature, body_bytes)
    if not valid:
        raise HTTPException(status_code=401, detail="Invalid signature")
    
    # 验签通过后，处理 URL 验证挑战或入站事件
    try:
        body_json = json.loads(body_bytes.decode("utf-8"))
        if "challenge" in body_json:
            # 返回 challenge 字符串完成验证
            return PlainTextResponse(content=body_json["challenge"])
    except Exception:
        pass

    # 入站事件落地：幂等去重 + 转发到 Java
    event = body_json.get("event") if isinstance(body_json, dict) else None
    event_id = body_json.get("event_id") if isinstance(body_json, dict) else None
    if event and event_id:
        if _dedup(event_id):
            return {"code": 0, "data": {"valid": True}}
        try:
            await _forward_to_java({"event_id": event_id, "text": event.get("text", ""), "type": event.get("type")})
        except HTTPException:
            raise
        except Exception as e:
            logger.error("[Slack] 入站转发失败: %s", e)
            raise HTTPException(status_code=500, detail=f"转发失败: {e}")
    return {"code": 0, "data": {"valid": True}}


class GenericMessagePayload(BaseModel):
    """通用消息发送请求（钉钉/企微/飞书）。"""
    to: str = Field(..., description="接收者 ID")
    text: str = Field(..., description="消息正文")


@router.post("/wecom/send")
async def wecom_send(
    payload: GenericMessagePayload,
    user: AuthUser = Depends(get_current_user),
) -> dict[str, Any]:
    """发送企业微信应用消息。"""
    connector = _wecom_connector()
    if not connector.is_configured:
        raise HTTPException(status_code=400, detail="企业微信未配置")
    result = await connector.send_message(user_id=payload.to, text=payload.text)
    return {"code": 0 if result.get("errcode") == 0 else 1, "data": result}


@router.post("/dingtalk/send")
async def dingtalk_send(
    payload: GenericMessagePayload,
    user: AuthUser = Depends(get_current_user),
) -> dict[str, Any]:
    """发送钉钉应用消息。"""
    connector = _dingtalk_connector()
    if not connector.is_configured:
        raise HTTPException(status_code=400, detail="钉钉未配置")
    result = await connector.send_message(user_id=payload.to, text=payload.text)
    return {"code": 0 if result.get("errcode") == 0 else 1, "data": result}


@router.post("/feishu/send")
async def feishu_send(
    payload: GenericMessagePayload,
    user: AuthUser = Depends(get_current_user),
) -> dict[str, Any]:
    """发送飞书消息。"""
    connector = _feishu_connector()
    if not connector.is_configured:
        raise HTTPException(status_code=400, detail="飞书未配置")
    result = await connector.send_message(open_id=payload.to, text=payload.text)
    return {"code": 0 if result.get("code") == 0 else 1, "data": result}


@router.post("/feishu/verify")
async def feishu_verify(request: Request) -> PlainTextResponse | dict[str, Any]:
    """验证飞书事件回调签名（P0-2a 接真）。

    飞书事件订阅请求带以下 header:
      X-Lark-Request-Timestamp, X-Lark-Request-Nonce, X-Lark-Signature (hex)
    校验规则：HMAC-SHA256(key=app_secret, message=timestamp + nonce + body)。

    challenge 响应：飞书 URL 校验模式会 POST {challenge: "..."}，原样返回。
    """
    connector = _feishu_connector()
    if not connector.is_configured:
        raise HTTPException(status_code=503, detail="飞书未配置")

    body_bytes = await request.body()
    timestamp = request.headers.get("X-Lark-Request-Timestamp", "")
    nonce = request.headers.get("X-Lark-Request-Nonce", "")
    signature = request.headers.get("X-Lark-Signature", "")

    if not connector.verify_signature_hex(signature, timestamp, nonce, body_bytes):
        logger.warning("[Feishu] 入站签名校验失败 ts=%s nonce=%s", timestamp, nonce)
        raise HTTPException(status_code=401, detail="Invalid signature")

    try:
        body_json = json.loads(body_bytes.decode("utf-8"))
    except Exception:
        body_json = {}

    # URL 验证 challenge（飞书事件订阅握手）
    if isinstance(body_json, dict) and "challenge" in body_json:
        return PlainTextResponse(content=str(body_json["challenge"]))

    # 业务事件落地：转 Java IM
    event_id = body_json.get("uuid") if isinstance(body_json, dict) else None
    if event_id and _dedup(str(event_id)):
        return {"code": 0, "data": {"valid": True}}
    # 飞书事件结构：body.header.event_type + body.event
    header = body_json.get("header", {}) if isinstance(body_json, dict) else {}
    event = body_json.get("event", {}) if isinstance(body_json, dict) else {}
    text = ""
    if isinstance(event, dict):
        # 飞书消息事件 payload 结构多样,先取 text/content
        if "text" in event:
            text = str(event.get("text", ""))
        elif "message" in event and isinstance(event["message"], dict):
            text = str(event["message"].get("content", ""))
    event_type = header.get("event_type", "unknown") if isinstance(header, dict) else "unknown"
    try:
        await _forward_to_java({
            "event_id": event_id,
            "text": text,
            "type": event_type,
        })
    except HTTPException:
        raise
    except Exception as e:
        logger.error("[Feishu] 入站转发失败: %s", e)
        raise HTTPException(status_code=500, detail=f"转发失败: {e}")
    return {"code": 0, "data": {"valid": True}}


def _mattermost_connector() -> MattermostConnector:
    """基于全局配置构造 Mattermost 连接器。"""
    s = get_settings()
    return MattermostConnector(
        webhook_url=s.mattermost_webhook_url if hasattr(s, "mattermost_webhook_url") else "",
        bot_token=s.mattermost_bot_token if hasattr(s, "mattermost_bot_token") else "",
        server_url=s.mattermost_server_url if hasattr(s, "mattermost_server_url") else "",
    )


@router.post("/mattermost/send")
async def mattermost_send(
    payload: GenericMessagePayload,
    user: AuthUser = Depends(get_current_user),
) -> dict[str, Any]:
    """发送 Mattermost 消息（通过 Incoming Webhook）。"""
    connector = _mattermost_connector()
    if not connector.is_configured:
        raise HTTPException(status_code=400, detail="Mattermost 未配置")
    result = await connector.send_message(channel=payload.to, text=payload.text)
    return {"code": 0 if result.get("ok") else 1, "data": result}


@router.post("/mattermost/verify")
async def mattermost_verify(request: Request) -> dict[str, Any]:
    """验证 Mattermost outgoing webhook 请求（P0-2a 接真）。

    Mattermost outgoing webhook 字段：
      token: 预共享密钥（与 settings.mattermost_webhook_token 比对）
      text / user_name / channel_id / channel_name ...
    """
    connector = _mattermost_connector()
    if not connector.is_configured:
        raise HTTPException(status_code=503, detail="Mattermost 未配置")

    # Mattermost outgoing webhook 走 form-urlencoded 或 json,两种都兼容
    content_type = request.headers.get("Content-Type", "")
    if "application/x-www-form-urlencoded" in content_type:
        form = await request.form()
        token = form.get("token", "")
        text = form.get("text", "")
        channel_id = form.get("channel_id", "")
        user_name = form.get("user_name", "")
        trigger_word = form.get("trigger_word", "")
    else:
        try:
            body_json = json.loads((await request.body()).decode("utf-8"))
        except Exception:
            body_json = {}
        token = body_json.get("token", "")
        text = body_json.get("text", "")
        channel_id = body_json.get("channel_id", "")
        user_name = body_json.get("user_name", "")
        trigger_word = body_json.get("trigger_word", "")

    if not connector.verify_webhook_token(str(token)):
        logger.warning("[Mattermost] 入站 webhook token 校验失败 channel=%s", channel_id)
        raise HTTPException(status_code=401, detail="Invalid webhook token")

    # 入站事件落地
    event_id = f"mm-{channel_id}-{text[:64]}"
    if _dedup(event_id):
        return {"code": 0, "data": {"valid": True}}
    try:
        await _forward_to_java({
            "event_id": event_id,
            "text": f"@{user_name}: {text}" if user_name else text,
            "type": "mattermost_outgoing",
            "channel_id": channel_id,
            "trigger_word": trigger_word,
        })
    except HTTPException:
        raise
    except Exception as e:
        logger.error("[Mattermost] 入站转发失败: %s", e)
        raise HTTPException(status_code=500, detail=f"转发失败: {e}")
    return {"code": 0, "data": {"valid": True}}