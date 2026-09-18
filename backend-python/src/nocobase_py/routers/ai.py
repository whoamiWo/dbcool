"""AI 增强端点 — R11 LLM 成本控制(限流 + 缓存 + 配额)."""

from fastapi import APIRouter, Depends, Request, WebSocket, WebSocketDisconnect, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from nocobase_py.config import get_settings
from nocobase_py.middleware.rate_limit import SlidingWindowRateLimiter
from nocobase_py.security import AuthUser, get_current_user
from nocobase_py.services.llm_cache import LLMCache
from nocobase_py.services.quota import QuotaService

router = APIRouter(prefix="/api/ai", tags=["ai"])


def _build_routers() -> tuple[SlidingWindowRateLimiter, LLMCache, QuotaService]:
    """根据 Settings 动态构造 LLM 三层防护实例.

    支持热更新:依赖覆盖时(如环境变量)启动时即生效.
    """
    s = get_settings()
    limiter = SlidingWindowRateLimiter(
        max_requests=s.llm_rate_limit_max_requests,
        window_seconds=s.llm_rate_limit_window_seconds,
    )
    cache = LLMCache(
        max_size=s.llm_cache_max_size,
        ttl_seconds=s.llm_cache_ttl_seconds,
    )
    quota = QuotaService(
        daily_call_limit=s.llm_daily_call_limit,
        daily_token_limit=s.llm_daily_token_limit,
        monthly_call_limit=s.llm_monthly_call_limit,
        monthly_token_limit=s.llm_monthly_token_limit,
    )
    return limiter, cache, quota


_rate_limiter, _llm_cache, _quota = _build_routers()


class EchoData(BaseModel):
    """Echo 响应 data."""

    echo: str
    user_id: str
    username: str
    tenant_id: str
    service: str = "nocobase-py"


class ApiResponse(BaseModel):
    """统一响应."""

    code: int = 0
    message: str = "success"
    data: EchoData | dict


class ChatRequest(BaseModel):
    """LLM 聊天请求(简化)."""

    model: str = "gpt-4"
    prompt: str
    max_tokens: int = 1024


class ChatResponse(BaseModel):
    """LLM 聊天响应."""

    model: str
    prompt: str
    response: str
    cached: bool
    tokens_used: int
    quota: dict


@router.get("/echo", response_model=ApiResponse)
async def echo(
    msg: str = "hello",
    user: AuthUser = Depends(get_current_user),
) -> ApiResponse:
    """Echo 端点:校验 JWT + 返回 echo."""
    return ApiResponse(
        data=EchoData(
            echo=msg,
            user_id=user.user_id,
            username=user.username,
            tenant_id=user.tenant_id,
        )
    )


@router.get("/whoami", response_model=ApiResponse)
async def whoami(user: AuthUser = Depends(get_current_user)) -> ApiResponse:
    """返回当前 JWT 用户信息(给前端调试用)."""
    return ApiResponse(
        data=EchoData(
            echo="whoami",
            user_id=user.user_id,
            username=user.username,
            tenant_id=user.tenant_id,
        )
    )


@router.get("/quota")
async def quota_info(user: AuthUser = Depends(get_current_user)) -> dict:
    """R11: 查看当前用户 LLM 配额剩余."""
    return {"user_id": user.user_id, "remaining": _quota.remaining(user.user_id)}


@router.get("/cache/stats")
async def cache_stats(user: AuthUser = Depends(get_current_user)) -> dict:
    """R11: 查看缓存统计(管理员用途)."""
    stats = _llm_cache.stats()
    stats["hit_rate_warning"] = _llm_cache.should_warn_low_hit_rate()
    return stats


@router.get("/webhook/stats")
async def webhook_stats(user: AuthUser = Depends(get_current_user)) -> dict:
    """R11: 查看 webhook 推送统计."""
    try:
        from nocobase_py.services.webhook import _pusher as installed_pusher

        if installed_pusher is None:
            return {"configured": 0, "note": "pusher not installed"}
        return installed_pusher.stats()
    except Exception as e:  # noqa: BLE001
        return {"error": str(e)}


