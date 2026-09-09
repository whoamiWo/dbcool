"""健康检查 + JWT 鉴权测试."""

import time
from typing import Any

import pytest
from fastapi.testclient import TestClient
from jose import jwt

from nocobase_py.config import get_settings
from nocobase_py.main import create_app


@pytest.fixture
def client() -> TestClient:
    """FastAPI 测试客户端."""
    return TestClient(create_app())


def _issue_test_token(user_id: str = "u-1", username: str = "alice", tenant_id: str = "t-1") -> str:
    """签发测试用的 JWT(模拟 Java 端的 token 格式)."""
    settings = get_settings()
    payload: dict[str, Any] = {
        "sub": user_id,
        "username": username,
        "tid": tenant_id,
        "typ": "access",
        "iat": int(time.time()),
        "exp": int(time.time()) + 600,
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


# ============================================================
#  /api/health(公开)
# ============================================================

def test_health_returns_ok(client: TestClient) -> None:
    """GET /api/health 返回 ok,无需鉴权."""
    response = client.get("/api/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["service"] == "nocobase-py"


# ============================================================
#  /api/ai/echo(需 JWT)
# ============================================================

def test_ai_echo_without_token_returns_401(client: TestClient) -> None:
    """GET /api/ai/echo 无 token 返回 401."""
    response = client.get("/api/ai/echo?msg=hello")
    assert response.status_code == 401
    assert "Authorization" in response.json()["detail"]


def test_ai_echo_with_invalid_token_returns_401(client: TestClient) -> None:
    """GET /api/ai/echo 非法 token 返回 401."""
    response = client.get(
        "/api/ai/echo?msg=hello",
        headers={"Authorization": "Bearer invalid.token.here"},
    )
    assert response.status_code == 401


def test_ai_echo_with_valid_token(client: TestClient) -> None:
    """GET /api/ai/echo 有效 token 返回 echo + 用户信息."""
    token = _issue_test_token("u-42", "bob", "tenant_xyz")
    response = client.get(
        "/api/ai/echo?msg=hello",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 0
    assert data["data"]["echo"] == "hello"
    assert data["data"]["user_id"] == "u-42"
    assert data["data"]["username"] == "bob"
    assert data["data"]["tenant_id"] == "tenant_xyz"


def test_ai_echo_with_refresh_token_type_rejected(client: TestClient) -> None:
    """refresh token(typ=refresh)不能用于 API 调用."""
    settings = get_settings()
    payload = {
        "sub": "u-1",
        "typ": "refresh",  # 错误类型
        "exp": int(time.time()) + 600,
    }
    token = jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)
    response = client.get(
        "/api/ai/echo?msg=hello",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 401


def test_ai_whoami_returns_user_info(client: TestClient) -> None:
    """GET /api/ai/whoami 返回 JWT 用户信息."""
    token = _issue_test_token("u-99", "charlie", "tenant_abc")
    response = client.get(
        "/api/ai/whoami",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["data"]["user_id"] == "u-99"
    assert data["data"]["username"] == "charlie"
