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
    """解码 JWT，失败返回 None."""
    settings = get_settings()
    try:
        payload = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=settings.jwt_algorithms,
        )
        # 必须为 access token
        if payload.get("typ") != "access":
            return None
        return payload
    except JWTError:
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


async def get_service_or_user(
    authorization: Annotated[str | None, Header()] = None,
) -> AuthUser:
    """服务间调用 或 用户 JWT 认证(二者取其一)。

    用于 Java → Python 的内部端点(如 `/api/ai/embedding`):

    1. 若配置了 `internal_service_token` 且请求携带的 token 与其一致,
       视为可信的服务间调用(不消耗用户配额);
    2. 否则按普通用户 JWT 校验,未携带或无效 → 401。

    这样既恢复了对外的认证保护,又允许 Java 侧以服务身份调用。
    """
    settings = get_settings()
    token = (
        authorization[7:]
        if (authorization is not None and authorization.startswith("Bearer "))
        else None
    )

    if (
        token is not None
        and settings.internal_service_token
        and token == settings.internal_service_token
    ):
        return AuthUser(user_id="internal-service", username="internal", tenant_id="")

    # 回退:按用户 JWT 校验(无效会抛 401)
    return await get_current_user(authorization)


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
