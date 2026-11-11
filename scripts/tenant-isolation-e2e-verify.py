#!/usr/bin/env python3
"""
PHASE95 R5: Cross-tenant Isolation E2E Validation (R5 rewrite)

Tests that two tenants (admin/tenant_default and tenantb_admin/tenant_b) cannot
access each other's resources via HTTP. Content assertions added for 8/9/12/14,
ACL policies copied for tenant B, failability verified.

Usage: python3 scripts/tenant-isolation-e2e-verify.py
"""
import atexit
import base64
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid as _uuid_mod

BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
TIMEOUT = 15

TENANT_B_ID = "tenant_b"
TENANT_B_USER = "tenantb_admin" + _uuid_mod.uuid4().hex[:4]
PG_USER = os.environ.get("PGUSER", "nocobase")
PG_DB = os.environ.get("PGDATABASE", "nocobase")
PG_HOST = os.environ.get("PGHOST", "localhost")
PG_PORT = os.environ.get("PGPORT", "5432")
PG_PASSWORD = os.environ.get("PGPASSWORD", "test_password")


def log(msg):
    print(msg)
    sys.stdout.flush()


# 连接方式统一由 _db 处理：CI（无 nocobase-postgres 容器）走本机 psql，
# 本地 docker-compose 回退 docker exec。此前写死 docker exec 导致 CI 中
# admin_hash 为空（"Could not get valid admin hash"），该步骤自 707925c 起一直红。
from _db import db_sql  # noqa: E402


def login(username, password):
    data = json.dumps({"username": username, "password": password}).encode()
    req = urllib.request.Request(
        f"{BASE}/api/auth/login", data=data,
        headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = json.loads(resp.read())
            t = body.get("data", {})
            return t.get("access_token"), t.get("user", {}).get("roles", []), t.get("user", {}).get("tenant_id")
    except Exception as e:
        return None, [], None


def http(method, path, token=None, data=None):
    url = f"{BASE}{path}"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers, method=method)
    if data is not None:
        req.data = json.dumps(data).encode()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            resp.read()
            return resp.status
    except urllib.error.HTTPError as e:
        e.read()
        return e.code
    except Exception:
        return -1


def fetch_json(path, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{BASE}{path}", headers=headers)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return json.loads(resp.read())


def raw_post(path, body, token):
    url = f"{BASE}{path}"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers, method="POST",
                                  data=json.dumps(body).encode())
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        e.read()
        return e.code, {}


def parse_jwt(token):
    parts = token.split(".")
    if len(parts) != 3: return {}
    pad = 4 - len(parts[1]) % 4
    if pad != 4: parts[1] += "=" * pad
    try:
        return json.loads(base64.urlsafe_b64decode(parts[1]))
    except: return {}


def wait_server(t=30):
    for _ in range(t):
        try:
            with urllib.request.urlopen(f"{BASE}/api/health", timeout=3) as r:
                if r.status == 200: return True
        except: pass
        time.sleep(1)
    return False


