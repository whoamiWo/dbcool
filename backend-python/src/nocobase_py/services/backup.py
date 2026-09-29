"""W6 生产化闭环 — 备份服务（PostgreSQL + SQLite + Redis + 媒体资产）。

支持：
- 手动触发全量备份（`POST /api/admin/backup`）
- 列出备份（`GET /api/admin/backup`）
- 恢复备份（`POST /api/admin/backup/{id}/restore`）
- 定时备份（cron：`BACKUP_CRON` 环境变量，如 `0 2 * * *` = 每天 2 点）
- 备份保留（`BACKUP_RETENTION_DAYS` 环境变量，默认 7 天）

备份内容：
- PostgreSQL 主库（pg_dump -Fc 自定义格式）
- SQLite 告警库（alerts.db）
- 媒体资产目录（media/）
- Redis 快照（直连 redis-cli --rdb，无 docker 依赖）

备份文件命名：`backup_<YYYYMMDD>_<HHMMSS>.tar.gz`
存储在 `BACKUP_DIR`（默认 `./backups`）。

PHASE 58 P0-1 修复：
- 新增 PostgreSQL 主库备份（pg_dump），连接参数全部来自环境变量
- 移除 docker exec 依赖（Redis 直连，K8s 可用）
- 关键组件失败时整体失败并报错（不静默）
"""

from __future__ import annotations

import logging
import os
import subprocess
import tarfile
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from nocobase_py.config import get_settings

logger = logging.getLogger(__name__)

_settings = get_settings()
_BACKUP_DIR = Path(os.environ.get("BACKUP_DIR", "/tmp/backups"))
# PostgreSQL 主库连接参数（全部来自环境变量）
_POSTGRES_HOST = os.environ.get("POSTGRES_HOST", "localhost")
_POSTGRES_PORT = os.environ.get("POSTGRES_PORT", "5432")
_POSTGRES_USER = os.environ.get("POSTGRES_USER", "postgres")
_POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "")
_POSTGRES_DB = os.environ.get("POSTGRES_DB", "nocobase")
# SQLite 告警库（Python 端内存版 Settings 的持久化 fallback）
_SQLITE_PATHS = [Path("alerts.db")]
_MEDIA_DIR = Path("media")
# Redis 直连参数
_REDIS_HOST = os.environ.get("REDIS_HOST", _settings.redis_host)
_REDIS_PORT = int(os.environ.get("REDIS_PORT", _settings.redis_port))
_REDIS_PASSWORD = os.environ.get("REDIS_PASSWORD", "")
_RETENTION_DAYS = int(os.environ.get("BACKUP_RETENTION_DAYS", "7"))


def _now_stamp() -> str:
    return datetime.now(UTC).strftime("%Y%m%d_%H%M%S")


def _ensure_dir() -> Path:
    _BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    return _BACKUP_DIR


def _postgres_dump() -> bytes:
    """备份 PostgreSQL 主库（pg_dump -Fc 自定义格式）。
    
    fail-closed：备份失败时抛出异常，禁止静默返回空值后误报「备份成功」。
    连接参数全部来自环境变量，支持容器内（POSTGRES_HOST=postgres）和本地（localhost）。
    """
    if not _POSTGRES_DB:
        raise RuntimeError("POSTGRES_DB not configured; cannot backup PostgreSQL")
    
    env = os.environ.copy()
    env["PGPASSWORD"] = _POSTGRES_PASSWORD
    
    try:
        result = subprocess.run(
            [
                "pg_dump",
                "-h", _POSTGRES_HOST,
                "-p", _POSTGRES_PORT,
                "-U", _POSTGRES_USER,
                "-d", _POSTGRES_DB,
                "-F", "c",  # 自定义格式（便于 pg_restore 选择性恢复）
            ],
            capture_output=True,
            check=True,
            timeout=120,
            env=env,
        )
        if result.returncode == 0 and result.stdout:
            logger.info("[backup] pg_dump success: host=%s port=%s db=%s", 
                       _POSTGRES_HOST, _POSTGRES_PORT, _POSTGRES_DB)
            return result.stdout
        raise RuntimeError(f"pg_dump returned empty output: {result.stderr.decode()}")
    except subprocess.CalledProcessError as e:
        error_msg = e.stderr.decode() if e.stderr else str(e)
        logger.error("[backup] pg_dump failed: %s", error_msg)
        raise RuntimeError(f"pg_dump failed: {error_msg}") from e
    except FileNotFoundError:
        raise RuntimeError("pg_dump not found; cannot backup PostgreSQL")
    except subprocess.TimeoutExpired:
        raise RuntimeError("pg_dump timed out after 120s")
    except Exception as e:
        logger.error("[backup] PostgreSQL backup failed: %s", e)
        raise


