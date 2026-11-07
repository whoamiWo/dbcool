#!/usr/bin/env python3
"""R0：历史记录归属列（created_by）手工回填工具。

背景：R0 修复后，新建/批量/导入/更新/删除都会写 created_by/updated_by/deleted_by，
但**历史 NULL 不自动回填**（D20）。未回填的历史记录在 SELF/DEPT 数据范围下不可见，
这是 fail-closed 的预期结果，不是 bug。

用法：
    # 默认 dry-run：只打印将影响行数与样例 5 条，不写库
    python3 scripts/backfill-created-by.py --tenant tenant_default --collection customer

    # 从历史 extra 里的 createdBy/created_by 解析 UUID
    python3 scripts/backfill-created-by.py --tenant t --collection c --from-extra

    # 显式指定归属用户并落库（必须三参数齐全）
    python3 scripts/backfill-created-by.py --tenant t --collection c --user <uuid> --apply

安全约束：
  - 默认 dry-run；
  - --apply 必须同时给出 --tenant/--collection/--user（或 --from-extra 且能解析）；
  - 不提供"全库回填"；
  - --apply 成功后写一条 audit_log（action=BACKFILL_CREATED_BY）。
"""
import argparse
import json
import re
import sys
import uuid

from _db import db_sql

UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


def schema_of(tenant: str) -> str:
    return "public" if tenant == "tenant_default" else tenant


def table_of(collection: str) -> str:
    if not re.fullmatch(r"[a-z_][a-z0-9_]{0,63}", collection or ""):
        raise SystemExit(f"非法 collection 名: {collection!r}")
    return f"data_{collection}"


def q(value: str) -> str:
    return "'" + str(value).replace("'", "''") + "'"


def run(sql: str):
    ok, out, err = db_sql(sql)
    if not ok:
        raise SystemExit(f"SQL 失败: {err}")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="回填历史记录 created_by（默认 dry-run）")
    ap.add_argument("--tenant", required=True)
    ap.add_argument("--collection", required=True)
    ap.add_argument("--user", help="归属用户 UUID（--from-extra 无法解析时的兜底）")
    ap.add_argument("--from-extra", action="store_true",
                    help="优先使用 extra 中的 createdBy/created_by（必须是合法 UUID）")
    ap.add_argument("--apply", action="store_true", help="真正写入（默认只预览）")
    ap.add_argument("--limit", type=int, default=10000, help="单次最多处理行数（默认 10000）")
    args = ap.parse_args()

    if args.user and not UUID_RE.match(args.user):
        raise SystemExit(f"--user 必须是 UUID: {args.user!r}")
    if args.apply and not args.user and not args.from_extra:
        raise SystemExit("--apply 必须给出 --user 或 --from-extra（禁止无归属回填）")

    schema = schema_of(args.tenant)
    table = table_of(args.collection)
    qualified = f"{schema}.{table}"

    # 表存在性检查
    exists = run(
        "SELECT COUNT(*) FROM information_schema.tables "
        f"WHERE table_schema = {q(schema)} AND table_name = {q(table)}"
    ).strip()
    if exists != "1":
        raise SystemExit(f"物理表不存在: {qualified}")

    # 待回填行
    total = int(run(f"SELECT COUNT(*) FROM {qualified} WHERE created_by IS NULL").strip() or 0)
    print(f"集合 {schema}.{table}: created_by IS NULL 共 {total} 行")

    if args.from_extra:
        sample_sql = f"""
            SELECT id::text,
                   NULLIF(COALESCE(extra->>'createdBy', extra->>'created_by', ''), '')
            FROM {qualified}
            WHERE created_by IS NULL
            ORDER BY id
            LIMIT 5
        """
    else:
        sample_sql = f"""
            SELECT id::text, NULL
            FROM {qualified}
            WHERE created_by IS NULL
            ORDER BY id
            LIMIT 5
        """
    sample = run(sample_sql)
    print("样例 5 条：")
    print(sample if sample else "  （无）")

    if not args.apply:
        print("\n[dry-run] 未写库。确认后加 --apply（并给出 --user/--from-extra）。")
        return 0

    # 生成 UPDATE
    if args.from_extra:
        if args.user:
            expr = (
                f"CASE WHEN COALESCE(extra->>'createdBy', extra->>'created_by', '') "
                f"~ '^[0-9a-fA-F-]{{36}}$' "
                f"THEN COALESCE(extra->>'createdBy', extra->>'created_by')::uuid "
                f"ELSE {q(args.user)}::uuid END"
            )
        else:
            expr = (
                "CASE WHEN COALESCE(extra->>'createdBy', extra->>'created_by', '') "
                "~ '^[0-9a-fA-F-]{36}$' "
                "THEN COALESCE(extra->>'createdBy', extra->>'created_by')::uuid "
                "ELSE NULL END"
            )
    else:
        expr = f"{q(args.user)}::uuid"

    sql = f"""
        BEGIN;
        WITH target AS (
            SELECT id FROM {qualified}
            WHERE created_by IS NULL
            ORDER BY id
            LIMIT {max(1, args.limit)}
        )
        UPDATE {qualified} t
        SET created_by = {expr}
        WHERE t.id IN (SELECT id FROM target)
          AND {expr} IS NOT NULL;
        SELECT COUNT(*) FROM {qualified} WHERE created_by IS NOT NULL;
        INSERT INTO audit_log (tenant_id, user_id, username, action, resource, resource_id, payload_json)
        VALUES ({q(args.tenant)}, 'system', 'backfill-created-by.py',
                'BACKFILL_CREATED_BY', {q(table)}, NULL,
                {q(json.dumps({"collection": args.collection, "fromExtra": args.from_extra,
                               "user": args.user, "limit": args.limit}, ensure_ascii=False))});
        COMMIT;
    """
    before_with_owner = int(run(
        f"SELECT COUNT(*) FROM {qualified} WHERE created_by IS NOT NULL").strip() or 0)
    run(sql)
    after_with_owner = int(run(
        f"SELECT COUNT(*) FROM {qualified} WHERE created_by IS NOT NULL").strip() or 0)
    print(f"[apply] 已回填：{after_with_owner - before_with_owner} 行；"
          f"当前有归属 {after_with_owner} 行；已写 audit_log(BACKFILL_CREATED_BY)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