def seed_tenant_b():
    """Seed tenant B with admin user using admin's password hash + ACL policies."""
    _, admin_hash, _ = db_sql("SELECT password_hash FROM users WHERE username='admin' LIMIT 1")
    if not admin_hash.startswith("$2b$"):
        log(f"FATAL: Could not get valid admin hash: {admin_hash[:20]}")
        sys.exit(1)

    # Get admin role id from tenant_default for ACL copying
    _, admin_role_id_str, _ = db_sql(
        "SELECT id FROM roles WHERE name='admin' AND tenant_id='tenant_default' LIMIT 1"
    )
    if not admin_role_id_str:
        log("FATAL: Could not find admin role in tenant_default")
        sys.exit(1)
    admin_role_uuid = admin_role_id_str.strip()

    # Generate UUIDs with unique suffix to avoid conflicts
    user_uuid = str(_uuid_mod.uuid4())
    role_uuid = str(_uuid_mod.uuid4())

    db_sql(f"INSERT INTO tenant (id, name, slug, status, schema_name, created_at) VALUES ('{TENANT_B_ID}', '租户B', 'tenantb', 'ACTIVE', 'tenant_b', NOW()) ON CONFLICT DO NOTHING")
    # 动态表按租户 schema 定位（TenantContext.currentSchema），搜索隔离用例需要在
    # tenant_b schema 中真实建表，因此先确保 schema 存在。
    db_sql("CREATE SCHEMA IF NOT EXISTS tenant_b")
    db_sql(f"INSERT INTO roles (id, name, description, tenant_id, created_at) VALUES ('{role_uuid}', 'admin', '租户B管理员', '{TENANT_B_ID}', NOW()) ON CONFLICT DO NOTHING")
    db_sql(f"INSERT INTO users (id, username, password_hash, tenant_id, display_name, created_at) VALUES ('{user_uuid}', '{TENANT_B_USER}', '{admin_hash}', '{TENANT_B_ID}', 'AdminB', NOW()) ON CONFLICT DO NOTHING")
    db_sql(f"INSERT INTO user_roles (user_id, role_id) VALUES ('{user_uuid}', '{role_uuid}') ON CONFLICT DO NOTHING")

    # R5-7: Copy ACL policies from tenant_default admin to tenant_b admin
    # This ensures tenant B has the same permissions as tenant A's admin
    db_sql(f"""
        INSERT INTO acl_policies (role_id, type, subject, action, config, tenant_id, created_at)
        SELECT '{role_uuid}', type, subject, action, config, '{TENANT_B_ID}', NOW()
        FROM acl_policies
        WHERE role_id = '{admin_role_uuid}' AND tenant_id = 'tenant_default'
        ON CONFLICT DO NOTHING
    """)

    return user_uuid, role_uuid


def cleanup_tenant_b():
    log("\n--- Cleaning up tenant B ---")
    db_sql(f"DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE tenant_id='{TENANT_B_ID}');")
    db_sql(f"DELETE FROM users WHERE tenant_id='{TENANT_B_ID}';")
    db_sql(f"DELETE FROM roles WHERE tenant_id='{TENANT_B_ID}';")
    db_sql(f"DELETE FROM acl_policies WHERE tenant_id='{TENANT_B_ID}';")
    db_sql(f"DELETE FROM tenant WHERE id='{TENANT_B_ID}';")


def cleanup_tenant_default_resources():
    """Clean up resources left in tenant_default by this script.
    
    Resources created in tenant_default during tests:
    - KB, pages, automation rules, channels, messages, huddles, BI reports
    - Test users
    
    Cleanup order matters due to FK constraints.
    Note: Some resources (channels, huddles, BI reports) have no HTTP DELETE endpoint,
          so we clean them directly via db_sql.
    """
    log("\n--- Cleaning up tenant_default resources ---")
    # Clean test users created in tenant_default (those with 'testuser_a_' prefix)
    db_sql("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE tenant_id='tenant_default' AND username LIKE 'testuser_a_%');")
    db_sql("DELETE FROM users WHERE tenant_id='tenant_default' AND username LIKE 'testuser_a_%';")
    # Clean automation rules created in tenant_default
    # 必须给 OR 分支加括号：AND 优先级高于 OR，原写法等价于
    #   WHERE (tenant_id='tenant_default' AND name LIKE 'rule-a%') OR (name LIKE '%-a-%')
    # 第二个分支没有租户限制 → 会删除所有租户中名字含 "-a-" 的自动化规则。
    db_sql("DELETE FROM automation_rules WHERE tenant_id='tenant_default' "
           "AND (name LIKE 'rule-a%' OR name LIKE '%-a-%');")
    # Clean wiki pages (CASCADE will delete associated blocks)
    db_sql("DELETE FROM wiki_pages WHERE tenant_id='tenant_default' AND slug LIKE 'page-a-%';")
    # Clean wiki knowledge bases (CASCADE will delete pages and categories)
    db_sql("DELETE FROM wiki_knowledge_bases WHERE tenant_id='tenant_default' AND slug LIKE 'kb-a-%';")
    # Clean IM messages (soft delete - set deleted_at)
    db_sql("UPDATE im_messages SET deleted_at=CURRENT_TIMESTAMP WHERE tenant_id='tenant_default' AND content LIKE 'msg a%';")
    # Clean IM channels (no HTTP endpoint, direct DB delete)
    db_sql("DELETE FROM im_channels WHERE tenant_id='tenant_default' AND name LIKE 'ch-a%';")
    # Clean huddles (no HTTP endpoint, direct DB delete)
    db_sql("DELETE FROM im_huddles WHERE tenant_id='tenant_default' AND name LIKE 'huddle-a-%';")
    # Clean BI reports (no HTTP endpoint, direct DB delete)
    db_sql("DELETE FROM bi_reports WHERE tenant_id='tenant_default' AND name LIKE 'report-a%';")
    log("  tenant_default cleanup complete")


