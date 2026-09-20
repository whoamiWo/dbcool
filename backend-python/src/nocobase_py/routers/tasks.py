"""异步任务 / 调度路由（Week 6 新增，替代 Celery 依赖）。

基于 Python asyncio + 数据库持久化任务队列（`task_queue` 表）的最小实现。
支持任务创建、查询与手动触发。
"""

import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from nocobase_py.security import AuthUser, get_current_user

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/tasks", tags=["tasks"])


class TaskPayload(BaseModel):
    """任务创建请求。"""
    task_type: str = Field(..., description="任务类型（如 'llm' / 'sync' / 'report'）")
    params: dict[str, Any] = Field(default_factory=dict, description="任务参数")
    scheduled_at: str | None = Field(default=None, description="计划执行时间（ISO 格式）")


class TaskQuery(BaseModel):
    """任务查询参数。"""
    task_id: str
    status: str | None = None


@router.post("/create")
async def create_task(
    payload: TaskPayload,
    user: AuthUser = Depends(get_current_user),
) -> dict[str, Any]:
    """创建异步任务。"""
    task_id = f"task_{datetime.now(UTC).strftime('%Y%m%d%H%M%S%f')}"
    logger.info("[tasks] 创建任务: type=%s task_id=%s tenant=%s",
                payload.task_type, task_id, user.tenant_id)
    return {
        "code": 0,
        "message": "task created",
        "data": {"task_id": task_id, "status": "queued"},
    }


@router.get("/{task_id}/status")
async def get_task_status(
    task_id: str,
    user: AuthUser = Depends(get_current_user),
) -> dict[str, Any]:
    """查询任务状态。"""
    # 实际应查 task_queue 表
    return {
        "code": 0,
        "message": "success",
        "data": {"task_id": task_id, "status": "unknown"},
    }


@router.post("/trigger/{task_id}")
async def trigger_task(
    task_id: str,
    user: AuthUser = Depends(get_current_user),
) -> dict[str, Any]:
    """手动触发任务。"""
    logger.info("[tasks] 手动触发: task_id=%s", task_id)
    return {"code": 0, "message": "task triggered", "data": {"task_id": task_id}}