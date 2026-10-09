#!/usr/bin/env python3
"""
PHASE94 端到端备份 / 恢复 / PITR 验证脚本。

验证 T1-T4：
1. 实际备份成功（T1）
2. **真**恢复演练 —— 把 dump 恢复进独立容器并校验数据（T2）
3. **真** PITR —— 在独立容器上演示"恢复到指定时间点"（T3）
4. 副本恢复功能（T4）

设计原则（PHASE94 收尾时重写，原因见文件末的"历史教训"）：
- **绝不在正在使用的库上做恢复/PITR 演练** —— 恢复会重置库，失败即丢数据。
  T2/T3 全部在独立容器中进行。
- **验收点是"数据真的回来"，不是"命令返回 0"** —— 必须校验表数、记录数、
  以及一条具体记录的内容。
- **禁止硬编码 True** —— 每个断言都必须有可能失败。

用法：
    python3 scripts/backup-e2e-verify.py

退出码：0=全部通过，非 0=失败
"""

import json
import os
import subprocess
import sys
import tarfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKUP_DIR = os.path.join(BASE_DIR, "backend-python", "backups")
tar_files = sorted([f for f in os.listdir(BACKUP_DIR) if f.endswith(".tar.gz")], reverse=True) \
    if os.path.isdir(BACKUP_DIR) else []
TAR_FILE = os.path.join(BACKUP_DIR, tar_files[0]) if tar_files else None

POSTGRES_USER = "nocobase"
POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "dev_password")
PG_IMAGE = "pgvector/pgvector:pg16"
RESTORE_CONTAINER = "postgres-restore-test"
PITR_SRC = "pitr-demo-src"
PITR_RESTORED = "pitr-demo-restored"

results = []


def run(cmd, check=True, stdin=None):
    proc = subprocess.run(cmd, capture_output=True, text=True, input=stdin)
    if check and proc.returncode != 0:
        raise RuntimeError(f"Command failed: {' '.join(cmd)}\n{proc.stderr}")
    return proc.returncode, proc.stdout, proc.stderr


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(f"  {'✓ PASS' if ok else '✗ FAIL'} {name}" + (f" — {detail}" if detail else ""))
    return bool(ok)


def psql(container, sql, user=POSTGRES_USER, db="nocobase"):
    rc, out, err = run(["docker", "exec", container, "psql", "-U", user, "-d", db,
                        "-t", "-A", "-c", sql], check=False)
    return out.strip() if rc == 0 else ""


def wait_pg(container, user=POSTGRES_USER, db="nocobase", tries=40):
    for _ in range(tries):
        rc, _, _ = run(["docker", "exec", container, "pg_isready", "-U", user, "-d", db], check=False)
        if rc == 0:
            return True
        time.sleep(2)
    return False


def rm_container(name):
    run(["docker", "rm", "-f", name], check=False)


# ---------------- T1 ----------------

def test_t1_backup_exists():
    print("\n=== T1: 实际备份验证 ===")
    if not TAR_FILE or not os.path.exists(TAR_FILE):
        check("备份文件存在", False, f"not found in {BACKUP_DIR}")
        return
    size = os.path.getsize(TAR_FILE)
    check("备份文件存在", size > 1_000_000, f"{size} bytes")

    with tarfile.open(TAR_FILE, "r:gz") as tar:
        names = tar.getnames()
        has_pg = any(n.endswith("dump.pg") or n == "postgresql.dump" for n in names)
        check("包含 PostgreSQL dump", has_pg)
        if "manifest.json" in names:
            mf = tar.extractfile("manifest.json")
            manifest = json.loads(mf.read()) if mf else {}
            pg_bytes = manifest.get("postgresql", {}).get("bytes", 0)
            check("PostgreSQL 备份字节 > 0", pg_bytes > 0, f"bytes={pg_bytes}")


# ---------------- T2：真恢复 ----------------