@router.get("/alerts/recent")
async def alerts_recent(
    user: AuthUser = Depends(get_current_user),
    limit: int = 50,
    include_resolved: bool = True,
) -> dict:
    """R11: 查询最近告警事件(限流/配额/缓存命中率异常)."""
    try:
        from nocobase_py.services.alerts import get_collector

        events = get_collector().recent(limit=limit, include_resolved=include_resolved)
        counts = get_collector().count_by_kind(include_resolved=include_resolved)
        unresolved = get_collector().unresolved_count()
        return {
            "events": events,
            "counts_by_kind": counts,
            "unresolved_count": unresolved,
        }
    except Exception as e:  # noqa: BLE001
        return {"events": [], "counts_by_kind": {}, "unresolved_count": 0, "error": str(e)}


class AlertAction(BaseModel):
    """告警动作请求体."""

    by: str | None = None


@router.post("/alerts/{event_id}/ack")
async def alerts_ack(event_id: str, body: AlertAction | None = None) -> dict:
    """R11: 标记告警已确认."""
    from nocobase_py.services.alerts import get_collector

    by = body.by if body else None
    ok = get_collector().ack(event_id, by=by)
    return {"ok": ok, "event_id": event_id}


@router.post("/alerts/{event_id}/resolve")
async def alerts_resolve(event_id: str) -> dict:
    """R11: 标记告警已解决."""
    from nocobase_py.services.alerts import get_collector

    ok = get_collector().resolve(event_id)
    return {"ok": ok, "event_id": event_id}


class SubscriptionRequest(BaseModel):
    """订阅请求体."""

    user_id: str
    kind: str


@router.post("/alerts/subscriptions")
async def alerts_subscribe(body: SubscriptionRequest) -> dict:
    """R11: 订阅某种告警(按 kind).返回是否新增."""
    from nocobase_py.services.alert_store import get_store

    created = get_store().subscribe(body.user_id, body.kind)
    return {"ok": True, "created": created, "user_id": body.user_id, "kind": body.kind}


@router.delete("/alerts/subscriptions")
async def alerts_unsubscribe(user_id: str, kind: str) -> dict:
    """R11: 取消订阅."""
    from nocobase_py.services.alert_store import get_store

    removed = get_store().unsubscribe(user_id, kind)
    return {"ok": removed, "user_id": user_id, "kind": kind}


@router.get("/alerts/subscriptions/{user_id}")
async def alerts_list_subscriptions(user_id: str) -> dict:
    """R11: 查询某用户的所有订阅."""
    from nocobase_py.services.alert_store import get_store

    return {"user_id": user_id, "kinds": get_store().subscriptions_for(user_id)}


@router.get("/store/stats")
async def store_stats(user: AuthUser = Depends(get_current_user)) -> dict:
    """R11: 持久化存储统计."""
    try:
        from nocobase_py.services.alert_store import get_store

        s = get_store()
        return {
            "path": str(s.path),
            "max_alerts": s.max_alerts,
            "unresolved": s.unresolved_count(),
            "by_kind": s.count_alerts(include_resolved=True),
            "by_kind_unresolved": s.count_alerts(include_resolved=False),
        }
    except Exception as e:  # noqa: BLE001
        return {"error": str(e)}


