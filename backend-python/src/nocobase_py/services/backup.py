"""W6 生产化闭环 — 备份服务（SQLite + Redis + 媒体资产）。

支持：
- 手动触发全量备份（`POST /api/admin/backup`）
- 列出备份（`GET /api/admin/backup`）
- 恢复备份（`POST /api/admin/backup/{id}/restore`）
- 定时备份（cron：`BACKUP_CRON` 环境变量，如 `0 2 * * *` = 每天 2 点）
- 备份保留（`BACKUP_RETENTION_DAYS` 环境变量，默认 7 天）

备份内容：
- SQLite 数据库文件（`nocobase.db`）
- 媒体资产目录（`media/`）
- Redis 快照（`redis-cli save` + 复制 dump.rdb）

备份文件命名：`backup_<YYYYMMDD>_<HHMMSS>.tar.gz`
存储在 `BACKUP_DIR`（默认 `./backups`）。
"""

from __future__ import annotations

import logging
import os
import tarfile
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from nocobase_py.config import get_settings

logger = logging.getLogger(__name__)

_settings = get_settings()
_BACKUP_DIR = Path(os.environ.get("BACKUP_DIR", "/tmp/backups"))
# Python 端当前为内存版 Settings（Phase52 遗留），无持久化 DB；
# 备份主数据源为本地 SQLite 告警库（alerts.db）+ 媒体资产 + Redis 快照。
_DB_PATHS = [Path("alerts.db")]
_MEDIA_DIR = Path("media")
_REDIS_HOST = _settings.redis_host
_REDIS_PORT = _settings.redis_port
_RETENTION_DAYS = int(os.environ.get("BACKUP_RETENTION_DAYS", "7"))


def _now_stamp() -> str:
    return datetime.now(UTC).strftime("%Y%m%d_%H%M%S")


def _ensure_dir() -> Path:
    _BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    return _BACKUP_DIR


def _redis_snapshot() -> bytes:
    """触发 Redis 持久化快照，返回 dump.rdb 字节内容。

    fail-closed：Redis 未启用或快照失败时抛出异常，禁止静默返回空值后
    误报「备份成功」。

    使用 docker exec redis-cli --rdb 流式获取 RDB，避免依赖本地 redis-cli。
    """
    if not _settings.redis_enabled:
        raise RuntimeError("Redis is not enabled (redis_enabled=false); cannot take snapshot")
    import subprocess
    try:
        # 尝试多个可能的 Redis 容器名称
        for container_name in ["nocobase-redis", "redis"]:
            result = subprocess.run(
                ["docker", "exec", container_name, "redis-cli", "--rdb", "-"],
                capture_output=True,
                check=True,
                timeout=30,
            )
            if result.returncode == 0 and result.stdout:
                return result.stdout
        raise RuntimeError("所有 Redis 容器都未返回 RDB 数据")
    except subprocess.CalledProcessError as e:
        logger.error("[backup] docker exec redis-cli --rdb 失败: %s", e.stderr.decode() if e.stderr else e)
        raise RuntimeError(f"docker exec redis-cli --rdb 失败: {e}") from e
    except FileNotFoundError:
        raise RuntimeError("docker 命令未找到，无法获取 Redis 快照")
    except Exception as e:
        logger.error("[backup] Redis snapshot failed: %s", e)
        raise


def create_backup() -> dict[str, Any]:
    """创建全量备份。

    返回：
        backup_id, path, size_bytes, components, created_at
    """
    stamp = _now_stamp()
    backup_id = f"backup_{stamp}"
    out_name = f"{backup_id}.tar.gz"
    out_path = _ensure_dir() / out_name

    components: list[str] = []
    with tarfile.open(out_path, "w:gz") as tar:
        # 1. SQLite 数据库文件（alerts.db 等）
        for db_path in _DB_PATHS:
            if db_path.exists():
                tar.add(db_path, arcname=f"db/{db_path.name}")
                components.append("db")
                logger.info("[backup] added db/%s", db_path.name)

        # 2. 媒体资产
        if _MEDIA_DIR.exists():
            tar.add(_MEDIA_DIR, arcname="media")
            components.append("media")
            logger.info("[backup] added media/")

        # 3. Redis 快照
        rdb_bytes = _redis_snapshot()
        if rdb_bytes:
            import io
            rdb_file = io.BytesIO(rdb_bytes)
            ti = tarfile.TarInfo(name="redis/dump.rdb")
            ti.size = len(rdb_bytes)
            ti.mtime = time.time()
            tar.addfile(ti, rdb_file)
            components.append("redis")
            logger.info("[backup] added redis/dump.rdb")

    size = out_path.stat().st_size
    logger.info("[backup] created %s (%d bytes, components=%s)", out_path, size, components)

    return {
        "backup_id": backup_id,
        "path": str(out_path),
        "size_bytes": size,
        "components": components,
        "created_at": datetime.now(UTC).isoformat(),
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
    """从备份恢复（覆盖当前 db + media + redis）。

    返回恢复结果。
    """
    backup_path = _BACKUP_DIR / f"{backup_id}.tar.gz"
    if not backup_path.exists():
        raise FileNotFoundError(f"backup not found: {backup_id}")

    restored: list[str] = []
    with tarfile.open(backup_path, "r:gz") as tar:
        members = tar.getmembers()
        for m in members:
            if m.name.startswith("db/"):
                tar.extract(m, path=".")
                # 还原到工作目录根（db/alerts.db -> ./alerts.db）
                src = Path(m.name)
                if src.exists():
                    src.rename(Path(m.name).name)
                    restored.append("db")
            elif m.name.startswith("media/"):
                tar.extract(m, path=".")
                restored.append("media")
            elif m.name.startswith("redis/"):
                data = tar.extractfile(m)
                if data:
                    Path("dump.rdb").write_bytes(data.read())
                    restored.append("redis")

    logger.info("[backup] restored %s: %s", backup_id, restored)
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