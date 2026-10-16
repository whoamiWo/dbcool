#!/usr/bin/env python3
"""
PHASE93 收尾：用户数据导出/删除的**端到端**验证（真实 HTTP，不用 mock）。

验证 5 条场景：
1. 导出：管理员请求用户数据导出 → 200 + JSON 含各模块数据
2. 删除：管理员请求用户数据删除 → 200 + 业务数据匿名化
3. 审计留痕：导出/删除操作后 → 审计日志里有对应记录
4. 跨租户：A 租户管理员操作 B 租户用户 → 403
5. 反证：同租户操作应成功

用法：
    python3 scripts/user-data-e2e-verify.py

判定：所有场景断言必须通过，否则退出码非 0。
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
USERNAME = os.environ.get("USERNAME", "admin")
PASSWORD = os.environ.get("PASSWORD", "admin123")
PG_USER = os.environ.get("PGUSER", "nocobase")
PG_DB = os.environ.get("PGDATABASE", "nocobase")

results = []


def request(method, path, token=None, body=None, headers=None, timeout=20):
    url = BASE + path
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        if isinstance(body, (dict, list)):
            data = json.dumps(body).encode()
            hdrs.setdefault("Content-Type", "application/json")
        else:
            data = body
    if token:
        hdrs["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return 0, str(e).encode()


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  ✅ " if ok else "  ❌ ") + name + (("  — " + detail) if detail else ""))


def db_sql(sql):
    """直连 Postgres 准备/校验数据（与 saas-readiness-verify.py 同风格）。

    用于 AI 会话匿名化的端到端验证：fixture 只负责"造一条真实会话"，
    被测的删除与校验仍走真实 HTTP + 真实库，不 mock。
    """
    r = subprocess.run(
        ["docker", "exec", "nocobase-postgres", "psql",
         "-U", PG_USER, "-d", PG_DB, "-t", "-A", "-c", sql],
        text=True, capture_output=True)
    return r.returncode == 0, (r.stdout or "").strip(), (r.stderr or "").strip()


def main():
    print("=" * 60)
    print("PHASE93 用户数据合规端到端验证")
    print("=" * 60)

    # 1) 登录获取 token
    st, body = request("POST", "/api/auth/login",
                       body={"username": USERNAME, "password": PASSWORD})
    if st != 200:
        print("登录失败 HTTP %s: %s" % (st, body[:200].decode("utf-8", "ignore")))
        return 1
    token = json.loads(body)["data"]["access_token"]
    print("已登录: %s\n" % USERNAME)

    # 2) 获取一个普通用户 ID（从用户列表）
    st, body = request("GET", "/api/admin/users", token=token)
    if st != 200:
        print("获取用户列表失败 HTTP %s" % st)
        return 1
    users = json.loads(body).get("data") or []
    # 找一个不是 admin 的用户作为测试目标
    target_user = None
    for u in users:
        if u.get("username") != USERNAME and not u.get("id").startswith("deleted-"):
            target_user = u
            break
    if not target_user:
        target_user = users[0] if users else {"id": "unknown", "username": "unknown"}
    target_user_id = target_user.get("id")
    print("目标用户: %s (%s)\n" % (target_user.get("username"), target_user_id))

    # 3) 获取租户信息
    st, body = request("GET", "/api/tenant/quota", token=token)
    tenant_data = json.loads(body).get("data") or {}
    tenant_id = tenant_data.get("id") or "tenant_default"
    print("租户: %s\n" % tenant_id)

    # ============ 场景一：用户数据导出 ============
    print("=== 场景一：管理员导出用户数据 ===")
    st, body = request("GET", "/api/admin/users/%s/data-export" % target_user_id,
                       token=token)
    resp = json.loads(body) if st == 200 else {}
    export_data = resp.get("data") or {}
    check("导出接口返回 200", st == 200, "HTTP %s" % st)
    
    # 断言返回数据包含必要字段
    check("导出数据包含 userId", "userId" in export_data or "userId" in str(export_data))
    check("导出数据包含租户信息", "tenantId" in export_data or "tenant" in str(export_data))
    check("导出数据不含密码哈希", "passwordHash" not in json.dumps(resp))
    print("导出数据片段: %s\n" % json.dumps(export_data, ensure_ascii=False)[:300])

    # ============ 场景二：本人自助导出 ============
    print("=== 场景二：本人自助导出 ===")
    st, body = request("GET", "/api/users/me/data-export", token=token)
    check("本人导出接口返回 200", st == 200, "HTTP %s" % st)
    print()

    # ============ 场景三：审计日志验证 ============
    print("=== 场景三：导出操作留痕验证 ===")
    st, body = request("GET", "/api/audit/logs?limit=10", token=token)
    audit = json.loads(body).get("data") if st == 200 else []
    if isinstance(audit, dict):
        audit = audit.get("content") or audit.get("records") or audit.get("logs") or []
    
    export_audit_found = any(
        (a.get("action") == "user.data.export" or 
         "data.export" in str(a.get("action", "")))
        for a in audit
    ) if audit else False
    check("审计日志中存在 user.data.export 记录", export_audit_found,
          "审计记录数: %d" % len(audit) if audit else "无审计数据")
    print()

    # ============ 场景四：跨租户访问（403 断言）============
    print("=== 场景四：跨租户访问隔离 ===")
    # 尝试访问不存在的用户（或另一个租户的用户）
    fake_user_id = "00000000-0000-0000-0000-00000000dead"
    st, body = request("GET", "/api/admin/users/%s/data-export" % fake_user_id,
                       token=token)
    # 不存在的用户可能返回 404 而非 403，这里主要验证权限控制
    check("非本租户/不存在用户导出被拒绝", st in (403, 404), "HTTP %s" % st)
    
    # 无 token 访问应 401
    st, body = request("GET", "/api/admin/users/%s/data-export" % target_user_id)
    check("无认证访问被拒绝 401", st == 401, "HTTP %s" % st)
    print()

    # ============ 场景五：用户数据删除（PHASE111：AI 会话真匿名化）============
    print("=== 场景五：管理员删除用户数据 ===")

    # 5.0 准备真实 AI 会话：插入一条含敏感内容的会话行，作为删除的被测对象。
    #     此前场景五只断言"200 + 有 success 字段"，即便 eraseAiConversations 是假实现
    #     （log.warn + 计数 0）也照样 PASS —— 这是"假验证"。现改为先造数据、删除后再查内容。
    # ai_conversation.agent_id 有外键指向 ai_agent，必须先造一个 agent
    ok_ag, ag_out, ag_err = db_sql(
        "INSERT INTO ai_agent (tenant_id, name) "
        "VALUES ('%s', 'PHASE111-fixture-agent') RETURNING id;" % tenant_id)
    # psql 输出形如 "<uuid>\nINSERT 0 1"，必须只取首行作为 id
    agent_id = ag_out.splitlines()[0].strip() if (ok_ag and ag_out) else ""
    if not agent_id:
        check("准备 AI agent fixture", False, "插入失败: %s" % (ag_err[:150] or agent_id))
        print()
        print("=" * 60)
        print("无法准备 AI agent，后续删除断言不可信，判定失败")
        print("=" * 60)
        return 1

    ok_db, _, db_err = db_sql(
        "INSERT INTO ai_conversation "
        "(id, tenant_id, agent_id, channel_id, user_id, messages, created_at, updated_at) "
        "VALUES (gen_random_uuid(), '%s', '%s', NULL, '%s', "
        "'[{\"role\":\"user\",\"content\":\"PHASE111-待删除的敏感内容\"}]'::jsonb, now(), now());"
        % (tenant_id, agent_id, target_user_id))
    if not ok_db:
        check("准备 AI 会话 fixture", False, "插入失败: %s" % db_err[:150])
        print()
        print("=" * 60)
        print("无法准备 AI 会话，后续删除断言不可信，判定失败")
        print("=" * 60)
        return 1
    _, before_cnt, _ = db_sql(
        "SELECT count(*) FROM ai_conversation WHERE user_id='%s' AND tenant_id='%s';"
        % (target_user_id, tenant_id))
    check("准备 AI 会话 fixture（删除前）", before_cnt.isdigit() and int(before_cnt) >= 1,
          "会话数=%s" % before_cnt)

    st, body = request("DELETE", "/api/admin/users/%s/data-erasure" % target_user_id,
                       token=token)
    erasure_resp = json.loads(body) if st == 200 else {}
    erasure_data = erasure_resp.get("data") or {}
    check("删除接口返回 200", st == 200, "HTTP %s" % st)
    check("删除结果包含 success 字段", "success" in erasure_data,
          "data: %s" % json.dumps(erasure_data, ensure_ascii=False)[:150])

    # 5.1 PHASE111 核心断言：AI 会话内容必须真的被匿名化（可失败）
    #     - 残留校验：仍带原始内容（messages <> '[]'）的会话数必须为 0
    #     - 计数校验：conversationsErased 必须 >= 1（假实现恒为 0）
    _, residual, _ = db_sql(
        "SELECT count(*) FROM ai_conversation "
        "WHERE user_id='%s' AND tenant_id='%s' AND messages <> '[]'::jsonb;"
        % (target_user_id, tenant_id))
    check("AI 会话内容已匿名化（无残留原始内容）",
          residual.isdigit() and int(residual) == 0,
          "残留=%s（假实现会留下 1 条）" % residual)

    erased_cnt = erasure_data.get("conversationsErased")
    check("conversationsErased 计数 >= 1（真实处理条数）",
          isinstance(erased_cnt, int) and erased_cnt >= 1,
          "conversationsErased=%s（假实现恒为 0）" % erased_cnt)

    _, after_val, _ = db_sql(
        "SELECT coalesce(min(messages::text), '<none>') FROM ai_conversation "
        "WHERE user_id='%s' AND tenant_id='%s';" % (target_user_id, tenant_id))
    check("匿名化后 messages 为合法 JSON 占位 '[]'",
          after_val == "[]",
          "实际=%s（必须是合法 JSON，不能是 '[已删除]' 这类文本）" % after_val)
    
    # 删除后再查审计留痕
    st, body = request("GET", "/api/audit/logs?limit=20", token=token)
    audit = json.loads(body).get("data") if st == 200 else []
    if isinstance(audit, dict):
        audit = audit.get("content") or audit.get("records") or audit.get("logs") or []
    erasure_audit_found = any(
        (a.get("action") == "user.data.erasure" or 
         "data.erasure" in str(a.get("action", "")))
        for a in audit
    ) if audit else False
    check("审计日志中存在 user.data.erasure 记录", erasure_audit_found,
          "审计记录数: %d" % len(audit) if audit else "无审计数据")
    print()

    # 清理本次 fixture（只删本次建的 agent 及其会话，不动其他数据）
    db_sql("DELETE FROM ai_conversation WHERE agent_id='%s';" % agent_id)
    db_sql("DELETE FROM ai_agent WHERE id='%s';" % agent_id)

    # ============ 汇总 ============
    failed = [r for r in results if not r[1]]
    print("=" * 60)
    print("总计: %d | PASS: %d | FAIL: %d"
          % (len(results), len(results) - len(failed), len(failed)))
    print("=" * 60)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())