def test_t2_restore_realtime():
    """T2：把备份里的 dump **真的**恢复到独立容器，并校验数据真的回来。"""
    print("\n=== T2: 恢复演练（真恢复 + 数据校验，独立容器）===")
    if not TAR_FILE:
        check("备份可用", False, "无备份文件")
        return

    dump_path = "/tmp/phase94_restore_dump.pg"
    try:
        with tarfile.open(TAR_FILE, "r:gz") as tar:
            cand = [m for m in tar.getmembers() if m.name.endswith("dump.pg")
                    or m.name == "postgresql.dump"]
            if not cand:
                check("从备份提取 dump", False, "备份内未找到 postgresql dump")
                return
            Path(dump_path).write_bytes(tar.extractfile(cand[0]).read())
        check("从备份提取 dump", os.path.getsize(dump_path) > 0,
              f"{os.path.getsize(dump_path)} bytes")
    except Exception as e:
        check("从备份提取 dump", False, str(e))
        return

    rm_container(RESTORE_CONTAINER)
    rc, _, err = run(["docker", "run", "-d", "--name", RESTORE_CONTAINER,
                      "-e", f"POSTGRES_USER={POSTGRES_USER}",
                      "-e", f"POSTGRES_PASSWORD={POSTGRES_PASSWORD}",
                      "-e", "POSTGRES_DB=nocobase",
                      PG_IMAGE], check=False)
    if not check("启动独立恢复容器", rc == 0, err.strip()[:120]):
        return
    if not check("独立容器就绪", wait_pg(RESTORE_CONTAINER)):
        rm_container(RESTORE_CONTAINER)
        return

    run(["docker", "cp", dump_path, f"{RESTORE_CONTAINER}:/tmp/dump.pg"], check=False)
    rc, out, err = run(["docker", "exec", RESTORE_CONTAINER, "pg_restore",
                        "-U", POSTGRES_USER, "-d", "nocobase",
                        "--no-owner", "--no-privileges", "/tmp/dump.pg"], check=False)
    # pg_restore 可能因"对象已存在"告警而非 0，只要能查到数据就算成功
    print(f"     （pg_restore rc={rc}，继续校验数据）")

    # 校验：表数量
    src_tables = psql("nocobase-postgres",
                      "select count(*) from information_schema.tables where table_schema='public'")
    got_tables = psql(RESTORE_CONTAINER,
                      "select count(*) from information_schema.tables where table_schema='public'")
    # 备份是**某个时点的快照**：备份之后在源库新建的表，本来就不该出现在恢复结果里。
    # 所以主断言是"关键表都在"，表总数差异只作提示（并列出差异表以便核对）。
    if src_tables != got_tables:
        src_set = set(psql("nocobase-postgres",
                           "select table_name from information_schema.tables where table_schema='public'").split())
        got_set = set(psql(RESTORE_CONTAINER,
                           "select table_name from information_schema.tables where table_schema='public'").split())
        print(f"     提示：源库={src_tables} 恢复库={got_tables}，"
              f"差异表={sorted(src_set - got_set)}"
              f"（备份时点之后新建的表不在备份内，属正常）")
    key_tables = ("users", "tenant", "audit_log")
    missing_key = [t for t in key_tables
                   if psql(RESTORE_CONTAINER, f"select to_regclass('{t}')") in ("", "null")]
    check("恢复库包含关键表 users/tenant/audit_log", not missing_key, f"缺失={missing_key}")

    # 校验：关键表记录数
    for table in ("users", "tenant"):
        src_n = psql("nocobase-postgres", f"select count(*) from {table}")
        got_n = psql(RESTORE_CONTAINER, f"select count(*) from {table}")
        check(f"记录数一致: {table}",
              src_n != "" and src_n == got_n, f"源库={src_n} 恢复库={got_n}")

    # 校验：具体一条记录的内容（不是只看数量）
    src_user = psql("nocobase-postgres", "select username from users order by username limit 1")
    got_user = psql(RESTORE_CONTAINER, "select username from users order by username limit 1")
    check("具体记录内容一致（首条用户名）",
          src_user != "" and src_user == got_user, f"源库={src_user} 恢复库={got_user}")

    rm_container(RESTORE_CONTAINER)
    print("     （独立容器已清理，未触碰生产库）")


