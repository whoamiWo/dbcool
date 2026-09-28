"""PHASE 57: 向量嵌入服务 — 使用 sentence-transformers 生成多语言向量.

功能:
- 加载预训练的多语言模型 (paraphrase-multilingual-MiniLM-L12-v2)
- 将文本转换为 384 维向量
- 提供批量嵌入接口

部署:
- 作为独立微服务运行在 localhost:8001
- 被 ai.py 的 /api/embed 端点调用
"""
from __future__ import annotations

import asyncio
from typing import TYPE_CHECKING

from fastapi import APIRouter, FastAPI, HTTPException
from pydantic import BaseModel

if TYPE_CHECKING:
    from sentence_transformers import SentenceTransformer


app = FastAPI(title="Embedding Service", version="1.0.0")
router = APIRouter(prefix="/api")

_model: SentenceTransformer | None = None


class EmbedRequest(BaseModel):
    text: str
    model: str | None = None


class EmbedResponse(BaseModel):
    model: str
    embedding: list[float]
    tokens: int


@app.on_event("startup")
async def load_model():
    """启动时加载模型到 GPU/CPU."""
    global _model
    try:
        from sentence_transformers import SentenceTransformer
        
        # 优先使用 GPU，若无则回退 CPU
        device = "cuda" if _has_gpu() else "cpu"
        
        _model = SentenceTransformer(
            "paraphrase-multilingual-MiniLM-L12-v2",
            device=device,
        )
        print(f"[Embedding] Model loaded on {device}")
    except Exception as e:
        print(f"[Embedding] Failed to load model: {e}")
        _model = None


def _has_gpu() -> bool:
    """检测是否有可用的 GPU."""
    try:
        import torch
        return torch.cuda.is_available()
    except ImportError:
        return False


@router.post("/embed", response_model=EmbedResponse)
async def embed(req: EmbedRequest) -> EmbedResponse:
    """生成文本的向量嵌入."""
    if _model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    try:
        embeddings = _model.encode([req.text], convert_to_numpy=True)
        embedding_list = embeddings[0].tolist()
        
        return EmbedResponse(
            model=_model.model_name_or_path,
            embedding=embedding_list,
            tokens=len(req.text) // 4,
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health() -> dict:
    """健康检查."""
    return {
        "status": "healthy",
        "model_loaded": _model is not None,
        "device": "cuda" if _has_gpu() else "cpu",
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001)
