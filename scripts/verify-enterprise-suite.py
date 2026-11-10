#!/usr/bin/env python3
"""企业自用闭环（E1–E4）真实 PostgreSQL 验证。

任务书要求：涉及 DB 约束、索引写入、权限过滤的用例必须落在真实 PG 上。
本脚本不 mock 任何东西，直接在运行中的 Postgres 上：

  1. 应用 V77–V84 迁移（全部 IF NOT EXISTS，可重复执行）；
  2. schema smoke：建两棵部门树 + 三个用户，验证 org_unit/users 数据与子树查询可用
     （**不验证 Java 实现**；Java 路径由 EnterpriseSuitePgIT 走真实代码覆盖）；
  3. 向 unified_search_index 写入一行业务记录，断言全文检索能命中（A-6 语义）；
  4. 校验 V78/V79/V81/V82/V83/V84 的表/列真实存在。

用法：
  python3 scripts/verify-enterprise-suite.py [--container nocobase-postgres]
                                             [--user nocobase] [--db nocobase]

失败时退出码非 0，并打印失败断言。脚本内所有数据用独立 tenant 前缀并在结束时清理。
"""

import argparse
import pathlib
import subprocess
import sys
import uuid

from _db import db_sql, pg_container

ROOT = pathlib.Path(__file__).resolve().parent.parent
MIGRATIONS = [
    ROOT / "backend-java/src/main/resources/db/migration/V77__org_unit_and_user_profile.sql",
    ROOT / "backend-java/src/main/resources/db/migration/V78__approval.sql",
    ROOT / "backend-java/src/main/resources/db/migration/V79__notification_center.sql",
    ROOT / "backend-java/src/main/resources/db/migration/V80__restore_search_vector_trigger.sql",
    ROOT / "backend-java/src/main/resources/db/migration/V81__notification_type_backfill_and_last_event.sql",
    ROOT / "backend-java/src/main/resources/db/migration/V82__user_external_identity_and_disable_reason.sql",
    ROOT / "backend-java/src/main/resources/db/migration/V83__dingtalk_approval_external_binding.sql",
    ROOT / "backend-java/src/main/resources/db/migration/V84__callback_event_corp_and_oidc_login_code.sql",
]

TENANT = "verify_es_" + uuid.uuid4().hex[:8]

failures = []
passed = 0


def run_sql(container, user, db, sql, expect_rows=False):
    """执行 SQL，返回 (ok, stdout, stderr)。

    优先 docker exec（本地 compose）；CI 无容器时回退 _db（本机 psql）。
    """
    if pg_container():
        proc = subprocess.run(
            ["docker", "exec", "-i", container, "psql", "-U", user, "-d", db,
             "-v", "ON_ERROR_STOP=1", "-tA", "-F", "|", "-f", "-"],
            input=sql,
            text=True,
            capture_output=True,
        )
        return proc.returncode == 0, proc.stdout.strip(), proc.stderr.strip()
    ok, out, err = db_sql(sql)
    return ok, out, err


