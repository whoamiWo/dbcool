# ============================================================
#  NocoBase Python 后端 — 多阶段构建
# ============================================================

FROM python:3.12-slim AS base

# PHASE 57: 是否安装真实语义向量依赖(sentence-transformers / torch)。
# 默认 false —— torch 体积大（CPU-only ~200MB、CUDA ~2GB），且 huggingface 依赖
# hf-xet 偶尔超时。未安装时 /api/ai/embedding 自动回退确定性哈希向量（接口仍可用）。
# 需要真语义检索时:
#   docker build --build-arg INSTALL_SEMANTIC=true ...
# 代码侧为可插拔设计，安装后自动优先使用真模型，无需改代码。
#
# 备注：huggingface-hub v1.33+ 依赖 hf-xet，网络不稳定时会超时。若需真向量，
# 建议使用镜像站点或提前准备好 torch 包。
ARG INSTALL_SEMANTIC=false

# 安装 uv
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app

# 安装依赖 (利用 Docker 缓存)
COPY pyproject.toml README.md ./

# PHASE 57: 分步安装：先创建 venv，再装 PyTorch CPU，最后装项目依赖
# 原因：pytorch CPU 源不包含 asyncpg 等纯 Python 包，不能作为唯一 index
# 使用清华镜像源避免网络超时
ENV UV_DEFAULT_INDEX=https://pypi.tuna.tsinghua.edu.cn/simple
ENV UV_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
# 关键：构建阶段也必须设置 HF_HOME，否则模型下载到 /root/.cache，最终镜像 COPY /app 时会丢失
ENV HF_HOME=/app/.cache/huggingface
RUN if [ "$INSTALL_SEMANTIC" = "true" ]; then \
        uv venv .venv && \
        uv sync --no-dev && \
        uv pip install https://download.pytorch.org/whl/cpu/torch-2.5.1%2Bcpu-cp312-cp312-linux_x86_64.whl --upgrade && \
        uv pip install transformers huggingface-hub tokenizers scipy scikit-learn Pillow && \
        uv pip install sentence-transformers --no-deps; \
    else \
        uv venv .venv && \
        uv sync --no-dev; \
    fi

# PHASE 57: 预下载模型（必须在 src 复制之后，否则 uv run 会失败）
RUN if [ "$INSTALL_SEMANTIC" = "true" ]; then \
        export HF_ENDPOINT=https://hf-mirror.com && \
        mkdir -p /app/.cache/huggingface && \
        /app/.venv/bin/python -c "from sentence_transformers import SentenceTransformer; m=SentenceTransformer('paraphrase-multilingual-MiniLM-L12-v2', device='cpu'); print('Model loaded:', m)" && \
        chown -R 1000:1000 /app/.cache; \
    fi

# 复制源码（依赖已在上一层安装，不再 sync 以免覆盖 semantic 变体安装的 torch）
COPY src ./src

# 运行时
FROM python:3.12-slim
WORKDIR /app

# R1: 安装 postgresql-client + redis-tools；替换为清华源避免 deb.debian.org 卡死
RUN rm -f /etc/apt/sources.list.d/debian.sources /etc/apt/sources.list && \
    echo "deb http://mirrors.tuna.tsinghua.edu.cn/debian/ trixie main contrib non-free non-free-firmware" > /etc/apt/sources.list && \
    echo "deb http://mirrors.tuna.tsinghua.edu.cn/debian/ trixie-updates main contrib non-free non-free-firmware" >> /etc/apt/sources.list && \
    echo "deb http://mirrors.tuna.tsinghua.edu.cn/debian-security trixie-security main contrib non-free non-free-firmware" >> /etc/apt/sources.list && \
    apt-get update && apt-get install -y --no-install-recommends curl postgresql-client redis-tools && \
    rm -rf /var/lib/apt/lists/*

# 复制 venv 与源码
COPY --from=base /app /app

# 切到 venv
ENV PATH="/app/.venv/bin:$PATH"
# src 布局：显式暴露模块路径（semantic 分支构建时 src 尚未存在，包未安装进 venv）
ENV PYTHONPATH="/app/src"

# HuggingFace 模型缓存目录（可挂载卷加速）
ENV HF_HOME=/app/.cache/huggingface
RUN mkdir -p /app/.cache/huggingface && chown -R 1000:1000 /app/.cache

# 非 root
RUN useradd -m -u 1000 nocobase
USER nocobase

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8000/api/health || exit 1

CMD ["uvicorn", "nocobase_py.main:app", "--host", "0.0.0.0", "--port", "8000"]
