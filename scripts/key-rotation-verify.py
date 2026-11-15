#!/usr/bin/env python3
"""PHASE110: 密钥轮换验证（真验证，非端点存活性检查）。

2.1.5 发布约束：JWT 轮换默认禁用（`app.jwt.rotation.enabled=false`）。
因此本脚本有两种模式，由 `/api/admin/jwt-keys` 快照里的 `data.enabled` 决定：

A. 禁用模式（2.1.5 默认）—— 必须证明“禁用生效且共享密钥仍可用”：
  1. 快照明确返回 `enabled=false`
  2. `POST /api/admin/jwt-keys/rotate` 返回 409/501，不再生成新 key
  3. 轮换前后密钥环完全一致（没有 kid=k-1，ACTIVE 仍唯一）
  4. 登录所得旧 token 在 Java 侧仍被接受（GET /api/admin/jwt-keys 200）
  5. 同一 token 在 Python 侧仍被接受（GET /api/ai/whoami 200）
  这堵的是 2.1.5 的真实缺口：进程内随机 key 不持久化、不与 Python 共享，
  轮换后 `/api/ai/*` 与 `/api/admin/backup` 会统一 401。

B. 启用模式（仅当后端显式 `enabled=true`，为 2.1.6 共享 keyring 预留）：
  1. 轮换前旧 token 可用
  2. rotate 后用旧密钥签发的 token **仍被接受**（双密钥窗口）
  3. 轮换后新登录 token **被接受**（新密钥生效）
  4. 密钥环中**恰好有一个 ACTIVE** key

与“仅打端点看 200”的区别：断言写成 `status in (200, 401)` 这类恒真写法即为红线违规。

用法:
  python3 scripts/key-rotation-verify.py                      # 自动用 admin/admin123 登录
  python3 scripts/key-rotation-verify.py --admin-user X --admin-pass Y
  python3 scripts/key-rotation-verify.py --admin-token <jwt>  # 自带 token

环境变量:
  BASE_URL          Java 后端，默认 http://localhost:8080
  PYTHON_URL        Python 后端，默认 http://localhost:8000；ACCEPTANCE_MODE=1 时必须显式设置
  ACCEPTANCE_MODE   1 = 验收栈模式，缺 PYTHON_URL 时 fail closed
  REDIS_CONTAINER   限流 key 所在的 redis 容器，默认 nocobase-redis

退出码:
  0 = 当前模式下的核心断言全部通过
  1 = 任一条失败
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
TIMEOUT = 10
ACCEPTANCE_MODE = os.environ.get("ACCEPTANCE_MODE") == "1"


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}")
    sys.stdout.flush()


def parse_rotation_wait(body: dict) -> float:
    """从 429 响应体解析还需等待的秒数（轮换间隔保护）。

    响应 message 形如：
        "轮换速率超限:距上次轮换 1234 ms,需 ≥ 60000 ms"
    解析出 (需 ≥ 的 ms - 已过 ms)，加 2s 余量；解析失败则退回 62s（间隔 60s + 余量）。
    """
    msg = str(body.get("message") or "")
    try:
        import re
        nums = re.findall(r"(\d+)\s*ms", msg)
        if len(nums) >= 2:
            elapsed, required = int(nums[0]), int(nums[1])
            remain = (required - elapsed) / 1000.0 + 2.0
            return max(1.0, min(remain, 70.0))
    except Exception:
        pass
    return 62.0


def clear_rate_limit() -> None:
    """清理限流 key（best-effort），与 launch-final-verify.py 同款逻辑。

    rotate 接口被限流保护（管理员端点），脚本单独跑或编排串行跑都可能累计触发 429。
    清理是运维处理，不是放宽断言：清理后 rotate 必须真正返回 200 并执行断言。

    容器名支持 REDIS_CONTAINER 覆盖（默认 nocobase-redis）：验收隔离栈的 redis
    名为 nocobase-acc-redis，硬编码默认栈会导致限流 key 未被清理、登录持续 429。
    """
    redis_container = os.environ.get("REDIS_CONTAINER", "nocobase-redis")
    try:
        scan = subprocess.run(
            ["docker", "exec", redis_container, "redis-cli", "--scan", "--pattern", "ratelimit:*"],
            capture_output=True, text=True, timeout=20)
        keys = [k for k in (scan.stdout or "").split() if k.strip()]
        if keys:
            subprocess.run(["docker", "exec", redis_container, "redis-cli", "DEL"] + keys,
                           capture_output=True, text=True, timeout=20)
            log(f"  已清理 {len(keys)} 个限流 key")
    except Exception:
        pass


def ok(msg: str) -> None:
    print(f"  ✅ PASS: {msg}")


def fail(msg: str) -> None:
    print(f"  ❌ FAIL: {msg}")


def request(method: str, path: str, token: str | None = None,
            data: dict | None = None, base: str | None = None) -> tuple[int, dict]:
    url = f"{(base or BASE).rstrip('/')}{path}"
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
    """登录；登录端点确有 HTTP 限流（ratelimit.login=5/300s，基于 IP 累计），
    编排串行跑多个脚本时会互相挤兑，故失败先清限流再重试一次。"""
    for attempt in range(1, 3):
        status, body = request("POST", "/api/auth/login", data={"username": user, "password": password})
        if status == 200:
            return (body.get("data") or {}).get("access_token")
        if attempt == 1:
            clear_rate_limit()
            time.sleep(1)
    return None


def token_kid(token: str) -> str | None:
    """读取 JWT header 的 kid（只解码 header，不验签）。"""
    try:
        header_b64 = token.split(".")[0]
        header = json.loads(base64.urlsafe_b64decode(header_b64 + "=" * (-len(header_b64) % 4)))
        return header.get("kid")
    except Exception:  # noqa: BLE001
        return None


def python_url() -> str | None:
    url = os.environ.get("PYTHON_URL", "").rstrip("/")
    if url:
        return url
    if ACCEPTANCE_MODE:
        return None
    log("  ℹ️  未设置 PYTHON_URL，开发模式回退 http://localhost:8000")
    return "http://localhost:8000"


def check_python_token(token: str) -> bool:
    """证明与 Java 共享 JWT_SECRET 的 Python 侧也能验签同一个 token。"""
    url = python_url()
    if url is None:
        fail("ACCEPTANCE_MODE=1 但未设置 PYTHON_URL，无法证明 Python 侧仍可验签")
        return False
    status, body = request("GET", "/api/ai/whoami", token=token, base=url)
    data = body.get("data") or {}
    if status == 200 and data.get("user_id"):
        ok(f"Python 侧接受同一 token：{url}/api/ai/whoami → 200（user_id={data['user_id']}）")
        return True
    fail(f"Python 侧拒绝同一 token：{url}/api/ai/whoami → {status} {body}")
    return False


def verify_disabled(token_old: str, snap_before: dict) -> int:
    """2.1.5 默认模式：断言轮换已禁用且共享 k-0 在 Java/Python 均可用。"""
    failures: list[str] = []
    data = snap_before.get("data") or {}
    size_before = data.get("size")
    keys_before = data.get("keys") or []
    active_before = [k for k in keys_before if k.get("status") == "ACTIVE"]

    if data.get("enabled") is False:
        ok("断言1 通过：后端快照明确 enabled=false（JWT 轮换已禁用）")
    else:
        failures.append(f"断言1 失败：快照 enabled={data.get('enabled')!r}（期望 false）")
        fail(failures[-1])

    kid = token_kid(token_old)
    if kid in (None, "k-0"):
        ok(f"断言2 通过：admin token kid={kid or '(无)'}，来自共享 JWT_SECRET")
    else:
        failures.append(f"断言2 失败：admin token kid={kid}（期望 k-0/无 kid）——"
                        "当前 Java 仍在使用轮换后的进程内 key")
        fail(failures[-1])

    status, rot = request("POST", "/api/admin/jwt-keys/rotate", token=token_old)
    if status in (409, 501):
        ok(f"断言3 通过：rotate 返回 {status}（禁用生效，未生成新 key）")
    else:
        failures.append(f"断言3 失败：rotate 返回 {status}（期望 409/501）；"
                        f"body={str(rot)[:200]}")
        fail(failures[-1])

    status_after, snap_after = request("GET", "/api/admin/jwt-keys", token=token_old)
    data_after = snap_after.get("data") or {}
    keys_after = data_after.get("keys") or []
    active_after = [k for k in keys_after if k.get("status") == "ACTIVE"]
    if (status_after == 200 and data_after.get("size") == size_before
            and keys_after == keys_before and len(active_after) == 1):
        ok(f"断言4 通过：密钥环未变化（size={size_before}，ACTIVE=1，无 k-1）")
    else:
        failures.append(f"断言4 失败：轮换前后密钥环不一致（before size={size_before} "
                        f"keys={[(k.get('kid'), k.get('status')) for k in keys_before]}；"
                        f"after status={status_after} size={data_after.get('size')} "
                        f"keys={[(k.get('kid'), k.get('status')) for k in keys_after]}）")
        fail(failures[-1])

    status_java, _ = request("GET", "/api/admin/jwt-keys", token=token_old)
    if status_java == 200:
        ok("断言5 通过：同一 token 在 Java 侧仍被接受（200）")
    else:
        failures.append(f"断言5 失败：同一 token 在 Java 侧返回 {status_java}（期望 200）")
        fail(failures[-1])

    if check_python_token(token_old):
        ok("断言6 通过：同一 token 在 Python 侧仍被接受（共享 JWT_SECRET）")
    else:
        failures.append("断言6 失败：Python 侧拒绝同一 token；2.1.5 禁用轮换的前提是 k-0 跨栈可用")
        fail(failures[-1])

    log("\n=== 验证结果（2.1.5 禁用模式） ===")
    if failures:
        log(f"失败 {len(failures)} 项")
        return 1
    ok("JWT 轮换禁用断言 6/6 全部通过")
    return 0


def verify_enabled(token_old: str, admin_user: str, admin_pass: str) -> int:
    """启用模式（2.1.6 共享 keyring 预留）：原三条轮换断言。"""
    failures: list[str] = []

    # 读取轮换前密钥环
    _, keys_before = request("GET", "/api/admin/jwt-keys", token=token_old)
    size_before = (keys_before.get("data") or {}).get("size")
    log(f"轮换前密钥数量：{size_before}")

    # 执行轮换。
    # 注意：429 在这里**不是 HTTP 限流**（/api/admin/** 不匹配 GlobalRateLimitFilter
    # 的任何模式），而是 KeyRingService.rotate() 的"轮换最小间隔"保护：
    #   ROTATION_MIN_INTERVAL_MS = 60_000（KeyRingService.java:47）
    #   rotate() 捕获 IllegalStateException → 映射为 429（JwtKeyRotationController）
    # 因此清 redis 限流 key 无效，正确做法是**等待间隔到期**后重试。
    log("触发 rotate ...")
    status, rot = 0, {}
    for attempt in range(1, 4):
        status, rot = request("POST", "/api/admin/jwt-keys/rotate", token=token_old)
        if status == 200:
            break
        if status == 429:
            wait = parse_rotation_wait(rot)
            log(f"  ⚠️  第 {attempt} 次 rotate 触发轮换间隔保护（429，非限流）："
                f"需等待 ~{wait:.0f}s（KeyRingService 最小间隔 60s）")
            if attempt < 3:
                time.sleep(wait)
                continue
        break
    if status == 429:
        # PHASE113: 原实现在此 `return 0`（静默视为通过）—— 三条核心断言从未执行，
        # 等于把"未验证"伪装成"通过"，违反"禁止静默成功"红线。改为明确判失败。
        failures.append(
            "rotate 连续 3 次触发轮换间隔保护（429，最小间隔 60s），无法执行轮换 —— "
            "双密钥窗口/新密钥生效/ACTIVE 唯一性三项断言均未执行，不能判定为通过。"
            "请在距上次轮换 ≥60s 后重跑本脚本")
        fail(failures[-1])
        return 1
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
    token_new = login(admin_user, admin_pass)
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

    log("\n=== 验证结果（启用模式） ===")
    if failures:
        log(f"失败 {len(failures)} 项")
        return 1
    ok("密钥轮换三项核心断言全部通过")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="JWT 密钥轮换验证")
    ap.add_argument("--admin-user", default="admin")
    ap.add_argument("--admin-pass", default="admin123")
    ap.add_argument("--admin-token", default=None, help="自带 admin token，跳过登录")
    args = ap.parse_args()

    log(f"目标后端：{BASE}")

    # 拿 admin token（也是"旧密钥"token）
    token_old = args.admin_token or login(args.admin_user, args.admin_pass)
    if not token_old:
        fail(f"无法以 {args.admin_user} 登录后端（HTTP 非 200 或无 token），无法验证轮换")
        return 1
    ok("取得 admin token")

    # 基线：token 在轮换判定前可用
    status, snap_before = request("GET", "/api/admin/jwt-keys", token=token_old)
    if status != 200:
        fail(f"基线失败：token 访问 /api/admin/jwt-keys 返回 {status}（期望 200）")
        return 1
    ok("基线：token 可访问管理端点 (200)")

    enabled = (snap_before.get("data") or {}).get("enabled")
    if enabled is False:
        return verify_disabled(token_old, snap_before)
    if enabled is None:
        log("  ⚠️  后端快照未暴露 enabled 字段（旧镜像），按启用模式验证")
    else:
        log("  ℹ️  后端快照 enabled=true，按启用模式验证")
    return verify_enabled(token_old, args.admin_user, args.admin_pass)


if __name__ == "__main__":
    sys.exit(main())
