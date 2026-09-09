# NocoBase Python 后端

> FastAPI 0.115 + Python 3.12 + Pydantic v2

## 本地启动

```bash
# 1. 安装依赖
uv sync

# 2. 启动
uv run uvicorn nocobase_py.main:app --reload --port 8000

# 3. 验证
curl http://localhost:8000/api/health
```

或用 Makefile:

```bash
make python   # 启动 Python
```

## 测试

```bash
uv run pytest                # 全部
uv run pytest -v             # 详细
uv run pytest --cov=src      # 覆盖率
```

## 代码质量

```bash
uv run ruff check .          # lint
uv run ruff format .         # 格式化
uv run mypy .                # 类型检查
```

## 模块结构

```
src/nocobase_py/
├── __init__.py
├── main.py             # FastAPI 入口
├── config.py           # 配置(Settings)
└── routers/
    ├── __init__.py
    ├── health.py       # 健康检查
    └── ai.py           # AI 增强(Week 3 脚手架)
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `HOST` | 0.0.0.0 | 绑定地址 |
| `PORT` | 8000 | 服务端口 |
| `JAVA_BACKEND_URL` | http://localhost:8080 | Java 后端地址 |
| `JWT_SECRET` | (32+字符) | JWT 共享密钥 |
| `REDIS_HOST` | localhost | Redis 地址 |
| `REDIS_PORT` | 6379 | Redis 端口 |