def check(name, cond, detail=""):
    global passed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failures.append(name)
        print(f"  FAIL  {name} {detail}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--container", default=__import__("os").environ.get("PG_CONTAINER", "nocobase-postgres"))
    ap.add_argument("--user", default="nocobase")
    ap.add_argument("--db", default="nocobase")
    args = ap.parse_args()

    print(f"[1/4] 应用迁移 V77–V84（tenant={TENANT}）")
    for mig in MIGRATIONS:
        sql = mig.read_text(encoding="utf-8")
        ok, out, err = run_sql(args.container, args.user, args.db, sql)
        check(f"migration {mig.name}", ok, err[:400])
        if not ok:
            return finish()

    print("[2/4] schema smoke：部门子树数据可用性（不验证 Java 实现）")
    root = uuid.uuid4()
    child = uuid.uuid4()
    sibling = uuid.uuid4()
    mgr = uuid.uuid4()
    member = uuid.uuid4()
    outsider = uuid.uuid4()

    sql = f"""
BEGIN;
DELETE FROM org_unit WHERE tenant_id = '{TENANT}';
DELETE FROM users WHERE tenant_id = '{TENANT}';

INSERT INTO org_unit (id, tenant_id, parent_id, name, path, sort, source, deleted, created_at, updated_at)
VALUES ('{root}', '{TENANT}', NULL, '华东大区', '/{root}/', 0, 'LOCAL', FALSE, NOW(), NOW()),
       ('{child}', '{TENANT}', '{root}', '华东-销售', '/{root}/{child}/', 0, 'LOCAL', FALSE, NOW(), NOW()),
       ('{sibling}', '{TENANT}', NULL, '华南大区', '/{sibling}/', 0, 'LOCAL', FALSE, NOW(), NOW());

INSERT INTO users (id, username, tenant_id, enabled, password_set, created_at, status, department_id)
VALUES ('{mgr}', 'v_mgr_{TENANT}', '{TENANT}', TRUE, TRUE, NOW(), 'ACTIVE', '{root}'),
       ('{member}', 'v_member_{TENANT}', '{TENANT}', TRUE, TRUE, NOW(), 'ACTIVE', '{child}'),
       ('{outsider}', 'v_outsider_{TENANT}', '{TENANT}', TRUE, TRUE, NOW(), 'ACTIVE', '{sibling}');

-- 仅验证数据与子树 SQL 可执行；Java 实现路径见 EnterpriseSuitePgIT
SELECT u.id FROM users u
JOIN org_unit o ON u.department_id = o.id
WHERE o.tenant_id = '{TENANT}' AND o.deleted = FALSE
  AND o.path LIKE '/{root}/%'
ORDER BY u.id;
COMMIT;
"""
    ok, out, err = run_sql(args.container, args.user, args.db, sql)
    check("DEPT subtree query executes", ok, err[:400])
    ids = set(out.splitlines()) if out else set()
    check("DEPT includes subtree member", str(member) in ids, f"got={ids}")
    check("DEPT excludes sibling dept", str(outsider) not in ids, f"got={ids}")

    print("[3/4] A-6 索引写入后可被全文检索命中（真实 tsvector 触发器）")
    rec_id = str(uuid.uuid4())
    sql = f"""
BEGIN;
DELETE FROM unified_search_index WHERE tenant_id = '{TENANT}';
INSERT INTO unified_search_index (id, entity_type, entity_id, tenant_id, title, content, metadata, created_at, updated_at)
VALUES ('{uuid.uuid4()}', 'record', '{rec_id}', '{TENANT}',
        'East China Manufacturing Ltd', 'annual purchase framework agreement', '{{"collection":"customer"}}'::jsonb, NOW(), NOW());
SELECT COUNT(*) FROM unified_search_index
WHERE tenant_id = '{TENANT}' AND entity_type = 'record'
  AND content_tsv @@ plainto_tsquery('simple', 'purchase');
COMMIT;
"""
    ok, out, err = run_sql(args.container, args.user, args.db, sql)
    check("index write + full-text query executes", ok, err[:400])
    numeric = [l.strip() for l in out.splitlines() if l.strip().isdigit()]
    last = numeric[-1] if numeric else ""
    check("written record is searchable", last == "1", f"count={out!r}")

    print("[4/4] V78–V84 结构、通知列与外部绑定")
    sql = f"""
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('approval_template','approval_request','approval_cc');
"""
    ok, out, err = run_sql(args.container, args.user, args.db, sql)
    check("approval tables exist (3)", ok and out.strip() == "3", f"out={out!r} err={err[:200]}")

    sql = """
SELECT COUNT(*) FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'messages'
  AND column_name IN ('deeplink','read_at','digest_key','priority','channel_sent','digest_count','last_event_at');
"""
    ok, out, err = run_sql(args.container, args.user, args.db, sql)
    check("messages notification columns exist (7, incl V81 last_event_at)", ok and out.strip() == "7",
          f"out={out!r} err={err[:200]}")

    sql = """
SELECT COUNT(*) FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'users'
  AND column_name IN ('external_source','external_id','disable_reason');
"""
    ok, out, err = run_sql(args.container, args.user, args.db, sql)
    check("users external identity/disable columns exist (3, V82)", ok and out.strip() == "3",
          f"out={out!r} err={err[:200]}")

    sql = """
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('approval_external_binding','approval_callback_event');
"""
    ok, out, err = run_sql(args.container, args.user, args.db, sql)
    check("V83 binding/event tables exist (2)", ok and out.strip() == "2", f"out={out!r}")
    sql = """
SELECT COUNT(*) FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'approval_request'
  AND column_name IN ('external_push_status','external_push_error');
"""
    ok, out, err = run_sql(args.container, args.user, args.db, sql)
    check("V83 external push columns exist (2)", ok and out.strip() == "2", f"out={out!r}")

    sql = """
SELECT COUNT(*) FROM information_schema.table_constraints
WHERE table_schema='public' AND table_name='approval_callback_event'
  AND constraint_type='PRIMARY KEY' AND constraint_name='pk_approval_callback_event';
"""
    ok, out, err = run_sql(args.container, args.user, args.db, sql)
    check("V84 corp/event composite primary key exists", ok and out.strip() == "1", f"out={out!r}")
    sql = """
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema='public' AND table_name='oidc_login_code';
"""
    ok, out, err = run_sql(args.container, args.user, args.db, sql)
    check("V84 OIDC one-time code table exists", ok and out.strip() == "1", f"out={out!r}")

    sql = f"""
BEGIN;
DELETE FROM approval_callback_event WHERE tenant_id = '{TENANT}';
DELETE FROM approval_external_binding WHERE tenant_id = '{TENANT}';
DELETE FROM approval_cc WHERE request_id IN
  (SELECT id FROM approval_request WHERE tenant_id = '{TENANT}');
DELETE FROM approval_request WHERE tenant_id = '{TENANT}';
DELETE FROM approval_template WHERE tenant_id = '{TENANT}';
DELETE FROM unified_search_index WHERE tenant_id = '{TENANT}';
DELETE FROM oidc_login_code WHERE tenant_id = '{TENANT}';
DELETE FROM users WHERE tenant_id = '{TENANT}';
DELETE FROM org_unit WHERE tenant_id = '{TENANT}';
COMMIT;
"""
    run_sql(args.container, args.user, args.db, sql)
    return finish()


def finish():
    print()
    if failures:
        print(f"FAILED: {len(failures)} 项 —— {', '.join(failures)}")
        return 1
    print(f"PASSED: {passed} 项真实 PG 断言")
    return 0


if __name__ == "__main__":
    sys.exit(main())
