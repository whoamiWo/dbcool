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

可选的环境变量：
    BACKEND_SERVICE              目标后端 compose 服务名（默认 backend-java）

核实项（全部通过才退出 0）：
    1. 上述变量齐备且非默认值语义（PG_CONTAINER 不得为 nocobase-postgres）；
    2. PG_CONTAINER 容器存在且其 compose project label == COMPOSE_PROJECT_NAME；
    3. 在容器内实查 current_database() / inet_server_port()；
    4. GET {BASE_URL}/api/health 返回 200；
    5. BASE_URL 端口映射到同一 compose project 内的容器（挡住 18082 → 默认栈 backend）；
       且该容器 com.docker.compose.service == BACKEND_SERVICE
       （挡住 18082 → 本栈 frontend/minio，E5）；
    6. 后端容器的数据源环境变量（SPRING_DATASOURCE_URL / PGHOST / POSTGRES_HOST / DB_HOST）
       指向 PG_CONTAINER 或其 compose service 名（E5）；
    7. 打印脱敏目标指纹：base URL / project / backend / container / 数据库 / 端口 / 当前 commit。

用法：
    ACCEPTANCE_MODE=1 BASE_URL=http://127.0.0.1:18082 PG_CONTAINER=nocobase-acc-postgres \
    PG_STRICT=1 BACKUP_DIR=target/acceptance/backups COMPOSE_PROJECT_NAME=nocobase-acceptance \
    python3 scripts/acceptance-preflight.py

