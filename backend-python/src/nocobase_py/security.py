"""JWT 校验 — 与 Java 后端共享密钥."""

from typing import Annotated

from fastapi import Depends, Header, HTTPException, status
from jose import JWTError, jwt
from pydantic import BaseModel

from nocobase_py.config import get_settings


class AuthUser(BaseModel):
    """从 JWT 解析出的用户信息."""

    user_id: str
    username: str
    tenant_id: str

    @property
    def is_authenticated(self) -> bool:
        return bool(self.user_id)


def _decode_token(token: str) -> dict | None:
    """解码 JWT,失败返回 None."""
    settings = get_settings()
    try:
        payload = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
        )
        # 必须为 access token
        if payload.get("typ") != "access":
            return None
        return payload
    except JWTError:
        return None


async def get_current_user(
    authorization: Annotated[str | None, Header()] = None,
) -> AuthUser:
    """FastAPI 依赖:从 Authorization 头解析当前用户.

    Usage:
        @app.get("/api/foo")
        async def foo(user: AuthUser = Depends(get_current_user)):
            ...
    """
    if authorization is None or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="缺少或非法的 Authorization 头",
            headers={"WWW-Authenticate": "Bearer"},
        )

    token = authorization[7:]
    payload = _decode_token(token)
    if payload is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="token 无效或已过期",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return AuthUser(
        user_id=payload["sub"],
        username=payload.get("username", ""),
        tenant_id=payload.get("tid", ""),
    )


async def get_optional_user(
    authorization: Annotated[str | None, Header()] = None,
) -> AuthUser | None:
    """可选认证:有 token 解析,没有返回 None."""
    if authorization is None or not authorization.startswith("Bearer "):
        return None
    payload = _decode_token(authorization[7:])
    if payload is None:
        return None
    return AuthUser(
        user_id=payload["sub"],
        username=payload.get("username", ""),
        tenant_id=payload.get("tid", ""),
    )


async def get_current_admin_user(
    authorization: Annotated[str | None, Header()] = None,
) -> AuthUser:
    """FastAPI 依赖:要求当前用户为管理员（JWT 中 role=admin）.

    Usage:
        @app.post("/api/admin/backup")
        async def create(user: AuthUser = Depends(get_current_admin_user)):
            ...
    """
    user = await get_current_user(authorization)
    payload = _decode_token(authorization[7:]) if authorization else None
    if payload is None or payload.get("role") != "admin":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="需要管理员权限",
        )
    return user
