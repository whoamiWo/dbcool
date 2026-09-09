"""FastAPI 应用入口."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from nocobase_py import __version__
from nocobase_py.config import get_settings
from nocobase_py.routers import ai, health


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """应用生命周期."""
    settings = get_settings()
    print(f"🚀 {settings.app_name} v{__version__} 启动")
    print(f"   Java 后端: {settings.java_backend_url}")
    yield
    print(f"🛑 {settings.app_name} 关闭")


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
