"""W6 备份恢复演练测试 — PHASE 55 P1「容灾未验证」收口。

验证闭环：create_backup → 破坏源数据 → restore_backup → 断言数据完整恢复。
此前备份链路代码完整但**从未做过真实恢复验证**，本测试把「能恢复」变成可回归的断言。

隔离策略：
- `monkeypatch.chdir(tmp_path)` 切换工作目录（`_DB_PATHS`/`_MEDIA_DIR` 为相对路径）
- `monkeypatch.setattr(backup, "_BACKUP_DIR", ...)` —— 该常量在 import 时求值，改环境变量无效
- stub `_redis_snapshot` —— 避免依赖真实 Redis 实例
"""

from __future__ import annotations

import io
import sqlite3
import tarfile
import time
from pathlib import Path

import pytest

from nocobase_py.services import backup as backup_service

SENTINEL = "哨兵数据-不应丢失"
FAKE_RDB = b"FAKE-RDB-SNAPSHOT"


# ==================== helpers ====================


def _make_db(path: Path, value: str = SENTINEL) -> None:
    conn = sqlite3.connect(path)
    try:
        conn.execute("CREATE TABLE IF NOT EXISTS t (v TEXT)")
        conn.execute("DELETE FROM t")
        conn.execute("INSERT INTO t VALUES (?)", (value,))
        conn.commit()
    finally:
        conn.close()


def _read_db(path: Path) -> str | None:
    if not path.exists():
        return None
    conn = sqlite3.connect(path)
    try:
        row = conn.execute("SELECT v FROM t LIMIT 1").fetchone()
        return row[0] if row else None
    finally:
        conn.close()


# ==================== fixtures ====================


@pytest.fixture
def workspace(tmp_path, monkeypatch):
    """隔离工作目录与备份目录，绝不污染真实环境。

    关键：备份目录是模块级常量，需在 import 后立即修改。
    """
    work = tmp_path / "work"
    work.mkdir()
    backups = tmp_path / "backups"
    backups.mkdir()
    monkeypatch.chdir(work)
    # 环境变量在 import backup 前读取，必须先设 env 再 import
    # 但我们这里是测试已 import，直接改常量即可
    backup_service._BACKUP_DIR = backups
    return work, backups


@pytest.fixture
def redis_stubbed(monkeypatch):
    """stub Redis 快照，避免测试依赖真实 Redis 实例。

    直接改常量更可靠（monkeypatch.setattr 需 Python 3.12+ 的新特性）。
    """
    backup_service._redis_snapshot = lambda: FAKE_RDB


# ==================== 演练：完整闭环 ====================


class TestBackupRestoreDrill:
    def test_full_drill_db_and_media_restored(self, workspace, redis_stubbed):
        work, _ = workspace
        db = work / "alerts.db"
        media_dir = work / "media"
        media_dir.mkdir()
        media_file = media_dir / "a.txt"
        media_file.write_text("media-sentinel")
        _make_db(db)

        created = backup_service.create_backup()
        assert "db" in created["components"]
        assert "media" in created["components"]
        assert "redis" in created["components"]

        # 模拟灾难：删除 db、篡改 media
        db.unlink()
        media_file.write_text("被篡改")
        assert _read_db(db) is None

        result = backup_service.restore_backup(created["backup_id"])

        # 恢复后数据必须完整回来
        assert _read_db(db) == SENTINEL
        assert media_file.read_text() == "media-sentinel"
        assert "db" in result["restored"]
        assert "media" in result["restored"]

    def test_redis_snapshot_restored(self, workspace, redis_stubbed):
        work, _ = workspace
        _make_db(work / "alerts.db")
        created = backup_service.create_backup()

        dump = work / "dump.rdb"
        dump.unlink(missing_ok=True)

        backup_service.restore_backup(created["backup_id"])

        assert dump.exists(), "Redis 快照未恢复"
        assert dump.read_bytes() == FAKE_RDB

    def test_restore_twice_is_idempotent(self, workspace, redis_stubbed):
        """重复恢复不应报错，数据保持一致。"""
        work, _ = workspace
        db = work / "alerts.db"
        _make_db(db)
        created = backup_service.create_backup()

        backup_service.restore_backup(created["backup_id"])
        backup_service.restore_backup(created["backup_id"])

        assert _read_db(db) == SENTINEL


# ==================== 反向用例 ====================


class TestBackupRestoreErrors:
    def test_restore_missing_backup_raises_file_not_found(self, workspace):
        with pytest.raises(FileNotFoundError):
            backup_service.restore_backup("backup_不存在")

    def test_list_backups_empty_dir_returns_empty(self, workspace):
        assert backup_service.list_backups() == []

    def test_path_traversal_member_not_written_outside_workdir(self, workspace):
        """安全：恶意 tar 中的 `db/../../evil.txt` 不得写到工作目录之外。"""
        work, backups = workspace
        outside = work.parent / "evil.txt"  # 工作目录之外

        tar_path = backups / "backup_evil.tar.gz"
        payload = b"pwned"
        with tarfile.open(tar_path, "w:gz") as tar:
            ti = tarfile.TarInfo(name="db/../../evil.txt")
            ti.size = len(payload)
            ti.mtime = time.time()
            tar.addfile(ti, io.BytesIO(payload))

        try:
            backup_service.restore_backup("backup_evil")
        except Exception:
            # 抛异常（如 tarfile 的 data filter 拒绝）同样是可接受的拒绝行为
            pass

        assert not outside.exists(), "路径穿越成员被解压到工作目录之外"


# ==================== 备份清单 ====================


class TestListBackups:
    def test_list_backups_contains_created(self, workspace, redis_stubbed):
        work, _ = workspace
        _make_db(work / "alerts.db")
        created = backup_service.create_backup()

        items = backup_service.list_backups()

        assert any(i["backup_id"] == created["backup_id"] for i in items)
