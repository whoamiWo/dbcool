#!/usr/bin/env python3
"""
Wave4 月度门禁 —— 一条命令跑完全量门禁，输出汇总与失败项。

用法：
    python3 scripts/monthly-gate.py                # 全量（默认）
    python3 scripts/monthly-gate.py --quick        # 快档：版本/契约/依赖/冒烟（不含 mvn/vitest/E2E）
    python3 scripts/monthly-gate.py --list         # 只列出将执行的门禁项

退出码：0 = 全部通过；1 = 有门禁 FAIL；2 = 执行错误（依赖缺失 / 环境异常）
"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 每项：(名称, 相对命令, 是否属于 --quick)
GATES = [
    ("版本一致性", "python3 scripts/version-consistency-verify.py", True),
    ("契约 0 漂移", "python3 scripts/api-contract-verify.py", True),
    ("依赖漏洞审计", "python3 scripts/java-dependency-audit.py", True),
    ("后端 mvn -o verify", "cd backend-java && mvn -o verify", False),
    ("前端 npm run test:run", "cd frontend && npm run test:run", False),
    ("前端 tsc --noEmit", "cd frontend && npx tsc --noEmit", False),
    ("前端 eslint src", "cd frontend && npx eslint src", False),
    ("Python pytest", "cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q", False),
    ("冒烟 smoke.py", "python3 scripts/smoke.py", True),
    ("租户隔离", "python3 scripts/tenant-isolation-e2e-verify.py", False),
    ("备份/PITR 演练", "python3 scripts/backup-e2e-verify.py", False),
    ("上线终验", "python3 scripts/launch-final-verify.py", False),
]


def run(cmd: str, timeout: int = 1800) -> tuple[int, str, float]:
    start = time.time()
    try:
        proc = subprocess.run(
            ["bash", "-lc", cmd], cwd=ROOT, capture_output=True,
            text=True, timeout=timeout, env=None,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        return proc.returncode, out, time.time() - start
    except subprocess.TimeoutExpired as e:
        return 124, f"TIMEOUT after {timeout}s: {e}", time.time() - start
    except Exception as exc:  # noqa: BLE001
        return 2, f"EXEC ERROR: {exc}", time.time() - start


def main() -> int:
    ap = argparse.ArgumentParser(description="Wave4 月度门禁")
    ap.add_argument("--quick", action="store_true", help="快档（只跑轻量门禁）")
    ap.add_argument("--list", action="store_true", help="只列出门禁项")
    ap.add_argument("--timeout", type=int, default=1800, help="单门禁超时秒数（默认 1800）")
    args = ap.parse_args()

    gates = [(n, c) for (n, c, q) in GATES if (not args.quick or q)]
    if args.list:
        print(f"将执行 {len(gates)} 项门禁：")
        for n, c in gates:
            print(f"  - {n}: {c}")
        return 0

    print("=" * 72)
    print(f"Wave4 月度门禁（共 {len(gates)} 项，工作目录 {ROOT}）")
    print("=" * 72)
    results = []
    for name, cmd in gates:
        print(f"\n▶ {name} ...", flush=True)
        rc, out, cost = run(cmd, timeout=args.timeout)
        status = "✅ PASS" if rc == 0 else "❌ FAIL"
        print(f"  {status} ({cost:.1f}s, exit={rc})")
        if rc != 0:
            print("  ---- 输出尾部 ----")
            print("\n".join(("  " + line) for line in out.strip().splitlines()[-8:]))
        results.append((name, rc, cost, out))

    print("\n" + "=" * 72)
    print("汇总")
    print("=" * 72)
    for name, rc, cost, _ in results:
        print(f"  {'✅' if rc == 0 else '❌'} {name}: exit={rc} ({cost:.1f}s)")
    failed = [n for n, rc, _, _ in results if rc != 0]
    if failed:
        print(f"\n❌ {len(failed)} 项未通过：{', '.join(failed)}")
        return 1
    print(f"\n✅ 全部 {len(results)} 项门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
