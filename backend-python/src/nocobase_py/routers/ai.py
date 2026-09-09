"""AI 增强端点 — Week 4 真接 JWT 校验."""

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from nocobase_py.security import AuthUser, get_current_user

router = APIRouter(prefix="/api/ai", tags=["ai"])


class EchoData(BaseModel):
    """Echo 响应 data."""

    echo: str
    user_id: str
    username: str
    tenant_id: str
    service: str = "nocobase-py"


class ApiResponse(BaseModel):
    """统一响应."""

    code: int = 0
    message: str = "success"
    data: EchoData | dict


@router.get("/echo", response_model=ApiResponse)
async def echo(
    msg: str = "hello",
    user: AuthUser = Depends(get_current_user),
) -> ApiResponse:
    """Echo 端点:校验 JWT + 返回 echo.

    Args:
        msg: 要回显的消息
        user: 从 JWT 解析的用户(由 get_current_user 注入)
    """
    return ApiResponse(
        data=EchoData(
            echo=msg,
            user_id=user.user_id,
            username=user.username,
            tenant_id=user.tenant_id,
        )
    )


@router.get("/whoami", response_model=ApiResponse)
async def whoami(user: AuthUser = Depends(get_current_user)) -> ApiResponse:
    """返回当前 JWT 用户信息(给前端调试用)."""
    return ApiResponse(
        data=EchoData(
            echo="whoami",
            user_id=user.user_id,
            username=user.username,
            tenant_id=user.tenant_id,
        )
    )
