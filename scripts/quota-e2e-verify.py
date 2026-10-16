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

import base64
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


def token_tenant(token: str) -> str | None:
    """从 access_token 的 JWT payload 解析 tid（当前登录用户所属租户）。

    上传附件归属的是「登录用户的租户」，而对某个 admin 租户设配额不会影响它。
    此前场景一给 tenants[0]（tenant_c）设 storageLimit，但上传落到 tenant_default，
    导致配额根本没被测到（返回 200）并被误判为"存储配额失效"。
    """
    try:
        seg = token.split(".")[1]
        seg += "=" * (-len(seg) % 4)
        return json.loads(base64.urlsafe_b64decode(seg)).get("tid")
    except Exception:
        return None


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
    # 配额校验作用于 body 里的 tenantId，而 /api/tenant/quota 返回的是「当前登录用户
    # 所属租户」的用量。此前 tid 取 tenants[0]（tenant_c）与 quota API 的租户不一致，
    # 导致"已用量"与"被设上限的租户"错配，把后端的正确放行(201)误判为配额失效。
    tid = token_tenant(token) or tenants[0].get("id")
    print("目标租户(配额设置与用户关联均作用于此): %s\n" % tid)

    # 3) 记录原始配额（便于恢复）
    st, body = request("GET", "/api/tenant/quota", token=token)
    orig = json.loads(body).get("data") or {}
    orig_storage = orig.get("storageLimit")
    orig_seats = orig.get("seatsLimit")
    print("原始配额: storageLimit=%s seatsLimit=%s used_seats=%s\n"
          % (orig_storage, orig_seats, orig.get("seatsUsed")))

    # ============ 场景一：存储配额 ============
    print("=== 场景一：存储配额超限须 403 ===")
    # 上传归属「登录用户的租户」，因此必须对那个租户设配额，
    # 否则测的是别的租户、配额实际从未被触达（曾误判为存储配额失效）。
    storage_tid = token_tenant(token) or tid
    print("（存储配额作用于租户: %s）" % storage_tid)
    tiny = 1024  # 1 KB
    st, _ = request("PUT", "/api/admin/tenants/%s/quota" % storage_tid, token=token,
                    body={"storageLimit": tiny})
    check("设置 storageLimit=%d 成功" % tiny, st == 200, "HTTP %s" % st)

    # 上传 ~8KB（远超 1KB 上限）
    # 后端 AttachmentController#upload 用 @RequestPart("file") MultipartFile，
    # 必须是 multipart/form-data；此前用 octet-stream 会导致后端 500
    # "Current request is not a multipart request"，把脚本 bug 伪装成配额缺陷。
    # 后端有文件类型白名单（image/png、image/jpeg 等），用 png 才能触达配额校验，
    # 否则先被 400「不允许的文件类型」拦下，测不到存储配额本身。
    payload = os.urandom(8 * 1024)
    boundary = "----quotaProbeBoundary"
    body_bytes = (
        '--%s\r\n'
        'Content-Disposition: form-data; name="file"; filename="quota-probe.png"\r\n'
        'Content-Type: image/png\r\n\r\n' % boundary
    ).encode() + payload + ('\r\n--%s--\r\n' % boundary).encode()
    st, body = request("POST", "/api/attachments/upload", token=token, body=body_bytes,
                       headers={"Content-Type": "multipart/form-data; boundary=%s" % boundary})
    check("上传 8KB（上限 1KB）须被拒 403", st == 403,
          "HTTP %s %s" % (st, body[:120].decode("utf-8", "ignore")))

    # 恢复
    if orig_storage is not None:
        request("PUT", "/api/admin/tenants/%s/quota" % storage_tid, token=token,
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
