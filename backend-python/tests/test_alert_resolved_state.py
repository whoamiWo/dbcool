"""H11: resolved webhook events must update indexed resolved columns."""

from datetime import datetime

import pytest

from nocobase_py.routers import alerts as alerts_router
from nocobase_py.services.alert_store import AlertStore


def _epoch(ts: str) -> float:
    return datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp()


def _payload(
    *,
    status: str,
    alert_status: str,
    fingerprint: str = "fingerprint-h11",
    starts_at: str = "2026-10-14T00:00:00Z",
    ends_at: str = "2026-10-14T01:00:00Z",
) -> alerts_router.AlertPayload:
    return alerts_router.AlertPayload(
        version="4",
        groupKey="group-h11",
        status=status,
        receiver="test",
        groupLabels={"alertname": "H11"},
        commonLabels={},
        commonAnnotations={},
        externalURL="http://alertmanager",
        alerts=[
            {
                "fingerprint": fingerprint,
                "status": alert_status,
                "labels": {"alertname": "H11", "severity": "critical"},
                "annotations": {"summary": "summary", "description": "description"},
                "startsAt": starts_at,
                "endsAt": ends_at,
            }
        ],
    )


def _store(tmp_path, monkeypatch) -> AlertStore:
    store = AlertStore(tmp_path / "alerts.db")
    monkeypatch.setattr(alerts_router, "get_store", lambda: store)
    return store


def test_resolved_webhook_sets_index_columns(tmp_path, monkeypatch):
    store = _store(tmp_path, monkeypatch)
    ends_at = "2026-10-14T01:00:00Z"

    count = alerts_router.persist_alert(
        _payload(status="resolved", alert_status="resolved", ends_at=ends_at)
    )

    assert count == 1
    row = store.iter_alerts(limit=1)[0]
    assert row["detail"]["status"] == "resolved"
    assert row["resolved"] is True
    assert row["resolved_at"] == pytest.approx(_epoch(ends_at))


def test_firing_webhook_keeps_index_columns_unresolved(tmp_path, monkeypatch):
    store = _store(tmp_path, monkeypatch)

    alerts_router.persist_alert(_payload(status="firing", alert_status="firing"))

    row = store.iter_alerts(limit=1)[0]
    assert row["resolved"] is False
    assert row["resolved_at"] is None


def test_same_fingerprint_resolved_replay_updates_existing_row(tmp_path, monkeypatch):
    store = _store(tmp_path, monkeypatch)
    fingerprint = "same-fingerprint"

    alerts_router.persist_alert(
        _payload(status="firing", alert_status="firing", fingerprint=fingerprint)
    )
    alerts_router.persist_alert(
        _payload(status="resolved", alert_status="resolved", fingerprint=fingerprint)
    )

    rows = store.iter_alerts(limit=10)
    assert len(rows) == 1
    assert rows[0]["id"] == fingerprint
    assert rows[0]["resolved"] is True
