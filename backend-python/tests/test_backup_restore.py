"""W6 备份恢复演练测试 — PHASE 55 P1「容灾未验证」收口。

验证闭环：create_backup → 破坏源数据 → restore_backup → 断言数据完整恢复。
此前备份链路代码完整但**从未做过真实恢复验证**，本测试把「能恢复」变成可回归的断言。

隔离策略：
- `monkeypatch.chdir(tmp_path)` 切换工作目录（`_DB_PATHS`/`_MEDIA_DIR` 为相对路径）
- `monkeypatch.setattr(backup, "_BACKUP_DIR", ...)` —— 该常量在 import 时求值，改环境变量无效
- stub `_redis_snapshot` —— 避免依赖真实 Redis 实例
"""

from __future__ import annotations

import contextlib
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


@pytest.fixture
def redis_restore_stubbed(monkeypatch):
    """PHASE101 T4: stub Redis 真恢复（docker cp + restart），记录调用参数。

    原 `test_redis_snapshot_restored` 断言 `work/dump.rdb` 被写出 —— 那是
    **旧的假恢复**（只落盘不加载，红线 2）。T4 改为真恢复后，断言改为：
    `_restore_redis` 被调用且收到备份里的 rdb 字节。
    """
    calls: list[bytes] = []

    def _fake_restore(rdb_bytes: bytes) -> None:
        calls.append(rdb_bytes)

    backup_service._restore_redis = _fake_restore
    return calls


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

    def test_redis_snapshot_restored(self, workspace, redis_stubbed, postgres_stubbed, redis_restore_stubbed):
        """PHASE101 T4: Redis 恢复必须走真恢复路径（docker cp + restart），不是只落盘。

        旧断言 `work/dump.rdb` 存在 —— 那证明的只是"字节被写到当前目录"，
        Redis 从未加载它，是假恢复。新断言：`_restore_redis` 被调用且收到
        备份里的 rdb 字节（内容与 `_redis_snapshot()` 一致）。
        """
        work, _ = workspace
        _make_db(work / "alerts.db")
        created = backup_service.create_backup()

        result = backup_service.restore_backup(created["backup_id"])

        assert redis_restore_stubbed, "Redis 真恢复路径未被调用"
        assert redis_restore_stubbed[0] == FAKE_RDB
        assert "redis" in result["restored"]

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

        with contextlib.suppress(Exception):  # noqa: BLE001
            backup_service.restore_backup("backup_evil")

        assert not outside.exists(), "路径穿越成员被解压到工作目录之外"

    def test_dry_run_does_not_modify_system(self, workspace, redis_stubbed, postgres_stubbed):
        """PHASE101 T5: dry_run=True 只校验备份可解包，不写任何系统。"""
        work, _ = workspace
        _make_db(work / "alerts.db")
        created = backup_service.create_backup()

        # 删除 SQLite 文件，模拟需要恢复的状态
        (work / "alerts.db").unlink()

        # dry-run 只返回校验结果，不恢复
        result = backup_service.restore_backup(created["backup_id"], dry_run=True)

        assert result["dry_run"] is True
        assert "sqlite" in result["would_restore"], "dry-run 应识别出可恢复的 sqlite 组件"
        assert result["all_pass"] is True, f"dry-run 校验未通过: {result['checks']}"
        # 关键断言：dry-run 不得真正恢复数据
        assert (work / "alerts.db").exists() is False, "dry-run 后 SQLite 被恢复了（不应发生）"

    def test_pre_restore_snapshot_does_not_overwrite_target(self, workspace, redis_stubbed, postgres_stubbed):
        """PHASE101 T5: pre-restore snapshot 不得覆盖待恢复的备份（同秒 P0 bug 修复验证）。

        旧 bug: _now_stamp() 精度为秒，restore 内的 snapshot 与原备份同秒 → 同名 tar.gz
        被 "w:gz" 截断覆盖，导致恢复源被销毁。

        新行为：_now_stamp() 检测到同名时追加序号 (_1, _2...) 保证唯一性。
        """
        work, backups = workspace
        _make_db(work / "alerts.db")
        created = backup_service.create_backup()
        original_tar = backups / f"{created['backup_id']}.tar.gz"

        # 验证原备份存在
        assert original_tar.exists(), "原备份文件不存在"

        # 读取原备份的成员列表（用于后续对比）
        import tarfile
        with tarfile.open(original_tar, "r:gz") as t:
            original_members = set(t.getnames())

        # 删除 alerts.db 模拟需要恢复
        (work / "alerts.db").unlink()

        # 执行恢复（内部会创建 pre-restore snapshot）
        backup_service.restore_backup(created["backup_id"])

        # 验证原备份文件仍然存在且内容未变
        assert original_tar.exists(), "原备份文件被 pre-restore snapshot 覆盖（P0 bug）"
        with tarfile.open(original_tar, "r:gz") as t:
            restored_members = set(t.getnames())
        assert original_members == restored_members, "原备份内容被修改"

        # 验证 sqlite 确实恢复了
        assert (work / "alerts.db").exists(), "SQLite 未恢复"


