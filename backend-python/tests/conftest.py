"""Pytest fixtures for R11 tests."""

import pytest

# JWT 测试专用密钥（与 Java 侧 application-test.properties 的 ci_jwt_secret 对齐）。
# 移除 config.py 的 dev 默认值后，测试环境必须由 conftest 显式注入，避免依赖公开密钥。
_CI_JWT_SECRET = "ci_jwt_secret_at_least_32_chars_for_hs256_algorithm"


@pytest.fixture(autouse=True)
def _set_jwt_secret_for_tests(monkeypatch):
    """为所有测试注入测试专用 JWT_SECRET（生产密钥不得进入测试上下文）。"""
    monkeypatch.setenv("JWT_SECRET", _CI_JWT_SECRET)
    # PHASE114: 备份服务 MinIO 凭据 —— 即使 MINIO_ENABLED 未开，提前设值可避免
    # 未来测试意外启用 MinIO 时回落到弱口令（已移除）。
    monkeypatch.setenv("MINIO_ROOT_PASSWORD", "ci_minio_root_password_for_tests")
    return


@pytest.fixture(autouse=True)
async def clear_redis_before_each_test(monkeypatch):
    """Clear Redis before each test to ensure isolation by using memory mode."""
    from nocobase_py import redis_client as rc

    # Force memory mode for tests by setting env var
    monkeypatch.setenv("REDIS_ENABLED", "false")

    # Reset the singleton caches
    from nocobase_py.config import get_settings
    get_settings.cache_clear()

    # Reset the redis client singleton
    rc._redis = None
    rc._memory = None

    yield

    # Cleanup after test
    rc._redis = None
    rc._memory = None
    get_settings.cache_clear()
