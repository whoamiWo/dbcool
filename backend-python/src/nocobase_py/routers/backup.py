"""W6 生产化闭环 — 备份管理路由。

端点：
- POST /api/admin/backup          手动触发全量备份
- GET  /api/admin/backup          列出所有备份
- POST /api/admin/backup/{id}/restore  从指定备份恢复
- GET  /api/admin/backup/health    备份健康检查（备份数/最新/磁盘空间）
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from nocobase_py.security import AuthUser, get_current_admin_user, get_current_user
from nocobase_py.services import backup as backup_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/admin/backup", tags=["admin", "backup"])


@router.post("")
async def create_backup(user: AuthUser = Depends(get_current_admin_user)) -> dict[str, Any]:
    """手动触发全量备份（需管理员权限）。"""
    try:
        result = backup_service.create_backup()
        return {"code": 0, "message": "backup created", "data": result}
    except Exception as e:
        logger.exception("[backup] create failed")
        raise HTTPException(status_code=500, detail=f"backup failed: {e}")


@router.get("")
async def list_backups(user: AuthUser = Depends(get_current_user)) -> dict[str, Any]:
    """列出备份（需认证）。"""
    backups = backup_service.list_backups()
    return {"code": 0, "message": "success", "data": backups}


@router.post("/{backup_id}/restore")
async def restore_backup(backup_id: str, user: AuthUser = Depends(get_current_admin_user)) -> dict[str, Any]:
    """从指定备份恢复（需管理员权限）。"""
    try:
        result = backup_service.restore_backup(backup_id)
        return {"code": 0, "message": "restored", "data": result}
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.exception("[backup] restore failed")
        raise HTTPException(status_code=500, detail=f"restore failed: {e}")


@router.get("/health")
async def backup_health() -> dict[str, Any]:
    """备份健康检查（公开）。"""
    backups = backup_service.list_backups()
    latest = backups[0] if backups else None
    return {
        "code": 0,
        "message": "success",
        "data": {
            "total_backups": len(backups),
            "latest_backup_id": latest["backup_id"] if latest else None,
            "latest_size_bytes": latest["size_bytes"] if latest else None,
            "retention_days": backup_service._RETENTION_DAYS,
        },
    }


@router.post("/prune")
async def prune_backups(retention_days: int | None = None, user: AuthUser = Depends(get_current_admin_user)) -> dict[str, Any]:
    """手动清理超过保留期的备份（需管理员权限）。"""
    deleted = backup_service.prune_old_backups(retention_days)
    return {"code": 0, "message": "pruned", "data": {"deleted": deleted}}