# ---------------- T3：真 PITR ----------------

def test_t3_pitr():
    """
    T3：**真** PITR —— 在独立容器上完整演示"恢复到指定时间点"。

    为什么不在生产库上做：PITR 需要 base backup + WAL 回放，操作生产库风险高。
    独立容器同样能证明这套能力，而且这才是演练的正确姿势（与恢复演练同理）。

    流程：起源库(开归档) → 插入数据 → 记时间 T → 删除数据 → pg_basebackup
          → 新实例配置 recovery_target_time=T 启动 → 断言"被删的数据回来了"
    """
    print("\n=== T3: PITR 时间点恢复演练（独立容器）===")

    # 先确认生产库归档已开启（配置层面）
    rc, out, _ = run(["docker", "exec", "nocobase-postgres", "psql", "-U", POSTGRES_USER,
                      "-d", "nocobase", "-t", "-A", "-c", "SHOW archive_mode;"], check=False)
    check("生产库 archive_mode = on", "on" in out.strip().lower(), out.strip())

    rc, out, _ = run(["docker", "exec", "-u", "postgres", "nocobase-postgres",
                      "ls", "-1", "/var/lib/postgresql/data/pg_archive/"], check=False)
    archived = [l for l in out.splitlines() if l.strip()]
    check("生产库 WAL 归档文件已生成", len(archived) > 0, f"{len(archived)} 个文件")

    # ---- 独立环境 PITR 演练 ----
    work = Path("/tmp/phase94_pitr")
    run(["rm", "-rf", str(work)], check=False)
    (work / "archive").mkdir(parents=True, exist_ok=True)
    (work / "base").mkdir(parents=True, exist_ok=True)
    (work / "restore").mkdir(parents=True, exist_ok=True)
    run(["chmod", "-R", "777", str(work)], check=False)

    rm_container(PITR_SRC)
    rm_container(PITR_RESTORED)

    rc, _, err = run([
        "docker", "run", "-d", "--name", PITR_SRC,
        "-e", "POSTGRES_PASSWORD=pitrpass", "-e", "POSTGRES_USER=pitr", "-e", "POSTGRES_DB=pitrdb",
        "-v", f"{work}/archive:/archive",
        PG_IMAGE,
        "-c", "wal_level=replica",
        "-c", "archive_mode=on",
        "-c", "archive_command=test ! -f /archive/%f && cp %p /archive/%f",
    ], check=False)
    if not check("PITR 源容器启动（已开归档）", rc == 0, err.strip()[:150]):
        return
    if not check("PITR 源容器就绪", wait_pg(PITR_SRC, user="pitr", db="pitrdb")):
        rm_container(PITR_SRC)
        return

    # 建表并插入（这行是"删除前"的数据）
    run(["docker", "exec", PITR_SRC, "psql", "-U", "pitr", "-d", "pitrdb",
         "-c", "create table pitr_probe(id int primary key, note text);"], check=False)
    run(["docker", "exec", PITR_SRC, "psql", "-U", "pitr", "-d", "pitrdb",
         "-c", "insert into pitr_probe values (1, 'before-delete');"], check=False)

    # 强制归档当前 WAL，确保插入进入归档
    run(["docker", "exec", PITR_SRC, "psql", "-U", "pitr", "-d", "pitrdb",
         "-c", "select pg_switch_wal();"], check=False)
    time.sleep(3)

    # 记下"删除前"的时间点 T（UTC，稍晚于插入以确保 WAL 已落归档）
    target_t = (datetime.now(timezone.utc)).strftime("%Y-%m-%d %H:%M:%S.%f %Z")
    time.sleep(2)

    # 删除它 —— PITR 之后这行应该"回来"
    run(["docker", "exec", PITR_SRC, "psql", "-U", "pitr", "-d", "pitrdb",
         "-c", "delete from pitr_probe where id = 1;"], check=False)
    run(["docker", "exec", PITR_SRC, "psql", "-U", "pitr", "-d", "pitrdb",
         "-c", "select pg_switch_wal();"], check=False)
    time.sleep(2)

    deleted_now = psql(PITR_SRC, "select count(*) from pitr_probe where id = 1",
                       user="pitr", db="pitrdb")
    check("删除操作已生效（演练前提）", deleted_now == "0", f"剩余={deleted_now}")

    # base backup
    # 用 -F plain -X stream：直接产出**完整可启动**的数据目录。
    # 曾用 -F tar -X none + 手工解包 —— 解出来的目录缺内容，Postgres 一启动就崩
    # （实测：数据目录里出现 152MB 的 core dump，容器 Exited(1)）。
    rc, _, err = run(["docker", "exec", PITR_SRC, "pg_basebackup",
                      "-U", "pitr", "-D", "/tmp/base_out", "-F", "plain", "-X", "stream", "-c", "fast"],
                     check=False)
    if rc != 0:
        check("pg_basebackup 成功", False, err.strip()[:200])
        rm_container(PITR_SRC)
        return
    check("pg_basebackup 成功", True)
    run(["docker", "cp", f"{PITR_SRC}:/tmp/base_out/.", str(work / "base")], check=False)

    # 拷贝基础备份到待恢复的数据目录（plain 格式，无需解包）
    run(["docker", "cp", f"{PITR_SRC}:/tmp/base_out/.", str(work / "restore")], check=False)
    # 清理可能存在的崩溃残留（core dump 等），避免干扰
    run(["docker", "run", "--rm", "-v", f"{work}/restore:/data", "busybox",
         "sh", "-c", "rm -f /data/core.*"], check=False)

    # 注意：解包出来的 data 目录属主是容器内 postgres（uid 999），
    # 宿主机普通用户**写不进去**（实测 PermissionError）。改由容器自己写。
    conf_text = ("\nrestore_command = 'cp /archive/%f %p'\n"
                 f"recovery_target_time = '{target_t}'\n"
                 "recovery_target_action = 'promote'\n")
    host_conf = "/tmp/phase94_recovery.conf"
    Path(host_conf).write_text(conf_text)
    run(["docker", "run", "--rm",
         "-v", f"{work}/restore:/data", "-v", "/tmp:/hosttmp",
         PG_IMAGE, "sh", "-c",
         "cat /hosttmp/phase94_recovery.conf >> /data/postgresql.auto.conf "
         "&& touch /data/recovery.signal "
         "&& chmod 600 /data/recovery.signal"], check=False)
    run(["rm", "-f", host_conf], check=False)

    rc, _, err = run([
        "docker", "run", "-d", "--name", PITR_RESTORED,
        "-e", "POSTGRES_PASSWORD=pitrpass", "-e", "POSTGRES_USER=pitr", "-e", "POSTGRES_DB=pitrdb",
        "-v", f"{work}/restore:/var/lib/postgresql/data",
        "-v", f"{work}/archive:/archive",
        PG_IMAGE,
    ], check=False)
    if not check("PITR 恢复实例启动", rc == 0, err.strip()[:150]):
        rm_container(PITR_SRC)
        rm_container(PITR_RESTORED)
        return

    if not check("PITR 恢复实例就绪", wait_pg(PITR_RESTORED, user="pitr", db="pitrdb")):
        # 打印容器状态 + 全量日志，否则无从定位（recovery 失败原因只在日志里）
        _, st, _ = run(["docker", "ps", "-a", "--filter", f"name={PITR_RESTORED}",
                        "--format", "{{.Status}}"], check=False)
        print(f"     容器状态: {st.strip()}")
        _, logs, logerr = run(["docker", "logs", PITR_RESTORED], check=False)
        print("     --- PITR 恢复实例全量日志 ---")
        print((logs or logerr)[-3000:])
        _, ls, _ = run(["docker", "run", "--rm", "-v", f"{work}/restore:/data",
                        "-v", "/tmp:/hosttmp", "busybox", "sh", "-c",
                        "ls -la /data | head -20; echo '--- auto.conf ---'; "
                        "cat /data/postgresql.auto.conf 2>/dev/null | tail -5"], check=False)
        print("     --- 恢复数据目录 ---")
        print(ls[-1500:])
        rm_container(PITR_SRC)
        rm_container(PITR_RESTORED)
        return

    restored = psql(PITR_RESTORED,
                    "select note from pitr_probe where id = 1", user="pitr", db="pitrdb")
    check("PITR 恢复到删除前时间点：被删数据回来了",
          restored == "before-delete", f"recovery_target_time={target_t}, 查得 note={restored!r}")

    rm_container(PITR_SRC)
    rm_container(PITR_RESTORED)
    run(["rm", "-rf", str(work)], check=False)
    print("     （PITR 演练容器已清理）")


