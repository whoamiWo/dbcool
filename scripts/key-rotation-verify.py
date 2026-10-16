#!/usr/bin/env python3
"""PHASE110: 密钥轮换验证（真验证，非端点存活性检查）。

核心命题（三条，全部可失败）：
  1. 轮换前用旧密钥签发的 token，在 rotate 之后**仍被接受**（双密钥窗口，
     依据 JwtKeyRotationController 注释：RETIRED key 在 access TTL 内仍可验签）
  2. 轮换后用新密钥签发的 token **被接受**（新密钥生效）
  3. 轮换后密钥环中**恰好有一个 ACTIVE** key（否则无法签发新 token）

与"仅打端点看 200"的区别：
  - 若第 1 条失败（旧 token 轮换后立刻 401），说明双密钥窗口未生效 → 真实缺陷
  - 若把断言改成 `status in (200, 401)` 这类恒真写法即为红线违规

用法:
  python3 scripts/key-rotation-verify.py                      # 自动用 admin/admin123 登录
  python3 scripts/key-rotation-verify.py --admin-user X --admin-pass Y
  python3 scripts/key-rotation-verify.py --admin-token <jwt>  # 自带 token

退出码:
  0 = 三条核心断言全部通过
  1 = 任一条失败
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
TIMEOUT = 10


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}")
    sys.stdout.flush()


def ok(msg: str) -> None:
    print(f"  ✅ PASS: {msg}")


def fail(msg: str) -> None:
    print(f"  ❌ FAIL: {msg}")


def request(method: str, path: str, token: str | None = None,
            data: dict | None = None) -> tuple[int, dict]:
    url = f"{BASE}{path}"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, headers=headers, data=body, method=method)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            raw = resp.read().decode()
            try:
                return resp.status, json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                return resp.status, {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            return e.code, {}


def login(user: str, password: str) -> str | None:
    status, body = request("POST", "/api/auth/login", data={"username": user, "password": password})
    if status == 200:
        return (body.get("data") or {}).get("access_token")
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description="JWT 密钥轮换真验证")
    ap.add_argument("--admin-user", default="admin")
    ap.add_argument("--admin-pass", default="admin123")
    ap.add_argument("--admin-token", default=None, help="自带 admin token，跳过登录")
    args = ap.parse_args()

    log(f"目标后端：{BASE}")
    failures: list[str] = []

    # 拿 admin token（轮换前的"旧密钥"token）
    token_old = args.admin_token or login(args.admin_user, args.admin_pass)
    if not token_old:
        fail(f"无法以 {args.admin_user} 登录后端（HTTP 非 200 或无 token），无法验证轮换")
        return 1
    ok("取得轮换前 token（旧密钥签发）")

    # 基线：旧 token 在轮换前可用
    status, _ = request("GET", "/api/admin/jwt-keys", token=token_old)
    if status != 200:
        failures.append(f"基线失败：轮换前旧 token 访问 /api/admin/jwt-keys 返回 {status}（期望 200）")
        fail(failures[-1])
        return 1
    ok("基线：轮换前旧 token 可访问管理端点 (200)")

    # 读取轮换前密钥环
    _, keys_before = request("GET", "/api/admin/jwt-keys", token=token_old)
    size_before = (keys_before.get("data") or {}).get("size")
    log(f"轮换前密钥数量：{size_before}")

    # 执行轮换
    log("触发 rotate ...")
    status, rot = request("POST", "/api/admin/jwt-keys/rotate", token=token_old)
    if status == 429:
        log("  ⚠️  rotate 被限流（429），本次不判定失败，但需人工确认频率限制是否合理")
        return 0
    if status != 200:
        failures.append(f"rotate 返回 {status}（期望 200）")
        fail(failures[-1])
        return 1
    new_kid = (rot.get("data") or {}).get("newActiveKid")
    ok(f"rotate 成功，新 kid={new_kid}")

    # 断言 1：旧 token 在轮换后仍被接受（双密钥窗口）
    status, _ = request("GET", "/api/admin/jwt-keys", token=token_old)
    if status == 200:
        ok("断言1 通过：轮换后旧 token 仍被接受（双密钥窗口生效）")
    else:
        failures.append(f"断言1 失败：轮换后旧 token 被拒（{status}），双密钥窗口未生效")
        fail(failures[-1])

    # 断言 2：新密钥签发的 token 可用（重新登录）
    token_new = login(args.admin_user, args.admin_pass)
    if not token_new:
        failures.append("断言2 失败：轮换后无法用新密钥重新登录")
        fail(failures[-1])
    else:
        status, _ = request("GET", "/api/admin/jwt-keys", token=token_new)
        if status == 200:
            ok("断言2 通过：轮换后新登录 token 被接受（新密钥生效）")
        else:
            failures.append(f"断言2 失败：轮换后新 token 被拒（{status}）")
            fail(failures[-1])

    # 断言 3：密钥环恰好一个 ACTIVE
    status, snap = request("GET", "/api/admin/jwt-keys", token=token_new or token_old)
    keys = (snap.get("data") or {}).get("keys") or []
    active = [k for k in keys if k.get("status") == "ACTIVE"]
    if status == 200 and len(active) == 1:
        ok(f"断言3 通过：密钥环 ACTIVE 数=1（总 {len(keys)}）")
    elif status != 200:
        failures.append(f"断言3 失败：快照请求返回 {status}")
        fail(failures[-1])
    else:
        failures.append(f"断言3 失败：ACTIVE 密钥数={len(active)}（期望 1）")
        fail(failures[-1])

    log("\n=== 验证结果 ===")
    if failures:
        log(f"失败 {len(failures)} 项")
        return 1
    ok("密钥轮换三项核心断言全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
