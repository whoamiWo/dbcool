#!/usr/bin/env python3
"""
PHASE92 收尾：存储配额 / 席位配额的**端到端**验证（真实 HTTP，不用 mock）。

<p>为什么单独写：API 配额已用 k6 验证（quota-limit-test.js，实测 429 拦截）。
存储与席位两条此前**只做了单测**，没有真实流量验证 ——
而单测证明不了"过滤器/控制器真的在链路上拦住了请求"。

用法：
    python3 scripts/quota-e2e-verify.py

判定：两个场景都必须出现 403，否则退出码非 0。
"""

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
USERNAME = os.environ.get("USERNAME", "admin")
PASSWORD = os.environ.get("PASSWORD", "admin123")

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


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  ✅ " if ok else "  ❌ ") + name + (("  — " + detail) if detail else ""))


def main():
    # 1) 登录
    st, body = request("POST", "/api/auth/login",
                       body={"username": USERNAME, "password": PASSWORD})
    if st != 200:
        print("登录失败 HTTP %s: %s" % (st, body[:200]))
        return 1
    token = json.loads(body)["data"]["access_token"]
    print("已登录\n")

    # 2) 拿租户 ID
    st, body = request("GET", "/api/admin/tenants", token=token)
    if st != 200:
        print("获取租户列表失败 HTTP %s" % st)
        return 1
    tenants = json.loads(body).get("data") or []
    if not tenants:
        print("无租户数据，无法验证")
        return 1
    tid = tenants[0].get("id")
    print("目标租户: %s\n" % tid)

    # 3) 记录原始配额（便于恢复）
    st, body = request("GET", "/api/tenant/quota", token=token)
    orig = json.loads(body).get("data") or {}
    orig_storage = orig.get("storageLimit")
    orig_seats = orig.get("seatsLimit")
    print("原始配额: storageLimit=%s seatsLimit=%s used_seats=%s\n"
          % (orig_storage, orig_seats, orig.get("seatsUsed")))

    # ============ 场景一：存储配额 ============
    print("=== 场景一：存储配额超限须 403 ===")
    tiny = 1024  # 1 KB
    st, _ = request("PUT", "/api/admin/tenants/%s/quota" % tid, token=token,
                    body={"storageLimit": tiny})
    check("设置 storageLimit=%d 成功" % tiny, st == 200, "HTTP %s" % st)

    # 上传 ~8KB（远超 1KB 上限）
    payload = os.urandom(8 * 1024)
    st, body = request("POST", "/api/attachments/upload", token=token, body=payload,
                       headers={"Content-Type": "application/octet-stream",
                                "X-File-Name": "quota-probe.bin"})
    check("上传 8KB（上限 1KB）须被拒 403", st == 403,
          "HTTP %s %s" % (st, body[:120].decode("utf-8", "ignore")))

    # 恢复
    if orig_storage is not None:
        request("PUT", "/api/admin/tenants/%s/quota" % tid, token=token,
                body={"storageLimit": orig_storage})

    # ============ 场景二：席位配额 ============
    print("\n=== 场景二：席位已满时新增关联须 403 ===")
    st, body = request("GET", "/api/tenant/quota", token=token)
    cur = json.loads(body).get("data") or {}
    used = cur.get("seatsUsed") or 0
    # 把上限设为当前用量 → 立即"满"
    st, _ = request("PUT", "/api/admin/tenants/%s/quota" % tid, token=token,
                    body={"seatsLimit": used})
    check("设置 seatsLimit=%d（=当前用量）成功" % used, st == 200, "HTTP %s" % st)

    new_user = str(uuid.uuid4())
    st, body = request("POST", "/api/admin/users/%s/tenants" % new_user, token=token,
                       body={"tenantId": tid})
    check("席位已满时 link 新用户须被拒 403", st == 403,
          "HTTP %s %s" % (st, body[:120].decode("utf-8", "ignore")))

    # 放宽一个席位后应能成功（反证：上面的 403 确实来自席位而非其他原因）
    st, body2 = request("PUT", "/api/admin/tenants/%s/quota" % tid, token=token,
                        body={"seatsLimit": used + 1})
    st, body = request("POST", "/api/admin/users/%s/tenants" % new_user, token=token,
                       body={"tenantId": tid})
    check("放宽 1 个席位后 link 应成功（反证）", st in (200, 201),
          "HTTP %s %s" % (st, body[:120].decode("utf-8", "ignore")))

    # 解绑 + 恢复
    request("DELETE", "/api/admin/users/%s/tenants/%s" % (new_user, tid), token=token)
    if orig_seats is not None:
        request("PUT", "/api/admin/tenants/%s/quota" % tid, token=token,
                body={"seatsLimit": orig_seats})

    # 汇总
    failed = [r for r in results if not r[1]]
    print("\n" + "=" * 56)
    print("总计: %d | PASS: %d | FAIL: %d"
          % (len(results), len(results) - len(failed), len(failed)))
    print("=" * 56)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
