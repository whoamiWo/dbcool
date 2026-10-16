#!/usr/bin/env python3
"""PHASE110: 上线前全量门禁编排脚本。

串行执行以下门禁（任一失败即终止并返回非 0）：
  1. 安全门禁 (security-gates.py)
  2. 启动检查 (startup-checklist.py)
  3. SaaS 就绪 (saas-readiness-verify.py)
  4. 备份可恢复 (backup-e2e-verify.py)
  5. 租户隔离 (tenant-isolation-e2e-verify.py)
  6. 配额控制 (quota-e2e-verify.py)
  7. 密钥轮换 (key-rotation-verify.py)
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
    try:
        scan = subprocess.run(
            ["docker", "exec", "nocobase-redis", "redis-cli", "--scan", "--pattern", "ratelimit:*"],
            capture_output=True, text=True, timeout=20)
        keys = [k for k in (scan.stdout or "").split() if k.strip()]
        if keys:
            subprocess.run(["docker", "exec", "nocobase-redis", "redis-cli", "DEL"] + keys,
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
            env={**os.environ, "BASE_URL": BASE_URL}
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


def main() -> int:
    parser = argparse.ArgumentParser(description="上线前全量门禁编排")
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"), help="后端地址")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    os.environ["BASE_URL"] = base_url

    log(f"=== 上线前全量门禁编排 ===")
    log(f"目标后端：{BASE_URL}")

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
        # key-rotation-verify.py 通过 BASE_URL 环境变量取后端地址（run_script 已注入），
        # 不再接受 --base-url；传该参数会 argparse 报错 unrecognized arguments（PHASE110 审计修正）。
        ("密钥轮换", ["python3", "key-rotation-verify.py"]),
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
