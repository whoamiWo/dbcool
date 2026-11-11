"""管理员依赖的 JWT 角色声明兼容性测试。

Java 后端签发 `roles: ["admin"]`，Python 自签 token 使用 `role: "admin"`；
`get_current_admin_user` 必须同时接受两种格式（PHASE 验收修复）。
"""

import pytest
from fastapi import HTTPException
from jose import jwt

from nocobase_py.config import get_settings
from nocobase_py.security import _is_admin_payload, get_current_admin_user


def _token(payload_extra: dict) -> str:
    settings = get_settings()
    payload = {
        "sub": "00000000-0000-0000-0000-000000000001",
        "username": "admin",
        "tid": "tenant_default",
        "typ": "access",
        **payload_extra,
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithms[0])


@pytest.mark.parametrize(
    "claims",
    [
        {"role": "admin"},          # Python 自签格式
        {"roles": ["admin"]},       # Java 签发格式
        {"roles": ["ADMIN"]},       # 大小写不敏感
        {"roles": ["user", "admin"]},
        {"roles": "admin"},
    ],
)
def test_is_admin_payload_accepts_admin_claims(claims):
    assert _is_admin_payload(claims) is True


@pytest.mark.parametrize(
    "claims",
    [
        {},
        {"role": "user"},
        {"roles": ["user"]},
        {"roles": []},
        {"roles": None},
        {"roles": [None, ""]},
    ],
)
def test_is_admin_payload_rejects_non_admin_claims(claims):
    assert _is_admin_payload(claims) is False


@pytest.mark.asyncio
async def test_java_style_token_passes_admin_dependency():
    user = await get_current_admin_user(f"Bearer {_token({'roles': ['admin']})}")
    assert user.user_id.startswith("00000000")


@pytest.mark.asyncio
async def test_non_admin_token_gets_403():
    with pytest.raises(HTTPException) as exc:
        await get_current_admin_user(f"Bearer {_token({'roles': ['user']})}")
    assert exc.value.status_code == 403
