"""告警接收端点（AlertManager webhook）—— PHASE104 T7.3."""

import json
import logging
import uuid

from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel

from nocobase_py.services.alert_store import get_store

logger = logging.getLogger("nocobase_py.alerts")

router = APIRouter(prefix="/alerts", tags=["alerts"])


class AlertPayload(BaseModel):
    """AlertManager webhook 载荷（简化版）。"""

    version: str
    groupKey: str
    truncatedAlerts: int = 0
    status: str
    receiver: str
    groupLabels: dict
    commonLabels: dict
    commonAnnotations: dict
    externalURL: str
    alerts: list[dict]


def persist_alert(payload: AlertPayload) -> int:
    """将告警落盘到 SQLite（alert_store）。返回落盘条数。"""
    store = get_store()
    count = 0
    for alert in payload.alerts:
        labels = alert.get("labels", {})
        annotations = alert.get("annotations", {})
        store.insert_alert({
            "id": alert.get("fingerprint") or uuid.uuid4().hex[:12],
            "kind": f"alertmanager.{labels.get('alertname', 'unknown')}",
            "user_id": None,
            "detail": {
                "severity": labels.get("severity", "unknown"),
                "service": labels.get("service", ""),
                "summary": annotations.get("summary", ""),
                "description": annotations.get("description", ""),
                "status": payload.status,
                "startsAt": alert.get("startsAt", ""),
                "endsAt": alert.get("endsAt", ""),
                "externalURL": payload.externalURL,
            },
            "timestamp": _parse_ts(alert.get("startsAt")),
        })
        count += 1
    logger.info("[alert] 落盘 %d 条告警 (status=%s)", count, payload.status)
    return count


def _parse_ts(ts: str) -> float:
    """解析 RFC3339 时间戳为 epoch 秒；失败时返回当前时间。"""
    import datetime

    if not ts:
        return datetime.datetime.now(datetime.timezone.utc).timestamp()
    try:
        dt = datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))
        return dt.timestamp()
    except ValueError:
        return datetime.datetime.now(datetime.timezone.utc).timestamp()


@router.post("/webhook")
async def alert_webhook(payload: AlertPayload, background_tasks: BackgroundTasks):
    """AlertManager 回调端点。

    - 同步落盘告警到 SQLite（不丢失）
    - 后台推送 webhook（预留接口，待 webhook 服务完善后启用）
    """
    if not payload.version or not payload.receiver:
        raise HTTPException(status_code=400, detail="Invalid payload structure")

    count = persist_alert(payload)

    # TODO: 待 webhook 服务完善后启用二次推送
    # try:
    #     from nocobase_py.services.webhook import get_pusher
    #     pusher = get_pusher()
    #     ...
    # except Exception as e:
    #     logger.warning("[alert] webhook 推送失败：%s", e)

    return {"status": "ok", "received": count}


@router.get("/list")
async def list_alerts(limit: int = 50):
    """列出最近告警（用于调试/前端展示）。"""
    store = get_store()
    rows = store.iter_alerts(limit=limit)
    return {"total": len(rows), "alerts": rows}
