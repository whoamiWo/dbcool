"""Pytest fixtures for R11 tests."""

import os

import pytest

# CI 专用密钥（与 Java 侧 application-test.properties 的 ci_jwt_secret 对齐）。
_CI_JWT_SECRET = "ci_jwt_secret_at_least_32_chars_for_hs256_algorithm"


def pytest_configure(config):
    """Early environment setup before any test collection.

    This hook runs before test collection starts, ensuring that Settings
    can be instantiated during import time without validation errors.
    See PHASE114 security fix: JWT_SECRET must be provided via env var.
    """
    # Inject test-only environment variables BEFORE any module import
    if not os.environ.get("JWT_SECRET"):
        os.environ["JWT_SECRET"] = _CI_JWT_SECRET
    if not os.environ.get("MINIO_ROOT_PASSWORD"):
        os.environ["MINIO_ROOT_PASSWORD"] = "ci_minio_root_password_for_tests"


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
