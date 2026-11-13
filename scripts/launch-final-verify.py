#!/usr/bin/env python3
"""PHASE110: 上线前全量门禁编排脚本。

串行执行以下门禁（任一失败即终止并返回非 0）：
  1. 安全门禁 (security-gates.py)
  2. 启动检查 (startup-checklist.py)
  3. SaaS 就绪 (saas-readiness-verify.py)
  4. 备份可恢复 (backup-e2e-verify.py)
  5. 租户隔离 (tenant-isolation-e2e-verify.py)
  6. 配额控制 (quota-e2e-verify.py)
  7. 密钥轮换禁用断言 (key-rotation-verify.py；2.1.5 默认 JWT_ROTATION_ENABLED=false)
  8. 冒烟测试 (smoke.py)

用法:
  python3 scripts/launch-final-verify.py [--base-url http://localhost:8080]

退出码:
  0 = 全部通过
  1 = 某项失败
"""
from __future__ import annotations

import argparse
import os
import shutil as shutil_mod
import subprocess
import sys
import time
from typing import List, Tuple


def shutil_which(name: str):
    """shutil.which 的局部别名（避免与变量名冲突）。"""
    return shutil_mod.which(name)

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")


def log(msg: str) -> None:
    print(f"\n[{time.strftime('%H:%M:%S')}] {msg}")
    sys.stdout.flush()


def ok(msg: str) -> None:
    print(f"  ✅ {msg}")


def fail(msg: str) -> None:
    print(f"  ❌ {msg}")


def clear_rate_limit() -> None:
    """清理登录限流 key（best-effort）。

    编排串行跑 8 个子脚本，多数会登录 admin；而 ratelimit.login=5/300s 基于来源 IP
    累计 5 次即 429，会在跑到第 6+ 个脚本时把后续登录全部挡掉（实测 smoke 登录 429）。
    子脚本各自已做登录前清理，但编排串起来后累计次数仍会超限，故在此每个脚本前清一次。
    这是门禁编排的运维处理，不是放宽断言：每个脚本单独跑时同样会清理并保持自身断言强度。
    """
    redis_container = os.environ.get("REDIS_CONTAINER", "nocobase-redis")
    if os.environ.get("ACCEPTANCE_MODE") == "1" and redis_container == "nocobase-redis":
        # 2026-11-12 实测：ACCEPTANCE_MODE 下未显式指定 REDIS_CONTAINER 时，
        # 清理落到了默认栈 redis（nocobase-redis），隔离栈的限流 key 一个都没清，
        # 串行门禁后半段 smoke 登录 429。fail closed，不再静默清错目标。
        fail("ACCEPTANCE_MODE=1 时必须显式设置 REDIS_CONTAINER（如 nocobase-acc-redis），"
             "拒绝默认清理 nocobase-redis（默认栈）")
        raise SystemExit(2)
    try:
        scan = subprocess.run(
            ["docker", "exec", redis_container, "redis-cli", "--scan", "--pattern", "ratelimit:*"],
            capture_output=True, text=True, timeout=20)
        keys = [k for k in (scan.stdout or "").split() if k.strip()]
        if keys:
            subprocess.run(["docker", "exec", redis_container, "redis-cli", "DEL"] + keys,
                           capture_output=True, text=True, timeout=20)
    except Exception:
        pass  # 清理失败不影响门禁结论，交由脚本自身的登录结果判定


def run_script(name: str, args: List[str], timeout: int = 300) -> Tuple[bool, str]:
    """运行单个脚本，返回 (success, output)。"""
    log(f"▶️  {name}")
    # 每个脚本执行前清理限流，避免串行累计导致登录被 429 挡掉
    clear_rate_limit()
    try:
        result = subprocess.run(
            args,
            cwd=SCRIPTS_DIR,
            capture_output=True,
            text=True,
            timeout=timeout,
            env={**os.environ, "BASE_URL": os.environ.get("BASE_URL", BASE_URL)}
        )
        output = result.stdout + "\n" + result.stderr
        if result.returncode == 0:
            ok(f"{name} 通过")
            return True, output
        else:
            fail(f"{name} 失败 (exit={result.returncode})")
            return False, output
    except subprocess.TimeoutExpired:
        fail(f"{name} 超时 ({timeout}s)")
        return False, f"{name} 执行超时"
    except Exception as e:
        fail(f"{name} 异常：{e}")
        return False, str(e)