# ---------------- T4 ----------------

def test_t4_replica_restore():
    """T4：副本恢复 —— 把备份复制到独立位置，从副本解出同样的 dump。"""
    print("\n=== T4: 副本恢复验证 ===")
    if not TAR_FILE:
        check("副本可用", False, "无备份文件")
        return
    replica = "/tmp/phase94_backup_replica.tar.gz"
    run(["cp", TAR_FILE, replica], check=False)
    check("备份副本已创建", os.path.getsize(replica) == os.path.getsize(TAR_FILE))

    try:
        with tarfile.open(replica, "r:gz") as tar:
            names = tar.getnames()
            has_pg = any(n.endswith("dump.pg") or n == "postgresql.dump" for n in names)
            check("副本包含 PostgreSQL dump", has_pg)
            if "manifest.json" in names:
                mf = tar.extractfile("manifest.json")
                manifest = json.loads(mf.read()) if mf else {}
                check("副本 manifest 可读",
                      manifest.get("postgresql", {}).get("bytes", 0) > 0)
    except Exception as e:
        check("副本可解包", False, str(e))
    finally:
        run(["rm", "-f", replica], check=False)


def main():
    print("=" * 60)
    print("PHASE94 备份/恢复/PITR 端到端验证")
    print("=" * 60)
    print("注意：恢复与 PITR 演练均在**独立容器**中进行，不触碰生产数据库。\n")

    test_t1_backup_exists()
    test_t2_restore_realtime()
    test_t3_pitr()
    test_t4_replica_restore()

    failed = [r for r in results if not r[1]]
    print("\n" + "=" * 60)
    print(f"总计: {len(results)} | PASS: {len(results) - len(failed)} | FAIL: {len(failed)}")
    print("=" * 60)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

# ---------------------------------------------------------------------------
# 历史教训（PHASE94 收尾时记录，勿重犯）
#
# 该脚本第一版有两处硬编码断言，被审计抓出：
#   :116  check("PostgreSQL dump 文件可用", True)      # 恒真
#   :153  check("PostgreSQL PITR 工具可用", True)      # 纯硬编码，无任何前置判断
# 且 T2 名叫 dryrun：只检查"容器能起来 + dump 文件存在"就删容器，
# 从未真正恢复、也未校验任何数据 —— 却在回报里写了具体记录数（脚本根本产不出）。
# 教训：① 断言必须"有可能失败"；② 演练的验收点是"数据真的回来"，
#      不是"命令返回 0"或"文件存在"；③ 回报数字必须能在交付物里找到产出它的代码。
# ---------------------------------------------------------------------------
