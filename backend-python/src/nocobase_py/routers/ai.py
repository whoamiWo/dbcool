"""AI 增强端点 — R11 LLM 成本控制(限流 + 缓存 + 配额)."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Request, WebSocket, WebSocketDisconnect, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from nocobase_py.config import get_settings
from nocobase_py.middleware.rate_limit import SlidingWindowRateLimiter
from nocobase_py.security import AuthUser, get_current_user, get_service_or_user
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


class EmbeddingRequest(BaseModel):
    """向量嵌入请求."""

    text: str
    tenant_id: str
    model: str | None = None


class EmbeddingResponse(BaseModel):
    """向量嵌入响应."""

    model: str
    embedding: list[float]
    tokens: int


# ============================================================
#  PHASE 57: 可插拔向量嵌入
#  - 优先使用 sentence-transformers 真语义模型；
#  - 未安装/加载失败 → 回退确定性哈希向量（保证接口始终可用）
# ============================================================

_MODEL: "SentenceTransformer | None" = None
_MODEL_LOAD_FAILED = False


def _load_sentence_model() -> "SentenceTransformer | None":
    """尝试加载 sentence-transformers 语义模型；不可用返回 None（回退哈希）。"""
    global _MODEL, _MODEL_LOAD_FAILED
    if _MODEL_LOAD_FAILED:
        return None
    if _MODEL is not None:
        return _MODEL
    try:
        from sentence_transformers import SentenceTransformer

        import numpy as np

        _MODEL = SentenceTransformer(
            "paraphrase-multilingual-MiniLM-L12-v2",
            device="cuda" if _has_gpu() else "cpu",
        )
        return _MODEL
    except Exception as e:  # noqa: BLE001
        _MODEL_LOAD_FAILED = True
        print(f"[Embedding] 语义模型不可用，回退哈希向量: {e}")
        return None


def _has_gpu() -> bool:
    """检测是否有可用的 GPU。"""
    try:
        import torch

        return torch.cuda.is_available()
    except ImportError:
        return False


def _generate_hash_embedding(text: str, dimensions: int = 768) -> list[float]:
    """Generate deterministic non-zero embedding using SHA-256 hashing."""
    import hashlib

    hash_hex = hashlib.sha256(text.encode("utf-8")).hexdigest()
    embedding: list[float] = []
    for i in range(dimensions):
        byte_pos = (i % (len(hash_hex) // 2)) * 2
        byte_val = int(hash_hex[byte_pos:byte_pos + 2], 16)
        float_val = (byte_val / 127.5) - 1.0
        embedding.append(float_val)
    return embedding


def _generate_embedding(text: str, dimensions: int = 768) -> list[float]:
    """生成向量：优先真语义模型，不可用回退哈希（保证非零）。"""
    import numpy as np

    model = _load_sentence_model()
    if model is not None:
        try:
            vec = model.encode([text], normalize_embeddings=True)[0]
            vec = np.asarray(vec, dtype=float).tolist()
            if len(vec) < dimensions:
                vec = vec + [0.0] * (dimensions - len(vec))
            elif len(vec) > dimensions:
                vec = vec[:dimensions]
            return [float(x) for x in vec]
        except Exception as e:  # noqa: BLE001
            print(f"[Embedding] 模型推理失败，回退哈希向量: {e}")
    return _generate_hash_embedding(text, dimensions)


class RAGSearchRequest(BaseModel):
    """RAG 语义搜索请求."""

    query: str
    tenant_id: str
    kb_id: str | None = None
    top_k: int = 10


class RAGResult(BaseModel):
    """RAG 搜索结果项."""

    document_id: str | None
    title: str
    snippet: str
    similarity: float


class RAGSearchResponse(BaseModel):
    """RAG 搜索响应."""

    results: list[RAGResult]
    total: int


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
    await _rate_limiter.consume(f"llm:{user.user_id}")

    # 2. 缓存检查
    cached = await _llm_cache.get(model=req.model, prompt=req.prompt)
    if cached is not None:
        return ChatResponse(
            model=req.model,
            prompt=req.prompt,
            response=cached,
            cached=True,
            tokens_used=0,
            quota=await _quota.remaining(user.user_id),
        )

    # 3. 调用 LLM(真实模型或 simulated 回退; 消耗 token 由 _invoke_llm 记账)
    response_text, tokens_used = await _invoke_llm(
        model=req.model,
        prompt=req.prompt,
        max_tokens=req.max_tokens,
        user_id=user.user_id,
    )
    await _llm_cache.put(model=req.model, prompt=req.prompt, response=response_text)

    return ChatResponse(
        model=req.model,
        prompt=req.prompt,
        response=response_text,
        cached=False,
        tokens_used=tokens_used,
        quota=await _quota.remaining(user.user_id),
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


# ── PHASE 57: 向量嵌入 & RAG 搜索 ────────────────────────────────────


_MODEL = None
_MODEL_LOAD_FAILED = False


def _load_sentence_model():
    """尝试加载 sentence-transformers 语义模型；不可用则返回 None（回退哈希向量）。

    设计为"可插拔"：镜像若已安装 sentence-transformers（torch），自动使用真语义向量；
    未安装或加载失败则回退确定性哈希，保证 /api/ai/embedding 始终可用（不阻断业务）。
    """
    global _MODEL, _MODEL_LOAD_FAILED
    if _MODEL_LOAD_FAILED:
        return None
    if _MODEL is not None:
        return _MODEL
    try:
        from sentence_transformers import SentenceTransformer

        _MODEL = SentenceTransformer("paraphrase-multilingual-MiniLM-L12-v2")
        return _MODEL
    except Exception as e:  # noqa: BLE001
        _MODEL_LOAD_FAILED = True
        print(f"[Embedding] 语义模型不可用，回退哈希向量: {e}")
        return None


def _generate_embedding(text: str, dimensions: int = 768) -> list[float]:
    """生成向量：优先真语义模型，不可用回退哈希（保证非零）。"""
    model = _load_sentence_model()
    if model is not None:
        try:
            import numpy as np

            vec = model.encode([text], normalize_embeddings=True)[0]
            vec = np.asarray(vec, dtype=float).tolist()
            # 对齐维度：不足补 0，超出截断（pgvector 列固定 768 维）
            if len(vec) < dimensions:
                vec = vec + [0.0] * (dimensions - len(vec))
            elif len(vec) > dimensions:
                vec = vec[:dimensions]
            return [float(x) for x in vec]
        except Exception as e:  # noqa: BLE001
            print(f"[Embedding] 模型推理失败，回退哈希向量: {e}")
    return _generate_hash_embedding(text, dimensions)


def _generate_hash_embedding(text: str, dimensions: int = 768) -> list[float]:
    """Generate deterministic non-zero embedding using SHA-256 hashing.
    
    This produces a 768-dimension vector with values in [-1, 1] range.
    The same input text always produces the same output vector.
    Different texts produce different vectors.
    
    Note: This is a lightweight alternative to sentence-transformers
    that doesn't require heavy ML dependencies (torch, transformers).
    For production use with real semantic similarity, install sentence-transformers.
    """
    import hashlib
    
    embedding = []
    # Generate enough hash bytes for all dimensions (need 2 floats per byte pair for -1 to 1 range)
    bytes_needed = dimensions * 2
    hash_hex = hashlib.sha256(text.encode("utf-8")).hexdigest()
    
    # Extend hash if text is short to ensure variability
    extended_text = text + hash_hex[:16]
    full_hash = hashlib.sha256(extended_text.encode("utf-8")).hexdigest()
    
    # Convert hex pairs to float values in [-1, 1]
    for i in range(dimensions):
        byte_pos = (i % (len(full_hash) // 2)) * 2
        byte_val = int(full_hash[byte_pos:byte_pos + 2], 16)
        # Normalize to [-1, 1]
        float_val = (byte_val / 127.5) - 1.0
        embedding.append(float_val)
    
    return embedding


@router.post("/embedding", response_model=EmbeddingResponse)
async def embedding(
    req: EmbeddingRequest,
    user: AuthUser = Depends(get_service_or_user),
) -> EmbeddingResponse:
    """生成文本的向量嵌入 (768 维).

    - 认证: 服务间 token(`internal_service_token`) 或 用户 JWT,二者必备其一
      (此前为调试临时跳过认证,已恢复 —— 无凭据调用将返回 401)
    - 优先使用 sentence-transformers 生成真实语义向量（若镜像已安装）
    - 未安装/推理失败时回退确定性哈希向量（保证接口始终可用）
    """
    embedding = _generate_embedding(req.text, 768)

    model_name = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2" \
        if _MODEL is not None else "hash-sha256-deterministic"

    return EmbeddingResponse(
        model=model_name,
        embedding=embedding,
        tokens=len(req.text) // 4 + 100,
    )


@router.post("/rag/search", response_model=RAGSearchResponse)
async def rag_search(
    req: RAGSearchRequest,
    user: AuthUser = Depends(get_current_user),
) -> RAGSearchResponse:
    """RAG 语义搜索 — 委托给 Java 后端执行 pgvector 查询.

    Python 层无 PostgreSQL 连接，仅生成向量后由 Java 侧执行混合检索。
    此端点返回空结果，实际检索由 Java WikiEmbeddingService.hybridSearch 完成。
    """
    # 生成向量（验证 embedding 端点可用）
    embedding_resp = await embedding(
        EmbeddingRequest(
            text=req.query,
            tenant_id=req.tenant_id,
            model="multilingual-minilm",
        ),
        user=user,
    )

    if all(v == 0.0 for v in embedding_resp.embedding):
        return RAGSearchResponse(results=[], total=0)

    # 向量已生成，Java 侧将使用此向量执行 pgvector 查询
    # 此处返回空结果，实际搜索由 Java WikiSearchService 完成
    return RAGSearchResponse(results=[], total=0)


async def _keyword_fallback_search(session, req: RAGSearchRequest) -> RAGSearchResponse:
    """向量不可用时的关键字 fallback（配合 PostgreSQL FTS）。"""
    from sqlalchemy import text

    sql = text(
        """
        SELECT
            w.id,
            w.title,
            w.content,
            0.0 AS similarity
        FROM wiki_page w
        WHERE w.tenant_id = :tenant_id
          AND w.content ILIKE :pattern
        ORDER BY w.updated_at DESC
        LIMIT :top_k
        """
    )
    pattern = f"%{req.query}%"
    result = await session.execute(sql, {"tenant_id": req.tenant_id, "pattern": pattern, "top_k": req.top_k})
    rows = result.fetchall()
    rag_results = [
        RAGResult(
            document_id=str(row[0]),
            title=row[1],
            snippet=(row[2][:200] + "...") if len(row[2] or "") > 200 else (row[2] or ""),
            similarity=0.0,
        )
        for row in rows
    ]
    return RAGSearchResponse(results=rag_results, total=len(rag_results))
