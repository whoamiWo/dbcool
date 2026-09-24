"""FastAPI 应用入口."""

import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from nocobase_py import __version__
from nocobase_py.config import get_settings
from nocobase_py.routers import ai, backup, connectors, health, integration, tasks


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """应用生命周期."""
    settings = get_settings()
    print(f"🚀 {settings.app_name} v{__version__} 启动")
    print(f"   Java 后端: {settings.java_backend_url}")

    # W6: 安装生产化闭环任务（告警巡检 + 定时备份 + 租户配额清理）
    try:
        from nocobase_py.services.scheduler import get_scheduler, install_default_alert_checks
        from nocobase_py.services import backup as backup_service
        from nocobase_py.services.llm_cache import LLMCache

        sched = get_scheduler()
        # 1. 告警巡检（缓存命中率）
        install_default_alert_checks(lambda: LLMCache(), interval=60.0)
        # 2. 定时备份（BACKUP_CRON 环境变量，默认每天 02:00）
        backup_cron = os.environ.get("BACKUP_CRON", "0 2 * * *")
        sched.cron(backup_cron, backup_service.run_scheduled_backup, name="backup.scheduled")
        # 3. 租户配额每日重置（每天 00:00 UTC）
        sched.cron("0 0 * * *", _reset_quotas, name="quota.daily_reset")
        print(f"   W6 生产化闭环已安装: 告警巡检/备份({backup_cron})/配额重置")
    except Exception as e:
        print(f"   ⚠ W6 闭环安装失败: {e}")

    yield
    print(f"🛑 {settings.app_name} 关闭")


def _reset_quotas() -> None:
    """每日重置所有租户配额（调用 QuotaService.reset_daily）。"""
    try:
        import asyncio
        from nocobase_py.services.quota import QuotaService
        from nocobase_py.redis_client import get_redis

        async def _do():
            redis = await get_redis()
            svc = QuotaService()  # QuotaService 内部自己调用 get_redis()
            # 遍历所有 daily key，重置（key 格式: llm_quota:<user_id>:daily:<date>）
            keys = await redis.keys("llm_quota:*:daily:*")
            for k in keys:
                # key 格式: llm_quota:<user_id>:daily:<date>
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

    # 路由
    app.include_router(health.router)
    app.include_router(ai.router)
    app.include_router(backup.router)
    app.include_router(connectors.router)
    app.include_router(integration.router)
    app.include_router(tasks.router)

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