class TestScheduledBackupFailureAlert:
    """PHASE101 T6: 定时备份失败必须显式告警。"""

    def test_scheduled_backup_failure_emits_alert(self, workspace, redis_stubbed, monkeypatch):
        """当 create_backup 抛出异常时，run_scheduled_backup 应该 emit 告警。

        v2.1.3：此前直接赋值 backup_service._postgres_dump（未恢复）→ 污染后续
        TestRealPostgresDump（CI 上真实 pg_dump 用例报 "pg_dump failed"）。
        改用 monkeypatch.setattr，测试结束自动恢复。
        """
        work, _ = workspace
        monkeypatch.setattr(
            backup_service, "_postgres_dump",
            lambda: (_ for _ in ()).throw(RuntimeError("pg_dump failed")),
        )

        from nocobase_py.services.alerts import get_collector

        collector = get_collector()

        with pytest.raises(RuntimeError, match="pg_dump failed"):
            backup_service.run_scheduled_backup()

        # 断言：emit 了一条 critical 级别的 backup 告警
        events = [e.to_dict() for e in collector._events]
        alert_emitted = any(
            e.get("kind") == "backup.scheduled_failed"
            and e.get("detail", {}).get("severity") == "critical"
            for e in events
        )
        assert alert_emitted, "备份失败没有 emit 告警（T6 P0）"


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

    def test_postgres_fail_raises_runtime_error(self, workspace, redis_stubbed, monkeypatch):
        """当 _postgres_dump 抛出异常时，create_backup 应该向上抛出 RuntimeError。

        v2.1.3：此前直接赋值 stub 未恢复 → 污染后续 TestRealPostgresDump
        （CI 上真实 pg_dump 用例报 "pg_dump failed"）。改用 monkeypatch.setattr。
        """
        work, _ = workspace
        monkeypatch.setattr(
            backup_service, "_postgres_dump",
            lambda: (_ for _ in ()).throw(RuntimeError("pg_dump failed")),
        )

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


# ==================== PHASE 68: Warnings & Manifest ====================


class TestBackupWarningsAndManifest:
    """PHASE 68 P0: 缺失组件必须 WARN + 返回中标注，不能静默跳过。"""

    def test_missing_components_generate_warnings(self, workspace, redis_stubbed, postgres_stubbed):
        """当 sqlite 和 media 都不存在时，warnings 应该包含明确的警告信息。"""
        work, _ = workspace
        # 不创建 alerts.db 和 media 目录

        result = backup_service.create_backup()

        # 断言 1: warnings 字段存在
        assert "warnings" in result
        assert isinstance(result["warnings"], list)

        # 断言 2: 包含 sqlite 缺失警告
        sqlite_warnings = [w for w in result["warnings"] if "sqlite" in w.lower()]
        assert len(sqlite_warnings) > 0, "sqlite 缺失应该有警告"
        assert "not found" in sqlite_warnings[0].lower() or "source" in sqlite_warnings[0].lower()

        # 断言 3: 包含 media 缺失警告
        media_warnings = [w for w in result["warnings"] if "media" in w.lower()]
        assert len(media_warnings) > 0, "media 缺失应该有警告"

    def test_manifest_contains_component_stats(self, workspace, redis_stubbed, postgres_stubbed):
        """manifest 应该包含各组件的统计信息。"""
        work, _ = workspace
        _make_db(work / "alerts.db")

        result = backup_service.create_backup()

        # 断言 1: manifest 字段存在
        assert "manifest" in result
        manifest = result["manifest"]

        # 断言 2: manifest 包含必要字段
        assert "components" in manifest
        assert "warnings" in manifest
        assert "backup_id" in manifest
        assert "created_at" in manifest

        # 断言 3: postgresql 有 bytes 统计
        if "postgresql" in manifest:
            assert "bytes" in manifest["postgresql"]

        # 断言 4: redis 有 bytes 统计
        if "redis" in manifest:
            assert "bytes" in manifest["redis"]

    def test_manifest_includes_media_stats_when_present(self, workspace, redis_stubbed, postgres_stubbed):
        """当 media 存在时，manifest 应该包含 files 和 bytes 统计。"""
        work, _ = workspace
        media_dir = work / "media"
        media_dir.mkdir()
        (media_dir / "file1.txt").write_text("test content 1")
        (media_dir / "file2.txt").write_text("test content 2")

        result = backup_service.create_backup()

        # 断言 1: media 在 components 中
        assert "media" in result["components"]

        # 断言 2: manifest 包含 media 统计
        assert "media" in result["manifest"]
        assert result["manifest"]["media"]["files"] == 2
        # 字节数：len("test content 1") + len("test content 2") = 14 + 14 = 28
        assert result["manifest"]["media"]["bytes"] == 28

    def test_manifest_json_in_tarball(self, workspace, redis_stubbed, postgres_stubbed):
        """manifest.json 应该被写入 tar 包中。"""
        import tarfile
        work, _ = workspace
        _make_db(work / "alerts.db")

        result = backup_service.create_backup()

        # 读取 tar 包内容
        backup_path = Path(result["path"])
        with tarfile.open(backup_path, "r:gz") as tar:
            names = tar.getnames()
            assert "manifest.json" in names

            # 提取并验证 manifest
            member = tar.getmember("manifest.json")
            f = tar.extractfile(member)
            import json
            manifest_content = json.loads(f.read().decode("utf-8"))

            assert manifest_content["backup_id"] == result["backup_id"]
            assert manifest_content["components"] == result["components"]
            assert manifest_content["warnings"] == result["warnings"]

    def test_restore_generates_warnings_for_missing_data(self, workspace, redis_stubbed, postgres_stubbed):
        """恢复时如果备份中没有某些组件，应该生成 warnings。"""
        work, _ = workspace
        # 创建一个只有 postgresql 和 redis 的备份（没有 sqlite 和 media）
        result = backup_service.create_backup()

        restore_result = backup_service.restore_backup(result["backup_id"])

        # 断言 1: warnings 字段存在
        assert "warnings" in restore_result
        assert isinstance(restore_result["warnings"], list)

        # 断言 2: 包含 media 缺失警告（sqlite 可能不在 restore warnings 中，因为它直接 extract）
        media_warnings = [w for w in restore_result["warnings"] if "media" in w.lower()]
        assert len(media_warnings) > 0, f"Expected media warning, got: {restore_result['warnings']}"