async def _invoke_llm(
    model: str,
    prompt: str,
    max_tokens: int,
    user_id: str,
) -> tuple[str, int]:
    """调用 LLM,返回 (响应文本, 消耗 token 数).

    - 配置了 llm_api_key + llm_base_url → 真实调用(OpenAI 兼容 /chat/completions);
    - 未配置 → 回退 simulated 响应(便于无密钥环境联调与既有测试)。

    真实调用失败时异常向上抛,由调用方(Java AiAssistantService)降级处理。
    """
    s = get_settings()

    if not (s.llm_api_key and s.llm_base_url):
        estimated = min(max_tokens, len(prompt) // 4 + 100)
        _quota.consume(user_id, tokens=estimated)
        return f"[simulated] Model={model}, prompt_length={len(prompt)}", estimated

    import httpx  # 延迟导入:未配置 LLM 时无需该依赖参与启动

    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
    }
    headers = {
        "Authorization": f"Bearer {s.llm_api_key}",
        "Content-Type": "application/json",
    }
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(
            f"{s.llm_base_url.rstrip('/')}/chat/completions",
            json=payload,
            headers=headers,
        )
        resp.raise_for_status()
        data = resp.json()

    text = data["choices"][0]["message"]["content"]
    used = data.get("usage", {}).get("total_tokens") or min(
        max_tokens, len(prompt) // 4 + 100
    )
    _quota.consume(user_id, tokens=used)
    return text, used


@router.post("/chat", response_model=ChatResponse)
async def chat(
    req: ChatRequest,
    user: AuthUser = Depends(get_current_user),
) -> ChatResponse:
    """R11: LLM 聊天端点 — 三层防护.

    1. 限流:60s 内每用户最多 N 次 → 429(N 可配,默认 30)
    2. 缓存:相同 model+prompt 命中 → 节省 API 成本
    3. 配额:每日 100 次 / 50K token → 429(可配)
    """
    # 1. 限流
    _rate_limiter.consume(f"llm:{user.user_id}")

    # 2. 缓存检查
    cached = _llm_cache.get(model=req.model, prompt=req.prompt)
    if cached is not None:
        return ChatResponse(
            model=req.model,
            prompt=req.prompt,
            response=cached,
            cached=True,
            tokens_used=0,
            quota=_quota.remaining(user.user_id),
        )

    # 3. 调用 LLM(真实模型或 simulated 回退; 消耗 token 由 _invoke_llm 记账)
    response_text, tokens_used = await _invoke_llm(
        model=req.model,
        prompt=req.prompt,
        max_tokens=req.max_tokens,
        user_id=user.user_id,
    )
    _llm_cache.put(model=req.model, prompt=req.prompt, response=response_text)

    return ChatResponse(
        model=req.model,
        prompt=req.prompt,
        response=response_text,
        cached=False,
        tokens_used=tokens_used,
        quota=_quota.remaining(user.user_id),
    )


# ── R11 WebSocket 实时告警推送 ────────────────────────────────────
@router.websocket("/ws/alerts")
async def alerts_ws(ws: WebSocket, user_id: str | None = None) -> None:
    """WebSocket 端点:客户端连接后,服务端自动推送告警事件.

    协议:
    - 连接 URL 支持 ?user_id=xxx(用于按订阅路由);
    - 连接后服务端立刻发 `{"type": "hello", "user_id": "...", "msg": "connected"}`
    - 每条告警事件:`{"type": "alert", "id": "...", "kind": "...", ...}`
    - 客户端可发 `{"type": "ping"}`,服务端回 `{"type": "pong"}`

    路由策略:无 user_id 的连接(管理员/默认)会收到所有告警;
    有 user_id 的连接只收到其订阅 kind 的告警.
    """
    import json

    from nocobase_py.services.alert_ws import get_broadcaster

    broadcaster = get_broadcaster()
    await broadcaster.connect(ws, user_id=user_id)
    try:
        await ws.send_json({"type": "hello", "user_id": user_id, "msg": "connected"})
        while True:
            # 接收客户端消息(主要是 ping);断开时 WebSocketDisconnect 抛出
            data = await ws.receive_text()
            try:
                msg = json.loads(data)
            except Exception:
                continue
            if msg.get("type") == "ping":
                await ws.send_json({"type": "pong"})
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        await broadcaster.disconnect(ws)


@router.get("/ws/stats")
async def ws_stats(user: AuthUser = Depends(get_current_user)) -> dict:
    """R11: WebSocket 连接统计."""
    try:
        from nocobase_py.services.alert_ws import get_broadcaster

        return {"connections": get_broadcaster().connection_count()}
    except Exception as e:  # noqa: BLE001
        return {"error": str(e)}
