"""告警接收端点（AlertManager webhook）—— PHASE104 T7.3."""

import logging
import time
import uuid

from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel

from nocobase_py.services.alert_store import get_store

logger = logging.getLogger("nocobase_py.alerts")

# PHASE104 审计修正：前缀必须带 /api，与项目其他路由一致
# （ai=/api/ai、health=/api、backup=/api/admin/backup、tasks=/api/tasks）。
# 此前写成 /alerts，导致 AlertManager 配置的 http://backend-python:8000/api/alerts/webhook
# 实际 404 —— 告警永远送不到（该缺陷只有真跑才会暴露）。
router = APIRouter(prefix="/api/alerts", tags=["alerts"])


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
        return datetime.datetime.now(datetime.UTC).timestamp()
    try:
        dt = datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))
        return dt.timestamp()
    except ValueError:
        return datetime.datetime.now(datetime.UTC).timestamp()


@router.post("/webhook")
async def alert_webhook(payload: AlertPayload, background_tasks: BackgroundTasks):
    """AlertManager 回调端点。

    - 同步落盘告警到 SQLite（不丢失）
    - 后台推送 webhook（若已配置 ALERT_WEBHOOK_URL）
    """
    if not payload.version or not payload.receiver:
        raise HTTPException(status_code=400, detail="Invalid payload structure")

    count = persist_alert(payload)

    # PHASE104 T9: 二次推送（此前为 TODO 占位，告警只落盘不外发）。
    # 仅当配置了 webhook 才推送；未配置时启动日志已明确提示，不静默。
    try:
        from nocobase_py.services.webhook import get_pusher

        pusher = get_pusher()
        if pusher is not None and pusher.config.is_configured():
            for alert in payload.alerts:
                labels = alert.get("labels", {})
                annotations = alert.get("annotations", {})
                background_tasks.add_task(
                    _forward_alert_with_retry,
                    pusher,
                    {
                        "kind": f"alertmanager.{labels.get('alertname', 'unknown')}",
                        "user_id": None,
                        "detail": {
                            "severity": labels.get("severity", "unknown"),
                            "summary": annotations.get("summary", ""),
                            "description": annotations.get("description", ""),
                        },
                    },
                )
    except Exception as e:  # noqa: BLE001
        logger.warning("[alert] webhook 二次推送调度失败：%s", e)

    return {"status": "ok", "received": count}


def _forward_alert_with_retry(pusher, event: dict, max_attempts: int = 3) -> None:
    """带指数退避的 webhook 推送（后台任务）。

    AlertManager → webhook 的推送若失败（网络抖动/目标短暂不可用），
    不静默丢弃；重试耗尽后记录 WARN 日志供运维发现。
    """
    delay = 0.5
    for attempt in range(1, max_attempts + 1):
        try:
            if pusher.push(event):
                logger.info("[alert] webhook 二次推送成功（第 %d 次尝试）", attempt)
                return
        except Exception as e:  # noqa: BLE001
            logger.warning("[alert] webhook 推送异常（第 %d 次）：%s", attempt, e)
        if attempt < max_attempts:
            time.sleep(delay)
            delay *= 2
    logger.warning("[alert] webhook 二次推送重试 %d 次后仍失败，告警仅落盘", max_attempts)


@router.get("/list")
async def list_alerts(limit: int = 50):
    """列出最近告警（用于调试/前端展示）。"""
    store = get_store()
    rows = store.iter_alerts(limit=limit)
    return {"total": len(rows), "alerts": rows}
