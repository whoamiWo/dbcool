# ============================================================
#  NocoBase Python 后端 — 多阶段构建
# ============================================================

FROM python:3.12-slim AS base

# PHASE 57: 是否安装真实语义向量依赖(sentence-transformers / torch)。
# 默认 false —— torch 体积大(~800MB)，未安装时 /api/ai/embedding 自动回退
# 确定性哈希向量(接口仍可用)。需要真语义检索时:
#   docker build --build-arg INSTALL_SEMANTIC=true ...
# 代码侧为可插拔设计，安装后自动优先使用真模型，无需改代码。
ARG INSTALL_SEMANTIC=false

# 安装 uv
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app

# 安装依赖(利用 Docker 缓存)
COPY pyproject.toml README.md ./
RUN if [ "$INSTALL_SEMANTIC" = "true" ]; then \
        uv sync --no-install-project --no-dev --extra semantic; \
    else \
        uv sync --no-install-project --no-dev; \
    fi

# 复制源码
COPY src ./src
RUN if [ "$INSTALL_SEMANTIC" = "true" ]; then \
        uv sync --no-dev --extra semantic; \
    else \
        uv sync --no-dev; \
    fi

# 运行时
FROM python:3.12-slim
WORKDIR /app

# 安装 curl 用于健康检查
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# 复制 venv 与源码
COPY --from=base /app /app

# 切到 venv
ENV PATH="/app/.venv/bin:$PATH"

# 非 root
RUN useradd -m -u 1000 nocobase
USER nocobase

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8000/api/health || exit 1

CMD ["uvicorn", "nocobase_py.main:app", "--host", "0.0.0.0", "--port", "8000"]
