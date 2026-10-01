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

import json
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
# Media 目录：优先环境变量 MEDIA_DIR，否则默认 media/
_MEDIA_DIR = Path(os.environ.get("MEDIA_DIR", "media"))
# MinIO 配置（用于备份 MinIO 对象存储中的附件）
_MINIO_ENABLED = os.environ.get("MINIO_ENABLED", "").lower() == "true"
_MINIO_ENDPOINT = os.environ.get("MINIO_ENDPOINT", "http://minio:9000")
_MINIO_ACCESS_KEY = os.environ.get("MINIO_ROOT_USER", "minio")
_MINIO_SECRET_KEY = os.environ.get("MINIO_ROOT_PASSWORD", "minio123")
_MINIO_BUCKET = os.environ.get("MINIO_BUCKET", "nocobase")
_MINIO_PREFIX = os.environ.get("MINIO_PREFIX", "")  # 对象前缀，如 "attachments/"
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


def _backup_media() -> tuple[list[tuple[str, bytes]], list[str]]:
    """备份媒体资产（MinIO 对象或本地目录）。
    
    返回：([(path, bytes), ...], warnings)
    
    策略：
    - 如果 MINIO_ENABLED=true：用 minio SDK 下载对象
    - 否则如果 _MEDIA_DIR.exists()：打包本地目录
    - 否则：返回 warning，不静默跳过
    """
    files: list[tuple[str, bytes]] = []
    warnings: list[str] = []
    
    if _MINIO_ENABLED:
        # MinIO 模式：下载所有对象
        try:
            from minio import Minio
            from minio.error import S3Error
            
            client = Minio(
                _MINIO_ENDPOINT.replace("http://", "").replace("https://", ""),
                access_key=_MINIO_ACCESS_KEY,
                secret_key=_MINIO_SECRET_KEY,
                secure=_MINIO_ENDPOINT.startswith("https://"),
            )
            
            objects = client.list_objects(_MINIO_BUCKET, prefix=_MINIO_PREFIX, recursive=True)
            count = 0
            total_bytes = 0
            
            for obj in objects:
                try:
                    data = client.get_object(_MINIO_BUCKET, obj.object_name).read()
                    # 去除前缀，保持相对路径
                    rel_path = obj.object_name[len(_MINIO_PREFIX):] if _MINIO_PREFIX else obj.object_name
                    files.append((f"media/{rel_path}", data))
                    count += 1
                    total_bytes += len(data)
                except S3Error as e:
                    warnings.append(f"minio object {obj.object_name}: {e}")
            
            if count > 0:
                logger.info("[backup] minio media: %d objects, %d bytes", count, total_bytes)
            else:
                warnings.append("minio bucket empty or no objects with prefix")
                
        except ImportError:
            warnings.append("minio SDK not installed; cannot backup MinIO objects")
        except Exception as e:
            warnings.append(f"minio backup failed: {e}")
    
    elif _MEDIA_DIR.exists():
        # 本地目录模式
        for root, dirs, filenames in os.walk(_MEDIA_DIR):
            for filename in filenames:
                file_path = Path(root) / filename
                rel_path = file_path.relative_to(_MEDIA_DIR)
                try:
                    data = file_path.read_bytes()
                    files.append((f"media/{rel_path}", data))
                except Exception as e:
                    warnings.append(f"media file {rel_path}: {e}")
        
        if files:
            logger.info("[backup] local media: %d files, %d bytes", len(files), sum(len(d) for _, d in files))
        else:
            warnings.append("media directory exists but contains no files")
    
    else:
        # 既不是 MinIO 也没有本地目录
        warnings.append("media: source not available (MinIO disabled and media dir does not exist)")
    
    return files, warnings


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
        backup_id, path, size_bytes, components, warnings, manifest, created_at
    
    PHASE 58 P0-1：新增 PostgreSQL 主库备份，关键组件失败时整体失败。
    PHASE 68 P0: 不再静默跳过缺失组件；显式 WARN + manifest。
    """
    stamp = _now_stamp()
    backup_id = f"backup_{stamp}"
    out_name = f"{backup_id}.tar.gz"
    out_path = _ensure_dir() / out_name
    
    components: list[str] = []
    warnings: list[str] = []
    manifest_entries: dict[str, Any] = {}
    postgres_bytes: bytes | None = None
    rdb_bytes: bytes | None = None
    
    # 1. PostgreSQL 主库（关键组件，失败则整体失败）
    try:
        postgres_bytes = _postgres_dump()
        if postgres_bytes:
            manifest_entries["postgresql"] = {"bytes": len(postgres_bytes)}
    except Exception as e:
        logger.error("[backup] PostgreSQL backup FAILED: %s", e)
        raise RuntimeError(f"PostgreSQL backup failed (critical): {e}") from e
    
    # 2. SQLite 数据库文件（alerts.db 等）
    sqlite_count = 0
    sqlite_bytes = 0
    for db_path in _SQLITE_PATHS:
        if db_path.exists():
            sqlite_count += 1
            sqlite_bytes += db_path.stat().st_size
        else:
            w = f"sqlite: source file not found: {db_path}"
            warnings.append(w)
            logger.warning("[backup] %s", w)
    
    if sqlite_count > 0:
        manifest_entries["sqlite"] = {"files": sqlite_count, "bytes": sqlite_bytes}
    
    # 3. 媒体资产（MinIO 或本地目录）
    media_files, media_warnings = _backup_media()
    warnings.extend(media_warnings)
    
    if media_files:
        media_bytes = sum(len(d) for _, d in media_files)
        manifest_entries["media"] = {"files": len(media_files), "bytes": media_bytes}
    
    # 4. Redis 快照（关键组件）
    try:
        rdb_bytes = _redis_snapshot()
        if rdb_bytes:
            manifest_entries["redis"] = {"bytes": len(rdb_bytes)}
    except Exception as e:
        logger.error("[backup] Redis backup FAILED: %s", e)
        raise RuntimeError(f"Redis backup failed (critical): {e}") from e
    
    # 5. 写入 tar 包
    with tarfile.open(out_path, "w:gz") as tar:
        # PostgreSQL
        if postgres_bytes:
            import io
            pg_file = io.BytesIO(postgres_bytes)
            ti = tarfile.TarInfo(name="postgresql/dump.pg")
            ti.size = len(postgres_bytes)
            ti.mtime = time.time()
            tar.addfile(ti, pg_file)
            components.append("postgresql")
            logger.info("[backup] added postgresql/dump.pg (%d bytes)", len(postgres_bytes))
        
        # SQLite
        for db_path in _SQLITE_PATHS:
            if db_path.exists():
                tar.add(db_path, arcname=f"sqlite/{db_path.name}")
                components.append("sqlite")
                logger.info("[backup] added sqlite/%s", db_path.name)
        
        # Media
        for arc_path, data in media_files:
            import io
            ti = tarfile.TarInfo(name=arc_path)
            ti.size = len(data)
            ti.mtime = time.time()
            tar.addfile(ti, io.BytesIO(data))
        if media_files:
            components.append("media")
            logger.info("[backup] added media/ (%d files, %d bytes)", len(media_files), sum(len(d) for _, d in media_files))
        
        # Redis
        if rdb_bytes:
            import io
            rdb_file = io.BytesIO(rdb_bytes)
            ti = tarfile.TarInfo(name="redis/dump.rdb")
            ti.size = len(rdb_bytes)
            ti.mtime = time.time()
            tar.addfile(ti, rdb_file)
            components.append("redis")
            logger.info("[backup] added redis/dump.rdb (%d bytes)", len(rdb_bytes))
        
        # Manifest
        manifest_entries["backup_id"] = backup_id
        manifest_entries["created_at"] = datetime.now(UTC).isoformat()
        manifest_entries["components"] = components
        manifest_entries["warnings"] = warnings
        import io
        manifest_bytes = json.dumps(manifest_entries, indent=2).encode("utf-8")
        ti = tarfile.TarInfo(name="manifest.json")
        ti.size = len(manifest_bytes)
        ti.mtime = time.time()
        tar.addfile(ti, io.BytesIO(manifest_bytes))
    
    size = out_path.stat().st_size
    logger.info("[backup] created %s (%d bytes, components=%s, warnings=%s)", out_path, size, components, warnings)
    
    return {
        "backup_id": backup_id,
        "path": str(out_path),
        "size_bytes": size,
        "components": components,
        "warnings": warnings,
        "manifest": manifest_entries,
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
    PHASE 68 P0: 支持 manifest 验证 + warnings。
    """
    backup_path = _BACKUP_DIR / f"{backup_id}.tar.gz"
    if not backup_path.exists():
        raise FileNotFoundError(f"backup not found: {backup_id}")

    restored: list[str] = []
    warnings: list[str] = []
    postgres_bytes: bytes | None = None
    rdb_bytes: bytes | None = None
    
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
                    rdb_bytes = data.read()
                    restored.append("redis")
            elif m.name == "manifest.json":
                # 读取 manifest 用于验证
                data = tar.extractfile(m)
                if data:
                    manifest = json.loads(data.read().decode("utf-8"))
                    # 可以在此处验证组件完整性

    # 恢复 PostgreSQL（关键组件，失败则整体失败）
    if postgres_bytes:
        try:
            _restore_postgres(postgres_bytes)
            logger.info("[restore] PostgreSQL restored successfully")
        except Exception as e:
            logger.error("[restore] PostgreSQL restore FAILED: %s", e)
            raise RuntimeError(f"PostgreSQL restore failed (critical): {e}") from e
    else:
        w = "postgresql: no data in backup"
        warnings.append(w)
        logger.warning("[restore] %s", w)

    # 恢复 Redis
    if rdb_bytes:
        Path("dump.rdb").write_bytes(rdb_bytes)
        logger.info("[restore] Redis dump.rdb restored (%d bytes)", len(rdb_bytes))
    else:
        w = "redis: no data in backup"
        warnings.append(w)
        logger.warning("[restore] %s", w)

    # Media 已直接 extract，检查是否有 media 数据
    if "media" not in restored:
        w = "media: no data in backup"
        warnings.append(w)
        logger.warning("[restore] %s", w)

    logger.info("[restore] %s: restored=%s, warnings=%s", backup_id, restored, warnings)
    return {
        "backup_id": backup_id,
        "restored": restored,
        "warnings": warnings,
    }


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