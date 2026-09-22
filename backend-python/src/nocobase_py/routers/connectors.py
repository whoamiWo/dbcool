"""连接器 API 路由 (W4)。"""

import logging
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import JSONResponse

from nocobase_py.db.session import get_db_session
from nocobase_py.models.settings import Settings
from nocobase_py.services.connector_manager import connector_manager
from nocobase_py.services.connectors import get_connector, list_connectors

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/connectors", tags=["connectors"])


@router.get("")
async def list_available_connectors():
    """列出所有可用的连接器及其状态。"""
    connectors_info = []
    for name, cls in list_connectors().items():
        connector_info = {
            "name": name,
            "display_name": cls.DISPLAY_NAME,
            "description": cls.__doc__ or "",
        }
        connectors_info.append(connector_info)
    return {"connectors": connectors_info}


@router.get("/{name}")
async def get_connector_status(name: str):
    """获取指定连接器的状态。"""
    if name not in list_connectors():
        raise HTTPException(status_code=404, detail=f"Connector '{name}' not found")

    connector = await connector_manager.get_or_create_connector(name)
    if not connector:
        return {
            "name": name,
            "configured": False,
            "enabled": False,
        }

    return {
        "name": name,
        "configured": connector.is_configured,
        "enabled": True,
    }


@router.post("/{name}/health")
async def check_connector_health(name: str):
    """执行连接器健康检查。"""
    if name not in list_connectors():
        raise HTTPException(status_code=404, detail=f"Connector '{name}' not found")

    connector = await connector_manager.get_or_create_connector(name)
    if not connector:
        return {"healthy": False, "reason": "not_configured"}

    try:
        healthy = await connector.health_check()
        return {"healthy": healthy, "platform": name}
    except Exception as e:
        logger.error("[API] 健康检查异常：%s", e)
        return {"healthy": False, "reason": str(e)}


@router.post("/health-all")
async def check_all_connectors_health():
    """对所有连接器进行健康检查。"""
    results = await connector_manager.health_check_all()
    return {"results": results}


@router.post("/{name}/send")
async def send_message_via_connector(
    name: str,
    channel_id: str,
    message: str,
    session: Annotated[Settings, Depends(get_db_session)],
):
    """通过指定连接器发送消息。"""
    if name not in list_connectors():
        raise HTTPException(status_code=404, detail=f"Connector '{name}' not found")

    connector_manager.set_settings(session)
    result = await connector_manager.send_to_platform(name, channel_id, message)
    return JSONResponse(content=result)


@router.post("/broadcast")
async def broadcast_message(
    message: str,
    platforms: list | None = None,
    session: Annotated[Settings, Depends(get_db_session)] = None,
):
    """广播消息到多个平台。"""
    if session:
        connector_manager.set_settings(session)

    result = await connector_manager.broadcast(message, platforms)
    return JSONResponse(content=result)