def main():
    print("=" * 70)
    print("PHASE95 R5: Cross-tenant Isolation E2E Validation (Improved)")
    print(f"Base: {BASE}")
    print(f"DB 目标: container={os.environ.get('PG_CONTAINER', '(auto)')} "
          f"db={PG_DB} host={PG_HOST}:{PG_PORT} strict={os.environ.get('PG_STRICT', '0')}")
    print("=" * 70)

    if not wait_server():
        log("FATAL: Server not ready")
        sys.exit(1)

    # 清理登录限流 key，避免与其他脚本互扰（PHASE109）。
    # CI 可能通过 IPv6 localhost（0:0:0:0:0:0:0:1）访问，不能只删两个 IPv4 key，
    # 否则前序脚本耗尽 5 次额度后，本脚本创建的新用户会登录 429。
    import subprocess as _sp
    try:
        scan = _sp.run(["docker", "compose", "exec", "-T", "redis", "redis-cli",
                        "--scan", "--pattern", "ratelimit:ip:*:/api/auth/login"],
                       check=False, capture_output=True, text=True)
        for key in scan.stdout.splitlines():
            key = key.strip()
            if key:
                _sp.run(["docker", "compose", "exec", "-T", "redis", "redis-cli", "DEL", key],
                        check=False, capture_output=True)
    except Exception:
        pass

    # Admin login (tenant A)
    admin_token, admin_roles, admin_tid = login("admin", "admin123")
    if not admin_token:
        log("FATAL: admin login failed")
        sys.exit(1)
    jwt = parse_jwt(admin_token)
    log(f"Tenant A admin: tid={jwt.get('tid')} roles={admin_roles}")

    # Seed tenant B
    log("\n... Seeding tenant B ...")
    _, role_uuid_b = seed_tenant_b()
    atexit.register(cleanup_tenant_b)

    # Tenant B login
    b_token, b_roles, b_tid = login(TENANT_B_USER, "admin123")
    if not b_token:
        log("FATAL: tenantb_admin login failed (rate limit?)")
        sys.exit(1)
    jwt_b = parse_jwt(b_token)
    # R5-6: Hard assertions on JWT
    if jwt_b.get("tid") != TENANT_B_ID or "admin" not in (b_roles or []):
        log(f"FATAL: tenant B credential invalid (tid={jwt_b.get('tid')}, roles={b_roles})")
        sys.exit(1)
    log(f"Tenant B admin: tid={jwt_b.get('tid')} roles={b_roles}")

    # Create resources in tenant A
    log("\n--- Creating resources in tenant A ---")
    kb_slug = f"kb-a-{_uuid_mod.uuid4().hex[:6]}"
    page_slug = f"page-a-{_uuid_mod.uuid4().hex[:6]}"
    user_suffix = _uuid_mod.uuid4().hex[:4]
    huddle_name = f"huddle-a-{user_suffix}"
    # R1d：BI 报表保存现在校验 collection READ；用真实动态集合（创建者自动获得策略）
    # 使用更长后缀：4 位 hex 在多次运行后会与遗留集合碰撞，导致建表 409/失败
    report_collection = f"rpt_{_uuid_mod.uuid4().hex[:8]}"
    _, b = raw_post("/api/collections", {
        "name": report_collection,
        "fields": [{"name": "title", "type": "text"}]
    }, admin_token)
    if b.get("code") != 0:
        log(f"FATAL: report collection creation failed: {b}")
        sys.exit(1)

    _, b = raw_post("/api/wiki/kb", {"name": "kb-a", "slug": kb_slug, "description": "test"}, admin_token)
    kb_id_a = b.get("data", {}).get("id")
    if not kb_id_a:
        log(f"FATAL: KB creation failed: {b}")
        sys.exit(1)
    log(f"  KB A: id={kb_id_a}")

    _, b = raw_post("/api/wiki/pages", {"knowledge_base_id": kb_id_a, "slug": page_slug, "title": "Page A", "content": "test"}, admin_token)
    page_id_a = b.get("data", {}).get("id")
    if not page_id_a:
        log(f"FATAL: Page creation failed: {b}")
        sys.exit(1)
    log(f"  Page A: id={page_id_a}")

    _, b = raw_post("/api/automation", {
        "name": "rule-a", "description": "test", "collectionName": "users",
        "triggerType": "manual", "triggerConfig": {}, "conditions": [], "actions": []
    }, admin_token)
    rule_id_a = b.get("data", {}).get("id")
    if not rule_id_a:
        log(f"FATAL: Automation rule creation failed: {b}")
        sys.exit(1)
    log(f"  Automation rule A: id={rule_id_a}")

    _, b = raw_post("/api/im/channels", {"name": "ch-a", "type": "public"}, admin_token)
    channel_id_a = b.get("data", {}).get("id")
    if not channel_id_a:
        log(f"FATAL: Channel creation failed: {b}")
        sys.exit(1)
    log(f"  Channel A: id={channel_id_a}")

    _, b = raw_post("/api/im/messages", {"channelId": channel_id_a, "content": "msg a", "contentType": "text"}, admin_token)
    msg_id_a = b.get("data", {}).get("id")
    if not msg_id_a:
        log(f"FATAL: Message creation failed: {b}")
        sys.exit(1)
    log(f"  Message A: id={msg_id_a}")

    _, b = raw_post("/api/im/huddle", {"channelId": channel_id_a, "name": huddle_name}, admin_token)
    huddle_id_a = b.get("data", {}).get("id")
    if not huddle_id_a:
        log(f"FATAL: Huddle creation failed: {b}")
        sys.exit(1)
    log(f"  Huddle A: id={huddle_id_a}")

    _, b = raw_post("/api/bi/reports", {
        "collectionName": report_collection,
        "name": "report-a",
        "type": "PIVOT",
        "config": {}
    }, admin_token)
    report_id_a = b.get("data", {}).get("id")
    if not report_id_a:
        log(f"FATAL: BI report creation failed: {b}")
        sys.exit(1)
    log(f"  BI Report A: id={report_id_a}")

    # Create a test user in tenant A for role tests
    _, b = raw_post("/api/admin/users", {
        "username": f"testuser_a_{user_suffix}",
        "password": "password123",
        "displayName": "Test User A"
    }, admin_token)
    user_id_a = b.get("data", {}).get("id")
    if not user_id_a:
        log(f"FATAL: User creation failed: {b}")
        sys.exit(1)
    log(f"  Test User A: id={user_id_a}")

    # R5-7: Get admin role id from tenant_default for ACL copying and baseline role assignment
    _, role_id_a_str, _ = db_sql(
        "SELECT id FROM roles WHERE name='admin' AND tenant_id='tenant_default' LIMIT 1"
    )
    role_id_a = role_id_a_str.strip() if role_id_a_str else None
    if not role_id_a:
        log("FATAL: Could not get admin role_id from tenant_default")
        sys.exit(1)
    log(f"  Role A (admin): id={role_id_a}")

    # Get a role from tenant_b for role assignment test
    _, role_id_b_str, _ = db_sql(f"SELECT id FROM roles WHERE tenant_id='{TENANT_B_ID}' LIMIT 1")
    role_id_b = role_id_b_str.strip() if role_id_b_str else None
    if not role_id_b:
        log("FATAL: Could not get role_id from tenant_b")
        sys.exit(1)
    log(f"  Role B: id={role_id_b}")

    # Cross-tenant tests
    log("\n--- Cross-tenant access tests ---")
    results = []
    total, passed = 0, 0

    def check(label, method, path, exp_a, exp_b, content_check=None):
        nonlocal total, passed
        total += 1
        a = http(method, path, admin_token)
        b_status = http(method, path, b_token)
        ok_a = a == exp_a
        ok_b = b_status == exp_b
        ok = ok_a and ok_b

        # Content assertions for specific tests (only run when B's status matches expectation)
        if content_check and b_status == exp_b:
            try:
                resp_body = fetch_json(path, b_token) if b_status == 200 else None
                content_ok = content_check(resp_body)
                if not content_ok:
                    ok = False
                    log(f"  Content check failed for {label}: {resp_body}")
            except Exception as e:
                log(f"  Content check error for {label}: {e}")
                ok = False

        if ok: passed += 1
        mark = "✅" if ok else "❌"
        content_info = ""
        if content_check:
            try:
                resp_body = fetch_json(path, b_token) if b_status == 200 else None
                content_info = f", content={json.dumps(resp_body)[:100] if resp_body else 'none'}"
            except:
                content_info = ", content=N/A"
        log(f"  {mark} {label}: A→{a} B→{b_status} (want A={exp_a} B={exp_b}){content_info}")
        results.append((label, ok))
        return ok

    # R5-3: enable exp_a in check() - now both A and B sides are verified

    # 1. automation A own - GET to verify it exists
    check("1-automation A own", "GET", f"/api/automation/{rule_id_a}", 200, 404)

    # R6-2: Role assignment baseline - A assigns A's user to A's role (should succeed)
    check("role-assign baseline A→A", "POST", f"/api/admin/users/{user_id_a}/roles/{role_id_a}", 200, 404)

    # 3. B → GET /api/admin/users/{A用户id} → 404
    check("3-users B→A (GET)", "GET", f"/api/admin/users/{user_id_a}", 200, 404)

    # 4. B → POST /api/admin/users/{A用户}/roles/{B角色} → 404 (用户非当前租户)
    # Both A and B get 404: A's user exists in tenant_default but role B is in tenant_b;
    # B's user A doesn't exist in tenant_b's schema
    check("4-role-assign B→A", "POST", f"/api/admin/users/{user_id_a}/roles/{role_id_b}", 404, 404)

    # 6. B → GET /api/wiki/kb/{A的kb} → 404 (KB does not exist in tenant_b)
    # After ACL policies copied, tenant B has READ permission, but KB not in their tenant
    check("6-wikiKB B→A", "GET", f"/api/wiki/kb/{kb_id_a}", 200, 404)

    # 7. B → GET /api/wiki/pages/{A的page} → 404 (Page does not exist in tenant_b)
    check("7-wikiPage B→A", "GET", f"/api/wiki/pages/{page_id_a}", 200, 404)

    # 8. B → GET /api/wiki/kb/{A的kb}/categories → 200 + 空列表 (R5-2: content assertion)
    def check_categories(body):
        if not body or body.get("code") != 0:
            return False
        data = body.get("data", [])
        return isinstance(data, list) and len(data) == 0
    check("8-categories B→A", "GET", f"/api/wiki/kb/{kb_id_a}/categories", 200, 200, check_categories)

    # 9. B → GET /api/wiki/kb/{A的kb}/templates → 200 + 空列表
    def check_templates(body):
        if not body or body.get("code") != 0:
            return False
        data = body.get("data", [])
        return isinstance(data, list) and len(data) == 0
    check("9-templates B→A", "GET", f"/api/wiki/kb/{kb_id_a}/templates", 200, 200, check_templates)

    # 10. B → GET /api/im/huddle/{A的huddle} → 404
    check("10-huddle B→A", "GET", f"/api/im/huddle/{huddle_id_a}", 200, 404)

    # 11. B → GET /api/im/messages/{A的msg}/thread → 404
    check("11-thread B→A", "GET", f"/api/im/messages/{msg_id_a}/thread", 200, 404)

    # 12. B → GET /api/im/messages/pins?channelId={A的channel} → 200 + 空列表
    def check_pins(body):
        if not body or body.get("code") != 0:
            return False
        data = body.get("data", {})
        pins = data.get("pins", [])
        return isinstance(pins, list) and len(pins) == 0
    check("12-pins B→A", "GET", f"/api/im/messages/pins?channelId={channel_id_a}", 200, 200, check_pins)

    # 13. B → GET /api/bi/reports?collectionName=<A用的值> → 200 + 空数组
    def check_reports(body):
        if not body or body.get("code") != 0:
            return False
        data = body.get("data", [])
        return isinstance(data, list) and len(data) == 0
    check("13-reports B→A", "GET", f"/api/bi/reports?collectionName={report_collection}", 200, 200, check_reports)

    # PHASE96 B2: 写端点跨租户覆盖改写 → 404。
    # saveReport 在 body 带 id 时走 findByIdAndTenantId + orElseThrow(NOT_FOUND)，
    # 因此 B 传 A 的报表 id 必须 404（此前 findById 无租户校验，可覆盖改写他租户报表配置）。
    # 这是本批唯一针对"写操作"的跨租户断言——只测 GET 列表证明不了越权写入被堵住。
    log("\n--- PHASE96 B2: BI 报表写端点跨租户覆盖改写 ---")
    overwrite_status = http("POST", "/api/bi/reports", b_token, {
        "id": report_id_a,
        "collectionName": report_collection,
        "name": "hijacked-by-tenant-b",
        "type": "PIVOT",
        "config": {"injected": True}
    })
    total += 1
    if overwrite_status == 404:
        passed += 1
        log(f"  ✅ 15-biReport-write B→A: B 传 A 的报表 id → 404（越权覆盖被拒）")
    else:
        log(f"  ❌ 15-biReport-write B→A: B 传 A 的报表 id → {overwrite_status}（期望 404，越权写入未被拦住）")
    # 正向基线：A 用自己的报表 id 必须能保存成功（证明 404 归因于租户校验，而非端点本身报错）
    _, a = raw_post("/api/bi/reports", {
        "id": report_id_a,
        "collectionName": report_collection,
        "name": "report-a-updated-by-owner",
        "type": "PIVOT",
        "config": {}
    }, admin_token)
    a_ok = a.get("code") == 0 and a.get("data", {}).get("id") == report_id_a
    total += 1
    if a_ok:
        passed += 1
        log(f"  ✅ 16-biReport-write A→A 正向基线: A 保存自己的报表 → 200（证明 404 归因于租户隔离）")
    else:
        log(f"  ❌ 16-biReport-write A→A 正向基线: A 保存自己的报表失败 → {a}")

    # 14. B → GET /api/admin/users → 200 且列表不含 A 的任何用户名
    def check_users(body):
        if not body or body.get("code") != 0:
            return False
        data = body.get("data", [])
        if not isinstance(data, list):
            return False
        # All users should be from tenant_b
        for user in data:
            if user.get("tenant_id") != TENANT_B_ID:
                return False
        return True
    check("14-users B≠A", "GET", "/api/admin/users", 200, 200, check_users)

    # R6：4 张新表的跨租户断言（org_unit / approval_template / approval_request / approval_cc）
    log("\n--- R6: enterprise tables cross-tenant ---")
    org_unit_id = str(_uuid_mod.uuid4())
    org_unit_name = "R6部门-" + _uuid_mod.uuid4().hex[:6]
    db_sql(
        "INSERT INTO org_unit (id, tenant_id, parent_id, name, path, sort, source, deleted, created_at, updated_at) "
        f"VALUES ('{org_unit_id}', 'tenant_default', NULL, '{org_unit_name}', '/{org_unit_id}/', 0, 'LOCAL', FALSE, NOW(), NOW()) "
        "ON CONFLICT DO NOTHING"
    )

    def check_tree_excludes_a(body):
        if not body or body.get("code") != 0:
            return False
        return org_unit_name not in json.dumps(body, ensure_ascii=False)

    check("17-orgUnit B≠A", "GET", "/api/org/tree", 200, 200, check_tree_excludes_a)
    check("18-orgMember B→A", "GET", f"/api/org/members/{user_id_a}", 200, 404)

    tmpl_id = str(_uuid_mod.uuid4())
    req_id = str(_uuid_mod.uuid4())
    db_sql(
        "INSERT INTO approval_template (id, tenant_id, name, workflow_id, approver_type, enabled, created_at) "
        f"VALUES ('{tmpl_id}', 'tenant_default', 'R6审批', '{_uuid_mod.uuid4()}', 'USER', TRUE, NOW()) "
        "ON CONFLICT DO NOTHING"
    )
    db_sql(
        "INSERT INTO approval_request (id, tenant_id, template_id, applicant_id, form_data, status, submitted_at) "
        f"VALUES ('{req_id}', 'tenant_default', '{tmpl_id}', '{user_id_a}', '{{}}'::jsonb, 'PENDING', NOW()) "
        "ON CONFLICT DO NOTHING"
    )
    db_sql(
        "INSERT INTO approval_cc (request_id, user_id, created_at) "
        f"VALUES ('{req_id}', '{user_id_a}', NOW()) ON CONFLICT DO NOTHING"
    )

    def check_templates_excludes_a(body):
        if not body or body.get("code") != 0:
            return False
        return tmpl_id not in json.dumps(body, ensure_ascii=False)

    check("19-approvalTemplate B≠A", "GET", "/api/approvals/templates", 200, 200,
          check_templates_excludes_a)
    # A 管理员可见（R1e ADMIN 分支），B 一律 404；A 非申请人 cancel → 403，B → 404
    check("20-approvalRequest B→A read", "GET", f"/api/approvals/{req_id}", 200, 404)
    check("21-approvalRequest B→A write", "POST", f"/api/approvals/{req_id}/cancel", 403, 404)

    def check_cc_excludes_a(body):
        if not body or body.get("code") != 0:
            return False
        return req_id not in json.dumps(body, ensure_ascii=False)

    check("22-approvalCc B≠A", "GET", "/api/approvals/cc", 200, 200, check_cc_excludes_a)

    # R6-4：搜索索引的跨租户可见性。
    # 直接写索引行（不依赖异步索引监听器），并在各自租户写入真实记录 ——
    # 否则 getRecordsByIds 返回空会让「total == 0」在没有证据的情况下也通过。
    log("\n--- R6: search index cross-tenant visibility ---")

    def search_total(keyword, token):
        body = fetch_json(
            f"/api/search?keyword={urllib.parse.quote(keyword)}&types=record&limit=20",
            token)
        if body.get("code") != 0:
            return -1
        return body.get("data", {}).get("total", -1)

    def make_search_fixture(token, tenant_id, prefix):
        needle = _uuid_mod.uuid4().hex
        collection = f"{prefix}_{_uuid_mod.uuid4().hex[:8]}"
        status, body = raw_post("/api/collections", {
            "name": collection,
            "title": "R6 搜索隔离",
            "fields": [{"name": "title", "type": "text"}],
        }, token)
        if status not in (200, 201) or body.get("code") != 0:
            return None, None, None, needle, f"collection create {status}: {body}"
        status, body = raw_post(f"/api/collections/{collection}/records",
                                {"title": needle}, token)
        if status not in (200, 201) or body.get("code") != 0:
            return collection, None, None, needle, f"record create {status}: {body}"
        record_id = body.get("data", {}).get("id")
        index_id = str(_uuid_mod.uuid4())
        meta = json.dumps({"collection": collection, "recordId": record_id})
        db_sql(
            "INSERT INTO unified_search_index "
            "(id, entity_type, entity_id, tenant_id, title, content, metadata, created_at, updated_at) "
            f"VALUES ('{index_id}', 'record', '{record_id}', '{tenant_id}', "
            f"'{needle}', 'R6 搜索隔离 {needle}', '{meta}'::jsonb, NOW(), NOW()) "
            "ON CONFLICT DO NOTHING"
        )
        return collection, record_id, index_id, needle, None

    # 23. A 的记录索引，B 搜索不到
    col_a, rec_a, idx_a, needle_a, err_a = make_search_fixture(
        admin_token, "tenant_default", "r6_iso")
    total += 1
    if err_a:
        log(f"  ❌ 23-search A record not visible to B: fixture failed — {err_a}")
        results.append(("23-search A record not visible to B", False))
    else:
        a_total = search_total(needle_a, admin_token)
        b_total = search_total(needle_a, b_token)
        ok = a_total >= 1 and b_total == 0
        if ok:
            passed += 1
        log(f"  {'✅' if ok else '❌'} 23-search A record not visible to B: "
            f"A={a_total} B={b_total} (want A>=1 B=0)")
        results.append(("23-search A record not visible to B", ok))

    # 24. B 的记录索引，A 搜索不到（对称）
    col_b, rec_b, idx_b, needle_b, err_b = make_search_fixture(
        b_token, TENANT_B_ID, "r6_isob")
    total += 1
    if err_b:
        log(f"  ❌ 24-search B record not visible to A: fixture failed — {err_b}")
        results.append(("24-search B record not visible to A", False))
    else:
        a_total = search_total(needle_b, admin_token)
        b_total = search_total(needle_b, b_token)
        ok = b_total >= 1 and a_total == 0
        if ok:
            passed += 1
        log(f"  {'✅' if ok else '❌'} 24-search B record not visible to A: "
            f"A={a_total} B={b_total} (want B>=1 A=0)")
        results.append(("24-search B record not visible to A", ok))

    # 清理搜索隔离用例（索引行 + collection 元数据/物理表）
    for idx in (idx_a, idx_b):
        if idx:
            db_sql(f"DELETE FROM unified_search_index WHERE id='{idx}';")
    if col_a:
        http("DELETE", f"/api/collections/{col_a}", admin_token)
    if col_b:
        http("DELETE", f"/api/collections/{col_b}", b_token)

    # 清理 R6 数据
    db_sql(f"DELETE FROM approval_cc WHERE request_id='{req_id}';")
    db_sql(f"DELETE FROM approval_request WHERE id='{req_id}';")
    db_sql(f"DELETE FROM approval_template WHERE id='{tmpl_id}';")
    db_sql(f"DELETE FROM org_unit WHERE id='{org_unit_id}';")

    # R5-7: Control assertion - random ID should not give same result as A's real resource
    log("\n--- R5-7: Control assertion for random IDs ---")
    # R6-3 corrected control: A side random ID should be 404, proving resource existence.
    random_kb_id = _uuid_mod.uuid4()
    # A side: real KB 200, random KB 404 => proves existence.
    check("control random KB A→404", "GET", f"/api/wiki/kb/{random_kb_id}", 404, 404)
    # Diagnostic: B side random ID (no judgment)
    random_status = http("GET", f"/api/wiki/kb/{random_kb_id}", b_token)
    log(f"  Random KB ID B→{random_status} (diagnostic, not judged)")

    # R5-1: Item 15 removed (no HTTP path for AI conversation cross-tenant test)
    # Instead use unit test AgentServiceTenantGuardTest.java

    log("\n" + "=" * 70)
    log(f"Results: {passed}/{total} PASS")
    for label, ok in results:
        mark = "✅" if ok else "❌"
        log(f"  {mark} {label}")

    if passed < total:
        log("\n⚠️  Some tests failed")
        sys.exit(1)

    # Clean up resources created in tenant_default during this test run
    http("DELETE", f"/api/collections/{report_collection}", admin_token)
    cleanup_tenant_default_resources()

    sys.exit(0)


if __name__ == "__main__":
    main()
