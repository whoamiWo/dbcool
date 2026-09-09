# ============================================================
#  NocoBase Python 后端 — 多阶段构建
# ============================================================

FROM python:3.12-slim AS base

# 安装 uv
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app

# 安装依赖(利用 Docker 缓存)
COPY pyproject.toml ./
RUN uv sync --no-install-project --no-dev

# 复制源码
COPY src ./src
RUN uv sync --no-dev

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