def _redis_snapshot() -> bytes:
    """触发 Redis 持久化快照，返回 dump.rdb 字节内容。

    fail-closed：Redis 未启用或快照失败时抛出异常，禁止静默返回空值后
    误报「备份成功」。

    PHASE 58 P0-1 修复：移除 docker exec 依赖，改为直连 redis-cli --rdb。
    使用 REDIS_HOST/REDIS_PORT/REDIS_PASSWORD 环境变量，K8s 下可用。
    """
    if not _settings.redis_enabled:
        raise RuntimeError("Redis is not enabled (redis_enabled=false); cannot take snapshot")
    
    env = os.environ.copy()
    if _REDIS_PASSWORD:
        env["REDISCLI_AUTH"] = _REDIS_PASSWORD
    
    try:
        # 先触发持久化（SAVE）
        save_result = subprocess.run(
            ["redis-cli", "-h", _REDIS_HOST, "-p", str(_REDIS_PORT), "SAVE"],
            capture_output=True,
            timeout=30,
            env=env,
        )
        if save_result.returncode != 0:
            raise RuntimeError(f"redis-cli SAVE failed: {save_result.stderr.decode()}")
        
        # 直连方式：redis-cli --rdb 输出二进制
        result = subprocess.run(
            ["redis-cli", "-h", _REDIS_HOST, "-p", str(_REDIS_PORT), "--rdb", "-"],
            capture_output=True,
            check=True,
            timeout=30,
            env=env,
        )
        if result.returncode == 0 and result.stdout:
            logger.info("[backup] redis snapshot via --rdb: %s:%s", _REDIS_HOST, _REDIS_PORT)
            return result.stdout
        raise RuntimeError("redis-cli --rdb returned empty data")
    except subprocess.CalledProcessError as e:
        error_msg = e.stderr.decode() if e.stderr else str(e)
        logger.error("[backup] redis snapshot failed: %s", error_msg)
        raise RuntimeError(f"redis snapshot failed: {error_msg}") from e
    except FileNotFoundError:
        raise RuntimeError("redis-cli not found; cannot backup Redis")
    except subprocess.TimeoutExpired:
        raise RuntimeError("redis snapshot timed out")
    except Exception as e:
        logger.error("[backup] Redis snapshot failed: %s", e)
        raise