def check_backup_gateway() -> bool:
    """WP3：验证备份端点经前端网关可达（而不是被 nginx `/api/` 截到 Java）。

    根因（2026-11-12）：nginx 把所有 `/api/*` 转给 Java，Java 没有
    `/api/admin/backup` 路由，所以经网关恒 401/404；Python 直连才正常。
    修复是在 nginx 增加精确前缀 `location ^~ /api/admin/backup` → Python。

    - `FRONTEND_URL` 未设置：开发模式跳过（打印提示）；`ACCEPTANCE_MODE=1`
      时必须设置，否则 fail closed（发布证据不得缺这条）。
    - 正例：ADMIN JWT POST 返回 200 且 data.path 以 .tar.gz 结尾；
    - 负例：无 token 必须 401/403。
    """
    frontend_url = os.environ.get("FRONTEND_URL", "").rstrip("/")
    base_url = (os.environ.get("BASE_URL") or BASE_URL).rstrip("/")
    if not frontend_url:
        if os.environ.get("ACCEPTANCE_MODE") == "1":
            fail("ACCEPTANCE_MODE=1 但未设置 FRONTEND_URL，无法验证备份网关路由")
            return False
        print("  ℹ️  未设置 FRONTEND_URL，跳过备份网关检查（开发模式）")
        return True

    import base64
    import json
    import urllib.error
    import urllib.request

    # 与子脚本一致：串行门禁会累计登录次数，先清隔离栈限流，避免把 429 误判成鉴权失败。
    clear_rate_limit()

    username = os.environ.get("USERNAME", "admin")
    password = os.environ.get("PASSWORD", "admin123")
    login = urllib.request.Request(
        f"{base_url}/api/auth/login",
        data=json.dumps({"username": username, "password": password}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(login, timeout=10) as resp:
            token = json.loads(resp.read())["data"]["access_token"]
    except Exception as e:  # noqa: BLE001
        fail(f"备份网关检查：登录 {base_url} 失败：{e}")
        return False

    # 2.1.5 默认禁用 JWT 轮换，正常 token 应为 k-0。若仍出现 kid=k-1，说明
    # 运行的是旧镜像/旧配置（或用 JWT_ROTATION_ENABLED=true 启动），而 Python 只持有
    # 共享 JWT_SECRET（k-0），无法验签。此时继续 POST 只会得到误导性的 401，
    # 因此 fail closed 并给出可操作结论：回到默认配置并重启 backend-java。
    try:
        header_b64 = token.split(".")[0]
        header = json.loads(base64.urlsafe_b64decode(header_b64 + "=" * (-len(header_b64) % 4)))
        kid = header.get("kid")
    except Exception:  # noqa: BLE001
        kid = None
    if kid not in (None, "k-0"):
        fail(f"备份网关检查：当前 admin token kid={kid}，来自 JWT 轮换后的进程内随机 key；"
             "2.1.5 默认 JWT_ROTATION_ENABLED=false，正常应为 k-0。"
             "Python 只持有共享 JWT_SECRET（k-0），无法验签。"
             "请确认未启用轮换并重启 backend-java 恢复 k-0 后再跑发布门禁")
        return False

    def post_backup(auth_token: str) -> int:
        req = urllib.request.Request(
            f"{frontend_url}/api/admin/backup", data=b"", method="POST",
            headers={"Authorization": f"Bearer {auth_token}"} if auth_token else {},
        )
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                return resp.status
        except urllib.error.HTTPError as e:
            return e.code
        except Exception as e:  # noqa: BLE001
            fail(f"备份网关检查：POST {frontend_url}/api/admin/backup 失败：{e}")
            return 0

    status = post_backup(token)
    if status != 200:
        fail(f"备份网关检查：ADMIN POST {frontend_url}/api/admin/backup → {status}"
             "（期望 200；若 401/404 说明仍被 nginx /api/ 截到 Java）")
        return False
    anon_status = post_backup("")
    if anon_status not in (401, 403):
        fail(f"备份网关检查：无 JWT POST 返回 {anon_status}（期望 401/403）")
        return False
    ok(f"备份网关可达：{frontend_url}/api/admin/backup → 200；无 JWT → {anon_status}")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="上线前全量门禁编排")
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"), help="后端地址")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    os.environ["BASE_URL"] = base_url

    log(f"=== 上线前全量门禁编排 ===")
    log(f"目标后端：{base_url}")
    log(f"DB 目标：container={os.environ.get('PG_CONTAINER', '(auto)')} "
        f"strict={os.environ.get('PG_STRICT', '0')}")

    # WP3：备份网关路由是发布前置条件，不计入下面 8 项业务门禁。
    if not check_backup_gateway():
        log("\n❌ 备份网关检查失败，终止上线终验")
        return 1

    # 依赖漏洞扫描：优先官方 security-gates.py（需 trivy 二进制，CI 环境可用）；
    # 本地无 trivy 时回落离线替代 java-dependency-audit.py（PHASE104 方案，本地可跑且非恒真）。
    # 直接调 security-gates.py 而不带子命令会 exit 2（argparse 要求必填 cmd），
    # 曾导致本编排首项必然失败（PHASE110 审计发现并修正）。
    has_trivy = shutil_which("trivy") is not None
    if has_trivy:
        scan_step = ("依赖漏洞扫描（官方 trivy）", ["python3", "security-gates.py", "scan-deps"])
    else:
        scan_step = ("依赖漏洞扫描（离线替代）", ["python3", "java-dependency-audit.py"])

    tests = [
        scan_step,
        ("启动检查", ["python3", "startup-checklist.py"]),
        ("SaaS 就绪", ["python3", "saas-readiness-verify.py"]),
        ("备份可恢复", ["python3", "backup-e2e-verify.py"]),
        ("租户隔离", ["python3", "tenant-isolation-e2e-verify.py"]),
        ("配额控制", ["python3", "quota-e2e-verify.py"]),
        # key-rotation-verify.py 通过 BASE_URL/PYTHON_URL 环境变量取后端地址
        # （run_script 已注入），不接受 --base-url。2.1.5 默认禁用轮换，脚本断言
        # rotate 返回 409/501 且同一 k-0 token 在 Java/Python 均可用，不再执行轮换，
        # 因此门禁结束后无需重启 backend-java。
        ("密钥轮换（禁用断言）", ["python3", "key-rotation-verify.py"]),
        ("冒烟测试", ["python3", "smoke.py"]),
    ]

    total = len(tests)
    passed = 0
    failed_tests = []

    for name, args in tests:
        success, output = run_script(name, args)
        if success:
            passed += 1
        else:
            failed_tests.append((name, output))
            # 任一失败即终止（串行门禁原则）
            log(f"\n⛔ 门禁中止于 {name}")
            break

    # 汇总
    log("\n=== 最终结果 ===")
    log(f"通过率：{passed}/{total}")

    if failed_tests:
        for name, output in failed_tests:
            log(f"\n{name} 输出:")
            for line in output.splitlines()[:20]:  # 只显示前 20 行
                print(f"  {line}")
            if len(output.splitlines()) > 20:
                print(f"  ... (还有 {len(output.splitlines()) - 20} 行)")

        log("\n❌ 上线门禁未通过，请修复后重试")
        return 1
    else:
        ok("所有门禁通过，可以上线")
        log("\n🎉 上线准备完成!")
        log("\n建议后续操作:")
        log("  1. 记录本次验证时间戳: date > .last-verification")
        log("  2. 执行 ./deploy.sh prod")
        log("  3. 观察告警: docker compose logs backend-python 2>&1 | grep FAILED")
        return 0


if __name__ == "__main__":
    sys.exit(main())
