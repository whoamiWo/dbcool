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
# T3 演练一律用 docker 命名卷，不用宿主机目录：宿主机目录属主是宿主机用户
# （uid 1000），容器内 postgres 是 uid 999，archive_command 会 Permission denied。
PITR_VOL_ARCHIVE = "phase94-pitr-archive"
PITR_VOL_BASE = "phase94-pitr-base"

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


def rm_volume(name):
    run(["docker", "volume", "rm", "-f", name], check=False)


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

    流程（顺序不能变）：起源库(开归档) → 建表 + 基线数据 → **pg_basebackup**
          → 插入数据 → 等 WAL 归档 → 记时间 T → 删除数据 → 等 WAL 归档
          → 新实例配置 recovery_target_time=T 启动 → 断言"被删的数据回来了"

    为什么备份必须在插入/删除**之前**：PITR 只能前滚，不能回滚。备份是时点 T0 的
    快照，若备份发生在删除之后，快照里本来就没有那行，再要求恢复到更早的 T
    属于还原未来 —— 实测必然报 "recovery ended before configured recovery target
    was reached"（这一版之前就是这么错的）。
    """
    print("\n=== T3: PITR 时间点恢复演练（独立容器）===")

    # 先确认生产库归档已开启（配置层面）
    rc, out, _ = run(["docker", "exec", "nocobase-postgres", "psql", "-U", POSTGRES_USER,
                      "-d", "nocobase", "-t", "-A", "-c", "SHOW archive_mode;"], check=False)
    check("生产库 archive_mode = on", "on" in out.strip().lower(), out.strip())

    # 只 ls 归档目录不够：目录里有文件 ≠ 归档成功（归档失败时目录是空的，
    # 而曾经成功过的旧文件会让人误判）。真正的证据是 pg_stat_archiver。
    stat = psql("nocobase-postgres",
                "select archived_count || '|' || failed_count from pg_stat_archiver")
    parts = (stat or "|").split("|")
    archived_n, failed_n = (parts + ["", ""])[:2]
    check("生产库 WAL 已实际归档", archived_n.isdigit() and int(archived_n) > 0,
          f"archived_count={archived_n}")
    check("生产库 WAL 归档无失败", failed_n == "0", f"failed_count={failed_n}")

    # ---- 独立环境 PITR 演练 ----
    # 归档目录**必须**用 docker 命名卷，不能用宿主机目录：宿主机目录属主是宿主机
    # 用户（uid 1000），容器内 postgres 是 uid 999，写不进去 → archive_command
    # 全部 Permission denied。实测：failed_count=22、一个 WAL 都没归档，
    # 于是恢复实例只能前滚到备份末尾，必然报
    # "recovery ended before configured recovery target was reached"。
    # 命名卷允许我们在启动前用 busybox(root) 把它 chown 成 999:999。
    rm_container(PITR_SRC)
    rm_container(PITR_RESTORED)
    for vol in (PITR_VOL_ARCHIVE, PITR_VOL_BASE):
        rm_volume(vol)
        run(["docker", "volume", "create", vol], check=False)
    run(["docker", "run", "--rm",
         "-v", f"{PITR_VOL_ARCHIVE}:/archive", "-v", f"{PITR_VOL_BASE}:/base",
         "busybox", "sh", "-c", "chown -R 999:999 /archive /base && chmod 700 /archive /base"],
        check=False)

    def q(sql):
        return psql(PITR_SRC, sql, user="pitr", db="pitrdb")

    def wait_archived(wal_file, tries=40):
        """等指定 WAL 段真的落到归档目录（归档器是异步的，不能靠 sleep 猜）。"""
        for _ in range(tries):
            rc_w, _, _ = run(["docker", "exec", "-u", "postgres", PITR_SRC,
                              "test", "-f", f"/archive/{wal_file}"], check=False)
            if rc_w == 0:
                return True
            time.sleep(1)
        return False

    rc, _, err = run([
        "docker", "run", "-d", "--name", PITR_SRC,
        "-e", "POSTGRES_PASSWORD=pitrpass", "-e", "POSTGRES_USER=pitr", "-e", "POSTGRES_DB=pitrdb",
        "-v", f"{PITR_VOL_ARCHIVE}:/archive", "-v", f"{PITR_VOL_BASE}:/base_out",
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

    # ① 建表 + 基线数据（备份时点就存在的行，用来验证恢复没有丢备份内的东西）
    q("create table pitr_probe(id int primary key, note text);")
    q("insert into pitr_probe values (2, 'baseline');")
    q("select pg_switch_wal();")
    time.sleep(2)

    # ② base backup —— 必须在插入/删除**之前**（PITR 只能前滚）
    rc, _, err = run(["docker", "exec", PITR_SRC, "pg_basebackup",
                      "-U", "pitr", "-D", "/base_out", "-F", "plain", "-X", "stream", "-c", "fast"],
                     check=False)
    if not check("pg_basebackup 成功", rc == 0, err.strip()[:200]):
        rm_container(PITR_SRC)
        return

    # ③ 插入"将被删除"的行，并**等它的 WAL 真的归档**（不靠 sleep 猜）
    q("insert into pitr_probe values (1, 'before-delete');")
    wal_insert = q("select pg_walfile_name(pg_current_wal_lsn());")
    q("select pg_switch_wal();")
    check("插入操作的 WAL 已归档", wait_archived(wal_insert), f"{wal_insert}")
    failed_n = q("select failed_count from pg_stat_archiver;")
    check("PITR 源库归档无失败", failed_n == "0", f"failed_count={failed_n}")

    # ④ 记下目标时间点 T（插入之后、删除之前）
    target_t = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f %Z")
    time.sleep(1)

    # ⑤ 删除它 —— PITR 到 T 之后这行应该"回来"
    q("delete from pitr_probe where id = 1;")
    wal_delete = q("select pg_walfile_name(pg_current_wal_lsn());")
    q("select pg_switch_wal();")
    check("删除操作的 WAL 已归档", wait_archived(wal_delete), f"{wal_delete}")

    deleted_now = q("select count(*) from pitr_probe where id = 1")
    check("删除操作已生效（演练前提）", deleted_now == "0", f"剩余={deleted_now}")

    # ⑥ 给基础备份写入恢复配置（recovery.signal + recovery_target_time）
    # 用 busybox(root) 写：数据目录属主是容器内 postgres(uid 999)，宿主机用户写不进去。
    conf_text = ("\nrestore_command = 'cp /archive/%f %p'\n"
                 f"recovery_target_time = '{target_t}'\n"
                 "recovery_target_action = 'promote'\n")
    host_conf = "/tmp/phase94_recovery.conf"
    Path(host_conf).write_text(conf_text)
    run(["docker", "run", "--rm",
         "-v", f"{PITR_VOL_BASE}:/data", "-v", "/tmp:/hosttmp",
         "busybox", "sh", "-c",
         "cat /hosttmp/phase94_recovery.conf >> /data/postgresql.auto.conf "
         "&& touch /data/recovery.signal "
         "&& chown -R 999:999 /data && chmod 700 /data && chmod 600 /data/recovery.signal"],
        check=False)
    run(["rm", "-f", host_conf], check=False)

    rc, _, err = run([
        "docker", "run", "-d", "--name", PITR_RESTORED,
        "-v", f"{PITR_VOL_BASE}:/var/lib/postgresql/data",
        "-v", f"{PITR_VOL_ARCHIVE}:/archive",
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
        rm_container(PITR_SRC)
        rm_container(PITR_RESTORED)
        return

    # pg_isready 在"只读回放"阶段就返回成功，此时 recovery 还没跑完、日志也没打全。
    # 必须等 promote 完成（pg_is_in_recovery() = f）再看日志，否则断言会假失败。
    in_recovery = "t"
    for _ in range(60):
        in_recovery = psql(PITR_RESTORED, "select pg_is_in_recovery()",
                           user="pitr", db="pitrdb")
        if in_recovery == "f":
            break
        time.sleep(1)
    check("恢复实例已完成 promote", in_recovery == "f", f"pg_is_in_recovery={in_recovery}")

    # 逻辑证据：日志必须显示"停在目标时间点的提交之前"。
    # 少了这条，"数据回来了"也可能是"整库恢复到最新"造成的假象。
    # Postgres 日志走 **stderr**，只取 stdout 会什么都拿不到（实测踩过）。
    _, logs, logerr = run(["docker", "logs", PITR_RESTORED], check=False)
    log_all = (logs or "") + (logerr or "")
    check("恢复确实停在目标时间点（日志证据）",
          "recovery stopping before commit" in log_all or
          "recovery stopping before abort" in log_all,
          log_all[-300:])

    restored = psql(PITR_RESTORED,
                    "select note from pitr_probe where id = 1", user="pitr", db="pitrdb")
    check("PITR 恢复到删除前时间点：被删数据回来了",
          restored == "before-delete", f"recovery_target_time={target_t}, 查得 note={restored!r}")

    total = psql(PITR_RESTORED, "select count(*) from pitr_probe", user="pitr", db="pitrdb")
    check("恢复结果同时保留备份时点的基线数据", total == "2", f"count={total}")

    rm_container(PITR_SRC)
    rm_container(PITR_RESTORED)
    for vol in (PITR_VOL_ARCHIVE, PITR_VOL_BASE):
        rm_volume(vol)
    print("     （PITR 演练容器与卷已清理）")


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
#
# 第三版又踩了三个坑（T3 一直 Exited(1) 的真正原因，全都不是权限 777）：
#   1. 归档目录用了**宿主机绑定挂载**：属主是宿主机用户(uid 1000)，容器内
#      postgres 是 uid 999 → archive_command 全部 Permission denied，
#      failed_count=22、一个 WAL 都没归档。改用 docker 命名卷 + busybox chown 999:999。
#      判断归档是否成功只能看 pg_stat_archiver，"ls 归档目录有文件"会骗人
#      （失败时目录为空，而历史遗留文件会让人误判）。
#   2. **PITR 顺序错了**：原流程是"插入 → 记 T → 删除 → pg_basebackup"。
#      PITR 只能前滚不能回滚，备份时点晚于 T 时根本无法回到 T，必然报
#      "recovery ended before configured recovery target was reached"。
#      正确顺序：建表 → **basebackup** → 插入 → 记 T → 删除。
#   3. 归档是**异步**的，sleep 猜不得：必须轮询归档目录里出现目标 WAL 段。
# 另外两个小坑：pg_isready 在只读回放阶段就返回成功，要看日志得先等 promote
# 完成（pg_is_in_recovery() = f）；Postgres 日志走 **stderr**，只取 stdout 是空的。
# ---------------------------------------------------------------------------