def _restore_postgres(dump_bytes: bytes) -> bool:
    """恢复 PostgreSQL 主库（pg_restore 或 psql）。
    
    返回 True 表示恢复成功。
    """
    if not _POSTGRES_DB:
        raise RuntimeError("POSTGRES_DB not configured; cannot restore PostgreSQL")
    
    env = os.environ.copy()
    env["PGPASSWORD"] = _POSTGRES_PASSWORD
    
    # 先清空数据库（可选，视恢复策略而定）
    try:
        subprocess.run(
            ["psql", "-h", _POSTGRES_HOST, "-p", _POSTGRES_PORT, 
             "-U", _POSTGRES_USER, "-d", _POSTGRES_DB,
             "-c", "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"],
            env=env,
            timeout=30,
            capture_output=True,
        )
    except Exception as e:
        logger.warning("[restore] schema reset failed (may not exist): %s", e)
    
    # 使用 pg_restore 恢复自定义格式 dump
    try:
        proc = subprocess.Popen(
            ["pg_restore", "-h", _POSTGRES_HOST, "-p", _POSTGRES_PORT,
             "-U", _POSTGRES_USER, "-d", _POSTGRES_DB,
             "--clean", "--if-exists", "--exit-on-error"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
        )
        stdout, stderr = proc.communicate(input=dump_bytes, timeout=120)
        if proc.returncode == 0:
            logger.info("[restore] pg_restore success: %s", _POSTGRES_DB)
            return True
        raise RuntimeError(f"pg_restore failed: {stderr.decode()}")
    except subprocess.TimeoutExpired:
        proc.kill()
        raise RuntimeError("pg_restore timed out after 120s")
    except Exception as e:
        logger.error("[restore] PostgreSQL restore failed: %s", e)
        raise


def create_backup() -> dict[str, Any]:
    """创建全量备份。

    返回：
        backup_id, path, size_bytes, components, created_at
    
    PHASE 58 P0-1：新增 PostgreSQL 主库备份，关键组件失败时整体失败。
    """
    stamp = _now_stamp()
    backup_id = f"backup_{stamp}"
    out_name = f"{backup_id}.tar.gz"
    out_path = _ensure_dir() / out_name
    
    components: list[str] = []
    postgres_bytes: bytes | None = None
    
    with tarfile.open(out_path, "w:gz") as tar:
        # 1. PostgreSQL 主库（关键组件，失败则整体失败）
        try:
            postgres_bytes = _postgres_dump()
            if postgres_bytes:
                import io
                pg_file = io.BytesIO(postgres_bytes)
                ti = tarfile.TarInfo(name="postgresql/dump.pg")
                ti.size = len(postgres_bytes)
                ti.mtime = time.time()
                tar.addfile(ti, pg_file)
                components.append("postgresql")
                logger.info("[backup] added postgresql/dump.pg (%d bytes)", len(postgres_bytes))
        except Exception as e:
            logger.error("[backup] PostgreSQL backup FAILED: %s", e)
            raise RuntimeError(f"PostgreSQL backup failed (critical): {e}") from e
        
        # 2. SQLite 数据库文件（alerts.db 等）
        for db_path in _SQLITE_PATHS:
            if db_path.exists():
                tar.add(db_path, arcname=f"sqlite/{db_path.name}")
                components.append("sqlite")
                logger.info("[backup] added sqlite/%s", db_path.name)
        
        # 3. 媒体资产
        if _MEDIA_DIR.exists():
            tar.add(_MEDIA_DIR, arcname="media")
            components.append("media")
            logger.info("[backup] added media/")
        
        # 4. Redis 快照（关键组件）
        try:
            rdb_bytes = _redis_snapshot()
            if rdb_bytes:
                import io
                rdb_file = io.BytesIO(rdb_bytes)
                ti = tarfile.TarInfo(name="redis/dump.rdb")
                ti.size = len(rdb_bytes)
                ti.mtime = time.time()
                tar.addfile(ti, rdb_file)
                components.append("redis")
                logger.info("[backup] added redis/dump.rdb (%d bytes)", len(rdb_bytes))
        except Exception as e:
            logger.error("[backup] Redis backup FAILED: %s", e)
            raise RuntimeError(f"Redis backup failed (critical): {e}") from e
    
    size = out_path.stat().st_size
    logger.info("[backup] created %s (%d bytes, components=%s)", out_path, size, components)
    
    return {
        "backup_id": backup_id,
        "path": str(out_path),
        "size_bytes": size,
        "components": components,
        "created_at": datetime.now(UTC).isoformat(),
        "postgres_bytes": len(postgres_bytes) if postgres_bytes else 0,
    }


def list_backups() -> list[dict[str, Any]]:
    """列出所有备份（按时间倒序）。"""
    if not _BACKUP_DIR.exists():
        return []
    result = []
    for f in sorted(_BACKUP_DIR.glob("backup_*.tar.gz"), reverse=True):
        stat = f.stat()
        result.append({
            # 用 .tar.gz 后缀匹配 restore_backup 的查找模式
            "backup_id": f.name.removesuffix(".tar.gz"),
            "path": str(f),
            "size_bytes": stat.st_size,
            "created_at": datetime.fromtimestamp(stat.st_mtime, UTC).isoformat(),
        })
    return result


def restore_backup(backup_id: str) -> dict[str, Any]:
    """从备份恢复（覆盖 PostgreSQL + SQLite + media + redis）。

    返回恢复结果。
    
    PHASE 58 P0-1：新增 PostgreSQL 恢复路径。
    """
    backup_path = _BACKUP_DIR / f"{backup_id}.tar.gz"
    if not backup_path.exists():
        raise FileNotFoundError(f"backup not found: {backup_id}")

    restored: list[str] = []
    postgres_bytes: bytes | None = None
    
    with tarfile.open(backup_path, "r:gz") as tar:
        members = tar.getmembers()
        for m in members:
            if m.name.startswith("postgresql/"):
                # 暂存 PostgreSQL dump，稍后恢复
                data = tar.extractfile(m)
                if data:
                    postgres_bytes = data.read()
                    restored.append("postgresql")
            elif m.name.startswith("sqlite/"):
                tar.extract(m, path=".")
                src = Path(m.name)
                if src.exists():
                    src.rename(Path(m.name).name)
                    restored.append("sqlite")
            elif m.name.startswith("media/"):
                tar.extract(m, path=".")
                restored.append("media")
            elif m.name.startswith("redis/"):
                data = tar.extractfile(m)
                if data:
                    Path("dump.rdb").write_bytes(data.read())
                    restored.append("redis")

    # 恢复 PostgreSQL（关键组件，失败则整体失败）
    if postgres_bytes:
        try:
            _restore_postgres(postgres_bytes)
            logger.info("[restore] PostgreSQL restored successfully")
        except Exception as e:
            logger.error("[restore] PostgreSQL restore FAILED: %s", e)
            raise RuntimeError(f"PostgreSQL restore failed (critical): {e}") from e

    logger.info("[restore] %s: %s", backup_id, restored)
    return {"backup_id": backup_id, "restored": restored}


def prune_old_backups(retention_days: int | None = None) -> int:
    """删除超过保留期的备份，返回删除数量。"""
    days = retention_days if retention_days is not None else _RETENTION_DAYS
    if not _BACKUP_DIR.exists():
        return 0
    cutoff = time.time() - days * 86400
    deleted = 0
    for f in _BACKUP_DIR.glob("backup_*.tar.gz"):
        if f.stat().st_mtime < cutoff:
            f.unlink()
            deleted += 1
            logger.info("[backup] pruned old backup: %s", f)
    return deleted


def run_scheduled_backup() -> dict[str, Any] | None:
    """定时备份入口（由 scheduler cron 调用）。"""
    logger.info("[backup] running scheduled backup")
    result = create_backup()
    prune_old_backups()
    return result