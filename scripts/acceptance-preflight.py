#!/usr/bin/env python3
"""验收目标固定预检（TY4 WP2）。

在 release 门禁 / 真实迁移 / 外部验收前，强制声明并核实目标栈，
杜绝脚本默认打到 localhost:8080 / 默认 nocobase-postgres（TY4 G2）。

必须显式设置的环境变量（缺一即失败）：
    ACCEPTANCE_MODE=1            显式确认进入验收模式
    BASE_URL                     目标后端（如 http://127.0.0.1:18082）
    PG_CONTAINER                 目标 postgres 容器名（不得是默认 nocobase-postgres）
    PG_STRICT=1                  禁止 _db 回退 host psql / 任意容器
    BACKUP_DIR                   验收专用备份目录（防止选到默认栈旧备份）
    COMPOSE_PROJECT_NAME         目标 compose project（默认要求 nocobase-acceptance 类隔离 project）

核实项（全部通过才退出 0）：
    1. 上述变量齐备且非默认值语义（PG_CONTAINER 不得为 nocobase-postgres）；
    2. PG_CONTAINER 容器存在且其 compose project label == COMPOSE_PROJECT_NAME；
    3. 在容器内实查 current_database() / inet_server_port()；
    4. GET {BASE_URL}/api/health 返回 200；
    5. BASE_URL 端口映射到同一 compose project 内的容器（挡住 18082 → 默认栈 backend）；
    6. 打印脱敏目标指纹：base URL / project / backend / container / 数据库 / 端口 / 当前 commit。

用法：
    ACCEPTANCE_MODE=1 BASE_URL=http://127.0.0.1:18082 PG_CONTAINER=nocobase-acc-postgres \
    PG_STRICT=1 BACKUP_DIR=target/acceptance/backups COMPOSE_PROJECT_NAME=nocobase-acceptance \
    python3 scripts/acceptance-preflight.py

退出码：0 = 目标固定成功；1 = 任一项缺失/不匹配。
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.request
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

failures: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)
    print(f"  ✗ FAIL {msg}")


def ok(msg: str) -> None:
    print(f"  ✓ {msg}")


def docker(args: list[str]) -> tuple[int, str]:
    r = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=30)
    return r.returncode, (r.stdout or "").strip()


def main() -> int:
    print("=" * 60)
    print("验收目标固定预检（acceptance-preflight）")
    print("=" * 60)

    # ---- 1) 必填环境变量 ----
    base_url = os.environ.get("BASE_URL", "").rstrip("/")
    pg_container = os.environ.get("PG_CONTAINER", "")
    pg_db = os.environ.get("PGDATABASE", "nocobase")
    pg_user = os.environ.get("PGUSER", "nocobase")
    backup_dir = os.environ.get("BACKUP_DIR", "")
    project = os.environ.get("COMPOSE_PROJECT_NAME", "")

    for var in ("ACCEPTANCE_MODE", "BASE_URL", "PG_CONTAINER", "PG_STRICT",
                "BACKUP_DIR", "COMPOSE_PROJECT_NAME"):
        if not os.environ.get(var):
            fail(f"缺少环境变量 {var}")
    if failures:
        print("\n💡 示例：ACCEPTANCE_MODE=1 BASE_URL=http://127.0.0.1:18082 "
              "PG_CONTAINER=nocobase-acc-postgres PG_STRICT=1 "
              "BACKUP_DIR=target/acceptance/backups COMPOSE_PROJECT_NAME=nocobase-acceptance "
              "python3 scripts/acceptance-preflight.py")
        return 1
    if os.environ.get("ACCEPTANCE_MODE") != "1":
        fail("ACCEPTANCE_MODE 必须为 1")
    if os.environ.get("PG_STRICT") != "1":
        fail("PG_STRICT 必须为 1")
    if pg_container == "nocobase-postgres":
        fail("PG_CONTAINER 是默认栈容器 nocobase-postgres；验收必须使用隔离容器")
    parsed = urllib.parse.urlparse(base_url)
    if parsed.hostname in {"localhost", "127.0.0.1", "::1"} and parsed.port == 8080:
        fail("BASE_URL 指向默认栈 8080；验收必须使用隔离栈端口（如 18082）")
    if not backup_dir or Path(backup_dir).resolve() == (ROOT / "backend-python" / "backups").resolve():
        fail("BACKUP_DIR 不得指向默认备份目录 backend-python/backups")

    # ---- 2) 容器存在且属于声明的 compose project ----
    db_name = ""
    server_port = ""
    if not failures:
        rc, cid = docker(["ps", "-q", "--filter", f"name=^{pg_container}$"])
        if rc != 0 or not cid:
            fail(f"运行中找不到容器 {pg_container!r}（PG_STRICT=1 不允许回退 host psql/任意容器）")
        else:
            rc, labels = docker(["inspect", "-f", "{{json .Config.Labels}}", cid])
            labels = json.loads(labels) if rc == 0 and labels else {}
            actual_project = labels.get("com.docker.compose.project", "")
            if actual_project != project:
                fail(f"容器 compose project={actual_project!r}，与 COMPOSE_PROJECT_NAME={project!r} 不一致")
            if actual_project == "nocobase":
                fail("目标容器属于默认 compose project 'nocobase'；验收必须使用隔离 project")
            ok(f"容器 {pg_container} 属于隔离 project {project}")

    # ---- 3) 实查数据库指纹（只在指定容器内，不回退）----
    if not failures:
        sql = "select current_database() || '|' || inet_server_port()"
        r = subprocess.run(
            # -h 127.0.0.1 强制容器内走 TCP：inet_server_port() 在 unix socket 下为 NULL
            ["docker", "exec", pg_container, "psql", "-h", "127.0.0.1",
             "-U", pg_user, "-d", pg_db, "-t", "-A", "-c", sql],
            capture_output=True, text=True, timeout=30)
        if r.returncode != 0 or "|" not in (r.stdout or ""):
            fail(f"数据库实查失败：{(r.stderr or r.stdout or '').strip()[:200]}")
        else:
            db_name, server_port = (r.stdout.strip().split("|") + [""])[:2]
            if db_name != pg_db:
                fail(f"current_database()={db_name!r} 与 PGDATABASE={pg_db!r} 不一致")
            else:
                ok(f"数据库指纹 current_database()={db_name} inet_server_port()={server_port}")

    # ---- 4) HTTP health ----
    if not failures:
        try:
            with urllib.request.urlopen(f"{base_url}/api/health", timeout=10) as resp:
                body = resp.read()[:200]
                ok(f"GET {base_url}/api/health → {resp.status}")
        except Exception as e:  # noqa: BLE001
            fail(f"GET {base_url}/api/health 失败：{e}（目标栈未启动或指错地址）")

    # ---- 4b) BASE_URL 必须指向同一 compose project 的容器（D6）----
    # 仅挡端口检查不够：18082 也可以手工映射到默认栈 java 容器。
    backend_names: list[str] = []
    is_local = parsed.hostname in {"localhost", "127.0.0.1", "::1"}
    if not failures and is_local:
        url_port = parsed.port or (443 if parsed.scheme == "https" else 80)
        rc, cids = docker(["ps", "-q", "--filter",
                           f"label=com.docker.compose.project={project}"])
        for cid in (cids.split() if rc == 0 and cids else []):
            rc2, ports = docker(["port", cid])
            host_ports = set()
            for line in (ports.splitlines() if rc2 == 0 else []):
                # 例：8080/tcp -> 0.0.0.0:18082
                host_ports.add(line.rsplit(":", 1)[-1].strip())
            if str(url_port) in host_ports:
                rc3, name = docker(["inspect", "-f", "{{.Name}}", cid])
                backend_names.append(name.lstrip("/") if rc3 == 0 else cid)
        if not backend_names:
            fail(f"BASE_URL 端口 {url_port} 未映射到 project {project!r} 内的任何容器；"
                 "拒绝把隔离端口指向默认栈或其它栈的 backend")
        else:
            ok(f"BASE_URL 端口 {url_port} 对应本栈容器：{', '.join(backend_names)}")
    elif not failures:
        ok(f"BASE_URL 非本机地址，无法本地核实 backend 同栈；需人工确认 {base_url} 属于 {project}")

    # ---- 5) 打印目标指纹 ----
    commit = ""
    try:
        r = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"],
                           capture_output=True, text=True, timeout=10)
        commit = r.stdout.strip()
    except Exception:  # noqa: BLE001
        pass
    print("\n验收目标指纹（脱敏）：")
    print(f"  BASE_URL            = {base_url or '(未设置)'}")
    print(f"  COMPOSE_PROJECT     = {project or '(未设置)'}")
    print(f"  BACKEND_CONTAINER   = {', '.join(backend_names) or '(未核实)'}")
    print(f"  PG_CONTAINER        = {pg_container or '(未设置)'}")
    print(f"  PGDATABASE / port   = {db_name or '(未实查)'} / {server_port or '(未实查)'}")
    print(f"  BACKUP_DIR          = {backup_dir or '(未设置)'}")
    print(f"  git commit          = {commit}")

    if failures:
        print(f"\n❌ 验收目标预检未通过（{len(failures)} 项）；修正前不得运行 release 门禁/迁移/外部验收")
        return 1
    print("\n✅ 验收目标已固定，所有后续脚本将作用于上述目标")
    return 0


if __name__ == "__main__":
    sys.exit(main())
