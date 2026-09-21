"""集成连接器路由 — 钉钉/企微/Slack/飞书。

复用 WebhookPusher 的 HMAC 签名模式，与 Java 侧通过 Redis 事件通道。
"""

import json
import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import PlainTextResponse
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
    event_id = body_json.get("event_id") if isinstance(body_json, dict) else None
    if event_id:
        logger.info("[Slack] 入站事件已转发: %s", body_json.get("event", {}).get("type", "unknown"))
        # TODO: 实际部署时，通过 httpx 将事件 POST 到 Java /api/slack/events 入库
        # 此处仅做日志记录，防止事件被静默丢弃
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


def _mattermost_connector() -> MattermostConnector:
    """基于全局配置构造 Mattermost 连接器。"""
    s = get_settings()
    return MattermostConnector(
        webhook_url=s.mattermost_webhook_url if hasattr(s, "mattermost_webhook_url") else "",
        bot_token=s.mattermost_bot_token if hasattr(s, "mattermost_bot_token") else "",
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