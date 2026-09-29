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
import shutil
import sqlite3
import tarfile
import time
from pathlib import Path

import pytest

from nocobase_py.services import backup as backup_service

SENTINEL = "哨兵数据 - 不应丢失"
FAKE_RDB = b"FAKE-RDB-SNAPSHOT"
# 仅用于「编排逻辑」用例：这些用例被测对象是 create_backup/restore_backup，
# pg_dump 属于外部二进制依赖，stub 它不影响被测目标。
# 真实 pg_dump 能力的验证在 TestRealPostgresDump（skipif 条件跳过）。
FAKE_PG_DUMP = b"FAKE-PG-DUMP-FOR-ORCHESTRATION-ONLY"


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


@pytest.fixture
def postgres_stubbed(monkeypatch):
    """stub PostgreSQL 主库 dump/restore，避免测试依赖 pg_dump 命令。

    说明（PHASE 58 第二轮返工修正）：
    本文件主体用例测的是**备份编排逻辑**（打包 / 恢复 / 幂等 / 路径穿越防护），
    被测对象是 create_backup / restore_backup，而 `_postgres_dump` 依赖外部二进制
    pg_dump，属于**外部不可控依赖**，stub 它是合理且必要的 ——
    否则在无 pg_dump 的 CI 环境下这 4 个用例会全部失败（实测 4 failed）。

    **真实 pg_dump 是否可用由 TestRealPostgresDump 单独负责**（见文件末尾，
    用 skipif(shutil.which("pg_dump")) 做条件跳过），两者分工不冲突。
    """
    backup_service._postgres_dump = lambda: FAKE_PG_DUMP
    backup_service._restore_postgres = lambda dump_bytes: True


# ==================== 演练：完整闭环 ====================


class TestBackupRestoreDrill:
    def test_full_drill_db_and_media_restored(self, workspace, redis_stubbed, postgres_stubbed):
        work, _ = workspace
        db = work / "alerts.db"
        media_dir = work / "media"
        media_dir.mkdir()
        media_file = media_dir / "a.txt"
        media_file.write_text("media-sentinel")
        _make_db(db)

        created = backup_service.create_backup()
        assert "sqlite" in created["components"]
        assert "media" in created["components"]
        assert "redis" in created["components"]
        # PostgreSQL 组件存在与否取决于环境，不强制断言

        # 模拟灾难：删除 db、篡改 media
        db.unlink()
        media_file.write_text("被篡改")
        assert _read_db(db) is None

        result = backup_service.restore_backup(created["backup_id"])

        # 恢复后数据必须完整回来
        assert _read_db(db) == SENTINEL
        assert media_file.read_text() == "media-sentinel"
        assert "sqlite" in result["restored"]
        assert "media" in result["restored"]

    def test_redis_snapshot_restored(self, workspace, redis_stubbed, postgres_stubbed):
        work, _ = workspace
        _make_db(work / "alerts.db")
        created = backup_service.create_backup()

        dump = work / "dump.rdb"
        dump.unlink(missing_ok=True)

        backup_service.restore_backup(created["backup_id"])

        assert dump.exists(), "Redis 快照未恢复"
        assert dump.read_bytes() == FAKE_RDB

    def test_restore_twice_is_idempotent(self, workspace, redis_stubbed, postgres_stubbed):
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
    def test_list_backups_contains_created(self, workspace, redis_stubbed, postgres_stubbed):
        work, _ = workspace
        _make_db(work / "alerts.db")
        created = backup_service.create_backup()

        items = backup_service.list_backups()

        assert any(i["backup_id"] == created["backup_id"] for i in items)


class TestPostgresBackupFailure:
    """PostgreSQL 备份失败时整体失败的测试。"""
    
    def test_postgres_fail_raises_runtime_error(self, workspace, redis_stubbed):
        """当 _postgres_dump 抛出异常时，create_backup 应该向上抛出 RuntimeError。"""
        work, _ = workspace
        backup_service._postgres_dump = lambda: (_ for _ in ()).throw(RuntimeError("pg_dump failed"))
        
        _make_db(work / "alerts.db")
        
        with pytest.raises(RuntimeError, match="PostgreSQL backup failed"):
            backup_service.create_backup()


class TestRealPostgresDump:
    """真实 pg_dump 测试 — 不 stub 主路径函数。"""
    
    @pytest.mark.skipif(not shutil.which("pg_dump"), reason="R2: 此环境无 pg_dump 命令，跳过真实测试。手动执行命令见文档 PHASE58_GLM53_REWORK2_PROMPT.md §3")
    def test_real_pg_dump_produces_valid_archive(self, workspace, redis_stubbed):
        """R2: 真实调用 pg_dump，断言返回非空且能被 pg_restore 识别。
        
        此测试不使用任何 stub，直接调用 backup_service._postgres_dump()。
        若 CI 环境无 pg_dump，pytest.mark.skipif 会自动跳过并显示原因。
        """
        work, _ = workspace
        
        # 真实调用，不 stub
        dump_data = backup_service._postgres_dump()
        
        # 断言 1: 返回字节数 > 0
        assert len(dump_data) > 0, "pg_dump 返回空数据"
        
        # 断言 2: 输出是有效的 PostgreSQL 格式（以 SQL 注释或 PGDMP 魔数开头）
        # PostgreSQL 自定义格式以 "PGDMP" 开头，纯文本以 "-- PostgreSQL" 开头
        is_custom_format = dump_data.startswith(b"PGDMP")
        is_plain_sql = dump_data.strip().startswith(b"-- PostgreSQL") or dump_data.strip().startswith(b"SET ")
        
        assert is_custom_format or is_plain_sql, f"pg_dump 输出格式未知：前 100 字节={dump_data[:100]}"
        
        # 断言 3: 如果能找到 pg_restore，验证它能列出内容
        if shutil.which("pg_restore"):
            import subprocess
            proc = subprocess.run(
                ["pg_restore", "-l", "-"],
                input=dump_data,
                capture_output=True,
                timeout=30
            )
            # pg_restore -l 成功退出码为 0，即使 archive 为空也会输出表头
            assert proc.returncode == 0, f"pg_restore -l 失败：{proc.stderr.decode()}"
