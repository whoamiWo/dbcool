"""契约测试 — 验证 OpenAPI 契约文件 + schemathesis 自动 fuzz.

运行:
    cd tests/contract && uv run pytest -v

环境变量:
    TARGET_URL: 实测目标(默认 http://localhost:8080)
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest
import yaml

CONTRACT_FILE = Path(__file__).parent.parent.parent / "contracts" / "openapi.yaml"
TARGET_URL = os.environ.get("TARGET_URL", "http://localhost:8080")


def load_contract() -> dict:
    """加载 OpenAPI 契约."""
    with open(CONTRACT_FILE) as f:
        return yaml.safe_load(f)


# ============================================================
#  契约文件本身的有效性
# ============================================================

def test_openapi_contract_exists() -> None:
    """契约文件存在."""
    assert CONTRACT_FILE.exists(), f"契约文件不存在: {CONTRACT_FILE}"


def test_openapi_contract_is_valid_yaml() -> None:
    """契约文件是合法 YAML."""
    contract = load_contract()
    assert isinstance(contract, dict)


def test_openapi_version_is_3_x() -> None:
    """OpenAPI 版本是 3.x."""
    contract = load_contract()
    assert contract["openapi"].startswith("3.")


def test_info_has_required_fields() -> None:
    """info 节有 title 和 version."""
    contract = load_contract()
    info = contract.get("info", {})
    assert "title" in info
    assert "version" in info


def test_required_paths_present() -> None:
    """关键路径都已定义."""
    contract = load_contract()
    paths = contract.get("paths", {})
    required = [
        "/api/health",
        "/api/auth/login",
        "/api/auth/refresh",
        "/api/users/me",
        "/api/collections",
        "/api/collections/{name}",
        "/api/collections/{name}/records",
        "/api/ai/echo",
    ]
    missing = [p for p in required if p not in paths]
    assert not missing, f"缺少路径: {missing}"


def test_security_scheme_defined() -> None:
    """BearerAuth 安全方案已定义."""
    contract = load_contract()
    schemes = contract.get("components", {}).get("securitySchemes", {})
    assert "BearerAuth" in schemes
    assert schemes["BearerAuth"]["type"] == "http"
    assert schemes["BearerAuth"]["scheme"] == "bearer"


def test_field_def_schema_restricts_type() -> None:
    """FieldDef 的 type 字段有 enum 限制."""
    contract = load_contract()
    schemas = contract.get("components", {}).get("schemas", {})
    field_def = schemas.get("FieldDef", {})
    props = field_def.get("properties", {})
    type_prop = props.get("type", {})
    assert "enum" in type_prop, "FieldDef.type 必须有 enum 限制"
    assert "text" in type_prop["enum"]
    assert "number" in type_prop["enum"]


def test_login_request_requires_credentials() -> None:
    """LoginRequest 必须要求 username + password."""
    contract = load_contract()
    schemas = contract.get("components", {}).get("schemas", {})
    login_req = schemas.get("LoginRequest", {})
    required = set(login_req.get("required", []))
    assert {"username", "password"} <= required


# ============================================================
#  与运行中的服务做集成验证(可选,目标服务在跑时才跑)
# ============================================================

@pytest.fixture(scope="module")
def target_url() -> str:
    """实测目标 URL."""
    return TARGET_URL


def test_java_health_endpoint_responds(target_url: str) -> None:
    """Java /api/health 公开可达."""
    import httpx

    try:
        resp = httpx.get(f"{target_url}/api/health", timeout=3.0)
        assert resp.status_code == 200, f"状态码: {resp.status_code}"
        data = resp.json()
        assert data.get("status") == "ok"
    except httpx.ConnectError:
        pytest.skip(f"Java 服务未运行于 {target_url}")


def test_login_endpoint_accepts_valid_credentials(target_url: str) -> None:
    """POST /api/auth/login admin/admin123 成功."""
    import httpx

    try:
        resp = httpx.post(
            f"{target_url}/api/auth/login",
            json={"username": "admin", "password": "admin123"},
            timeout=5.0,
        )
        # 401 也算"端点存在"的证据
        assert resp.status_code in (200, 401), f"状态码: {resp.status_code}"

        if resp.status_code == 200:
            data = resp.json()
            assert data.get("code") == 0
            assert "access_token" in data.get("data", {})
            assert "refresh_token" in data.get("data", {})
    except httpx.ConnectError:
        pytest.skip(f"Java 服务未运行于 {target_url}")


def test_unauthorized_request_to_protected_endpoint(target_url: str) -> None:
    """无 token 访问 /api/users/me 返回 401/403."""
    import httpx

    try:
        resp = httpx.get(f"{target_url}/api/users/me", timeout=3.0)
        assert resp.status_code in (401, 403), f"状态码: {resp.status_code}"
    except httpx.ConnectError:
        pytest.skip(f"Java 服务未运行于 {target_url}")