退出码：0 = 目标固定成功；1 = 任一项缺失/不匹配。
"""
from __future__ import annotations

import json
import os
import re
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


def compose_labels(cid: str) -> dict:
    rc, raw = docker(["inspect", "-f", "{{json .Config.Labels}}", cid])
    try:
        return json.loads(raw) if rc == 0 and raw else {}
    except json.JSONDecodeError:
        return {}


DB_ENV_KEYS = ("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_JDBC_URL",
               "JDBC_DATABASE_URL", "DATABASE_URL",
               "PGHOST", "POSTGRES_HOST", "DB_HOST")


def backend_db_env(cid: str) -> tuple[str, str]:
    """返回 backend 容器内用于定位数据库的 (环境变量名, 值)；找不到返回 ("", "")。"""
    rc, raw = docker(["inspect", "-f", "{{json .Config.Env}}", cid])
    try:
        env = json.loads(raw) if rc == 0 and raw else []
    except json.JSONDecodeError:
        env = []
    kv = {}
    for item in env if isinstance(env, list) else []:
        if isinstance(item, str) and "=" in item:
            k, v = item.split("=", 1)
            kv[k] = v
    for key in DB_ENV_KEYS:
        if kv.get(key):
            return key, kv[key]
    return "", ""


def db_host_of(value: str) -> str:
    """从 JDBC/URL 或纯主机名中取出主机部分。"""
    m = re.search(r"//(?:[^/@]+@)?([^:/?,;]+)", value)
    if m:
        return m.group(1)
    return value.split(":")[0].strip()


def check_backend_same_network(pg_container: str, backend_names: list[str]) -> list[str]:
    """E6：backend 与 PG_CONTAINER 必须共享至少一个 Docker 网络。

    2026-11-12 实测事故：compose 文件顺序错误（isolation override 放在
    observability overlay 之前）导致 backend 被接到默认栈 nocobase-net，
    服务名 postgres/redis 解析到默认栈容器——env 字符串检查（E5）全部通过，
    但验收读写实际落到默认库。只有数据面的网络成员关系能证明同栈。
    """
    errs: list[str] = []
    rc, raw = docker(["inspect", "-f", "{{json .NetworkSettings.Networks}}", pg_container])
    try:
        pg_nets = set(json.loads(raw).keys()) if rc == 0 and raw else set()
    except json.JSONDecodeError:
        pg_nets = set()
    if not pg_nets:
        return [f"无法读取 {pg_container} 的网络信息，拒绝继续"]
    for name in backend_names:
        rc, raw = docker(["inspect", "-f", "{{json .NetworkSettings.Networks}}", name])
        try:
            nets = set(json.loads(raw).keys()) if rc == 0 and raw else set()
        except json.JSONDecodeError:
            nets = set()
        shared = pg_nets & nets
        if not shared:
            errs.append(f"后端容器 {name} 与 {pg_container} 不共享任何 Docker 网络"
                        f"（backend={sorted(nets) or '(无)'} pg={sorted(pg_nets)}）——"
                        "服务名解析会落到其它栈，验收数据面未隔离")
        else:
            # 进一步实证：backend 内解析 PG service 名得到的 IP 必须等于
            # PG 容器在共享网络上的 IP（排除 DNS 指向其它栈的同名服务）。
            for net in sorted(shared):
                rc_ip, pg_ip = docker([
                    "inspect", "-f",
                    f'{{{{with index .NetworkSettings.Networks "{net}"}}}}{{{{.IPAddress}}}}{{{{end}}}}',
                    pg_container])
                if rc_ip != 0 or not pg_ip:
                    continue
                rc_dns, resolved = docker(["exec", name, "getent", "hosts", "postgres"])
                if rc_dns == 0 and resolved:
                    actual = resolved.split()[0]
                    if actual != pg_ip:
                        errs.append(f"后端容器 {name} 内 postgres 解析到 {actual}，"
                                    f"但 {pg_container} 在网络 {net} 上的 IP 是 {pg_ip}——"
                                    "DNS 指向了其它栈的 postgres")
                break
    return errs


def check_backend_same_stack(base_url_port: int, project: str, pg_container: str,
                             backend_service: str) -> tuple[list[str], list[str]]:
    """BASE_URL 端口必须映射到本 project 的 backend 服务，且该 backend 连向 PG_CONTAINER。

    只看端口不够：18082 也可以手工映射到默认栈容器，或映射到本栈 frontend/minio。
    返回 (failures, backend 容器名列表)。
    """
    found: list[str] = []
    errs: list[str] = []
    pg_service = ""
    rc, pg_cid = docker(["ps", "-q", "--filter", f"name=^{pg_container}$"])
    if rc == 0 and pg_cid:
        pg_service = compose_labels(pg_cid).get("com.docker.compose.service", "")

    rc, cids = docker(["ps", "-q", "--filter",
                       f"label=com.docker.compose.project={project}"])
    candidates = cids.split() if rc == 0 and cids else []
    for cid in candidates:
        rc2, ports = docker(["port", cid])
        host_ports = set()
        for line in (ports.splitlines() if rc2 == 0 else []):
            # 例：8080/tcp -> 0.0.0.0:18082
            host_ports.add(line.rsplit(":", 1)[-1].strip())
        if str(base_url_port) not in host_ports:
            continue
        labels = compose_labels(cid)
        service = labels.get("com.docker.compose.service", "")
        rc3, name = docker(["inspect", "-f", "{{.Name}}", cid])
        cname = (name.lstrip("/") if rc3 == 0 and name else cid)
        if service != backend_service:
            errs.append(f"BASE_URL 端口 {base_url_port} 映射到 {cname}"
                        f"（compose service={service or '(无)'}），不是后端服务 {backend_service!r}")
            continue
        found.append(cname)
        env_key, env_val = backend_db_env(cid)
        if not env_key:
            errs.append(f"后端容器 {cname} 无可验证的数据源环境变量"
                        f"（{'/'.join(DB_ENV_KEYS)}），无法确认它连向 {pg_container!r}")
            continue
        host = db_host_of(env_val)
        allowed = {h for h in (pg_service, pg_container) if h}
        if host not in allowed:
            errs.append(f"后端容器 {cname} 的 {env_key} 指向 {host!r}，"
                        f"与 PG_CONTAINER={pg_container!r}（service={pg_service or '(无)'}）不一致")

    if not candidates:
        errs.append(f"project {project!r} 内没有任何运行中的容器")
    elif not found and not errs:
        errs.append(f"BASE_URL 端口 {base_url_port} 未映射到 project {project!r} 内的任何容器；"
                    "拒绝把隔离端口指向默认栈或其它栈的 backend")
    return errs, found


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
    backend_service = os.environ.get("BACKEND_SERVICE", "") or "backend-java"

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

    # ---- 4b) BASE_URL 必须指向同一 compose project 的 backend（D6/E5）----
    # 仅挡端口检查不够：18082 也可以手工映射到默认栈 java 容器或本栈 frontend/minio。
    backend_names: list[str] = []
    is_local = parsed.hostname in {"localhost", "127.0.0.1", "::1"}
    if not failures and is_local:
        url_port = parsed.port or (443 if parsed.scheme == "https" else 80)
        errs, backend_names = check_backend_same_stack(
            url_port, project, pg_container, backend_service)
        for msg in errs:
            fail(msg)
        if backend_names and not errs:
            ok(f"BASE_URL 端口 {url_port} 对应本栈后端容器：{', '.join(backend_names)}")
            ok(f"后端数据源指向 {pg_container}（同一 compose project）")
            for msg in check_backend_same_network(pg_container, backend_names):
                fail(msg)
            if not any("网络" in f or "DNS" in f for f in failures):
                ok("backend 与 PG 共享 Docker 网络，且 postgres 域名解析到本栈 PG 容器（E6 数据面同栈）")
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
    print(f"  BACKEND_SERVICE     = {backend_service}")
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
