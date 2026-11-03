"""验证脚本共用的数据库直连工具。

背景：多个 *-verify.py 原先各自写死
``docker exec nocobase-postgres psql ...``，但 CI（GitHub Actions services
容器）里没有名为 ``nocobase-postgres`` 的容器，命令必然失败且错误被吞掉，
表现为「查询返回空」——例如 tenant-isolation 报 "Could not get valid admin
hash"、saas-readiness 报 T1.1 缺全部字段。这不是数据问题，是连接方式问题。

本模块统一两条路径：
1. 本机有 psql → 直连 PGHOST:PGPORT（CI 走这条）；
2. 否则找 docker 容器（本地 docker-compose 走这条）。
"""

import os
import shutil
import subprocess

PG_USER = os.environ.get("PGUSER", "nocobase")
PG_DB = os.environ.get("PGDATABASE", "nocobase")
PG_HOST = os.environ.get("PGHOST", "localhost")
PG_PORT = os.environ.get("PGPORT", "5432")
PG_PASSWORD = os.environ.get("PGPASSWORD", "test_password")

# 本地 compose 的容器名；仅作为回退时的优先候选
_COMPOSE_CONTAINER = "nocobase-postgres"


def _psql_args():
    return ["psql", "-h", PG_HOST, "-p", str(PG_PORT),
            "-U", PG_USER, "-d", PG_DB, "-t", "-A"]


def _docker_container() -> str | None:
    """返回可用于 psql 的容器 ID：优先 nocobase-postgres，其次任意 postgres 容器。"""
    if not shutil.which("docker"):
        return None
    try:
        out = subprocess.run(
            ["docker", "ps", "--format", "{{.ID}}\t{{.Names}}\t{{.Image}}"],
            capture_output=True, text=True, timeout=30
        ).stdout or ""
    except Exception:  # noqa: BLE001
        return None

    rows = [ln.split("\t") for ln in out.splitlines() if ln.count("\t") == 2]
    for cid, name, _img in rows:
        if name == _COMPOSE_CONTAINER:
            return cid
    for cid, _name, img in rows:
        if "postgres" in img.lower():
            return cid
    return None


def db_sql(sql: str, timeout: int = 120):
    """执行 SQL，返回 (ok, stdout, stderr)。

    两条路径都失败时，stderr 汇总两条路径的错误，便于判断是环境问题还是 SQL 问题。
    """
    errors = []

    # 1) 本机 psql（CI 场景）
    if shutil.which("psql"):
        env = dict(os.environ, PGPASSWORD=PG_PASSWORD)
        try:
            r = subprocess.run(_psql_args() + ["-c", sql], capture_output=True,
                               text=True, timeout=timeout, env=env)
            if r.returncode == 0:
                return True, (r.stdout or "").strip(), ""
            errors.append((r.stderr or "").strip())
        except Exception as e:  # noqa: BLE001
            errors.append(f"psql 调用异常: {e}")

    # 2) docker exec 回退（本地 compose 场景）
    cid = _docker_container()
    if cid:
        try:
            r = subprocess.run(
                ["docker", "exec", cid, "psql", "-U", PG_USER, "-d", PG_DB,
                 "-t", "-A", "-c", sql],
                capture_output=True, text=True, timeout=timeout
            )
            if r.returncode == 0:
                return True, (r.stdout or "").strip(), ""
            errors.append((r.stderr or "").strip())
        except Exception as e:  # noqa: BLE001
            errors.append(f"docker exec 调用异常: {e}")

    if not errors:
        errors.append("无可用的 psql / docker，无法连接数据库")
    return False, "", " | ".join(e for e in errors if e)
