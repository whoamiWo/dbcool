"""Pytest fixtures for R11 tests."""

import pytest


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
