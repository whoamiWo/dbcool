"""DB session 依赖注入 — 内存版 Settings 单例。

<p>Phase52 收口遗留:SQLAlchemy/asyncpg ORM 集成缺失。
本次端到端冒烟期间用内存 Settings 简化实现,提供 FastAPI Depends 接口。

未来接 PostgreSQL 时替换 get_db_session 实现即可,接口签名不变。
"""

from __future__ import annotations

from collections.abc import AsyncIterator

from nocobase_py.models.settings import Settings

# 模块级单例(进程内),所有请求共享同一 Settings 实例
_settings_singleton: Settings | None = None


def _get_singleton() -> Settings:
    """惰性初始化 Settings 单例(避免模块加载时立即创建)。"""
    global _settings_singleton
    if _settings_singleton is None:
        _settings_singleton = Settings()
    return _settings_singleton


async def get_db_session() -> AsyncIterator[Settings]:
    """FastAPI Depends 依赖:返回 Settings 单例。

    用法:
        @router.post("/xxx")
        async def handler(session: Annotated[Settings, Depends(get_db_session)]):
            config = session.get("connector.wecom.config", {})

    Returns:
        Settings 单例(进程内共享)
    """
    yield _get_singleton()


def reset_singleton() -> None:
    """重置单例(测试用)。"""
    global _settings_singleton
    _settings_singleton = None