"""FastAPI 应用入口."""

import logging
import os
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from contextvars import ContextVar

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware

from nocobase_py import __version__
from nocobase_py.config import get_settings
from nocobase_py.routers import ai, alerts, backup, connectors, health, integration, tasks

logger = logging.getLogger("nocobase_py")

# PHASE100 T5: traceId 上下文（供日志格式器输出 request_id）
_trace_id_var: ContextVar[str] = ContextVar("trace_id", default="")


def get_trace_id() -> str:
    """当前请求的 traceId（无请求上下文时返回空串）。"""
    return _trace_id_var.get()


def setup_logging() -> None:
    """PHASE100 T5: 结构化日志 —— 每条日志自动带上当前请求的 traceId。

    默认文本格式 `[req:<traceId>] message`；设 LOG_JSON=1 时输出 JSON。
    """
    use_json = os.getenv("LOG_JSON", "").lower() in ("1", "true", "yes")

    class TraceIdFormatter(logging.Formatter):
        def format(self, record: logging.LogRecord) -> str:
            trace_id = _trace_id_var.get()
            if not use_json:
                msg = super().format(record)
                return f"[req:{trace_id}] {msg}" if trace_id else msg
            import json

            data = {
                "level": record.levelname,
                "logger": record.name,
                "message": record.getMessage(),
                "request_id": trace_id or None,
            }
            if record.exc_info:
                data["exception"] = self.formatException(record.exc_info)
            return json.dumps(data, ensure_ascii=False)

    root = logging.getLogger()
    for h in list(root.handlers):
        root.removeHandler(h)
    handler = logging.StreamHandler()
    handler.setFormatter(TraceIdFormatter())
    root.addHandler(handler)
    root.setLevel(logging.INFO)
    # uvicorn 自带访问日志，降噪到 WARNING
    logging.getLogger("uvicorn").setLevel(logging.WARNING)


setup_logging()


async def trace_id_middleware(request: Request, call_next):
    """PHASE100 T5: traceId 贯通 —— 沿用上游 X-Request-ID，否则生成；响应头回写."""
    trace_id = request.headers.get("X-Request-ID") or uuid.uuid4().hex[:16]
    request.state.trace_id = trace_id
    token = _trace_id_var.set(trace_id)
    try:
        response = await call_next(request)
        response.headers["X-Request-ID"] = trace_id
        return response
    finally:
        _trace_id_var.reset(token)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """应用生命周期."""
    settings = get_settings()
    print(f"🚀 {settings.app_name} v{__version__} 启动")
    print(f"   Java 后端: {settings.java_backend_url}")

    # W6: 安装生产化闭环任务（告警巡检 + 定时备份 + 租户配额清理）
    try:
        from nocobase_py.services import backup as backup_service
        from nocobase_py.services.llm_cache import LLMCache
        from nocobase_py.services.scheduler import get_scheduler, install_default_alert_checks

        # PHASE100 T6: 告警持久化 —— 初始化全局 AlertStore 并 attach 到 collector。
        # 此前 get_store() 无人调用，告警只进内存 deque，重启即丢。
        try:
            from nocobase_py.services.alert_store import get_store

            alerts_path = os.environ.get("NOCOBASE_ALERTS_PATH", "alerts.db")
            get_store(path=alerts_path)
            print(f"   PHASE100 T6: 告警 SQLite 持久化已启用 ({alerts_path})")
        except Exception as e:  # noqa: BLE001
            print(f"   ⚠ PHASE100 T6 告警持久化初始化失败: {e}")

        # PHASE100 T6: 告警 webhook 外发通道（安装/备份失败等可真实送达）
        # 未配置 ALERT_WEBHOOK_URL 时不启用，绝不静默丢弃 —— 启动日志明确提示。
        alert_webhook_url = os.environ.get("ALERT_WEBHOOK_URL", "")
        alert_webhook_kind = os.environ.get("ALERT_WEBHOOK_KIND", "generic")
        alert_webhook_secret = os.environ.get("ALERT_WEBHOOK_SECRET", "")
        if alert_webhook_url:
            try:
                from nocobase_py.services.webhook import (
                    WebhookConfig,
                    WebhookPusher,
                    install_pusher,
                )

                pusher = WebhookPusher(WebhookConfig(
                    url=alert_webhook_url,
                    kind=alert_webhook_kind,
                    secret=alert_webhook_secret,
                    enabled=True,
                ))
                install_pusher(pusher)
                print(f"   PHASE100 T6: 告警 webhook 已启用 → {alert_webhook_kind} (secret={'on' if alert_webhook_secret else 'off'})")
            except Exception as e:  # noqa: BLE001
                print(f"   ⚠ PHASE100 T6 告警 webhook 安装失败: {e}")
        else:
            print("   ⚠ PHASE100 T6: 未配置 ALERT_WEBHOOK_URL，告警仅落盘不外发（备份失败/服务不可用告警将无接收方）")

        sched = get_scheduler()
        # 1. 告警巡检（缓存命中率）
        install_default_alert_checks(lambda: LLMCache(), interval=60.0)
        # 2. 定时备份（BACKUP_CRON 环境变量，默认每天 02:00）
        backup_cron = os.environ.get("BACKUP_CRON", "0 2 * * *")
        sched.cron(backup_cron, backup_service.run_scheduled_backup, name="backup.scheduled")
        # 3. 租户配额每日重置（每天 00:00 UTC）
        sched.cron("0 0 * * *", _reset_quotas, name="quota.daily_reset")
        # 启动调度器守护线程（此前未 start 导致任务永不执行）
        sched.start()
        print(f"   W6 生产化闭环已安装: 告警巡检/备份({backup_cron})/配额重置")
    except Exception as e:
        print(f"   ⚠ W6 闭环安装失败: {e}")

    yield
    print(f"🛑 {settings.app_name} 关闭")


def _reset_quotas() -> None:
    """每日重置所有租户配额（调用 QuotaService.reset_daily）。"""
    try:
        import asyncio

        from nocobase_py.redis_client import get_redis
        from nocobase_py.services.quota import QuotaService

        async def _do():
            redis = await get_redis()
            svc = QuotaService()
            # 遍历所有 daily key，重置（key 格式: llm_quota:<user_id>:daily:<date>）
            keys = await redis.keys("llm_quota:*:daily:*")
            for k in keys:
                user_id = k.split(":")[1]
                await svc.reset_daily(user_id)
            logger.info("[quota] 重置 %d 个用户日配额", len(keys))

        asyncio.run(_do())
    except Exception:  # noqa: BLE001
        import logging
        logging.getLogger(__name__).warning("[quota] 重置失败:\n%s", __import__("traceback").format_exc())


def create_app() -> FastAPI:
    """创建 FastAPI 应用."""
    settings = get_settings()

    app = FastAPI(
        title="NocoBase Python Backend",
        version=__version__,
        description="AI 增强 / 集成 / 异步任务层(Week 3 脚手架)",
        lifespan=lifespan,
    )

    # CORS
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # PHASE100 T5: traceId 中间件（沿用 X-Request-ID 或生成，响应头回写）
    app.middleware("http")(trace_id_middleware)

    # 路由
    app.include_router(health.router)
    app.include_router(ai.router)
    app.include_router(backup.router)
    app.include_router(connectors.router)
    app.include_router(integration.router)
    app.include_router(tasks.router)

    # PHASE104 T7.3: AlertManager webhook 接收
    app.include_router(alerts.router)

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn

    settings = get_settings()
    uvicorn.run(
        "nocobase_py.main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug,
    )
