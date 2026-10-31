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


# ============================================================
# Wave4 待办池 #16：门禁实测数字采集 → target/gate-numbers.json
# 供 scripts/doc-numbers-verify.py 校验/回填文档中的门禁数字，
# 消除"文档数字靠人工同步"导致的漂移。
# ============================================================
import json
import re as _re


def collect_numbers(outputs: dict) -> dict:
    """从各门禁输出文本中抽取关键实测数字。"""
    nums = {}

    java_out = outputs.get("后端 mvn -o verify", "")
    m = _re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+)", java_out)
    if m:
        nums["java.tests"] = int(m.group(1))
        nums["java.failures"] = int(m.group(2))
        nums["java.errors"] = int(m.group(3))

    # JaCoCo 根计数器 LINE 覆盖率
    jacoco = ROOT / "backend-java" / "target" / "site" / "jacoco" / "jacoco.xml"
    if jacoco.exists():
        try:
            import xml.etree.ElementTree as ET
            root = ET.parse(jacoco).getroot()
            for c in root.findall("counter"):
                if c.get("type") == "LINE":
                    miss = int(c.get("missed")); cov = int(c.get("covered"))
                    if miss + cov > 0:
                        nums["java.coverage_line_pct"] = round(cov * 100.0 / (miss + cov), 2)
        except Exception as e:  # noqa: BLE001
            print(f"⚠️ 采集 JaCoCo 覆盖率失败: {e}")

    fe_out = outputs.get("前端 npm run test:run", "")
    m = _re.search(r"Test Files\s+(\d+) passed", fe_out)
    if m:
        nums["fe.files"] = int(m.group(1))
    m = _re.search(r"Tests\s+(?:(\d+) failed \| )?(?:(\d+) skipped \| )?(\d+) passed(?: \((\d+)\))?", fe_out)
    if m:
        if m.group(1):
            nums["fe.failed"] = int(m.group(1))
        nums["fe.passed"] = int(m.group(3))
        if m.group(4):
            nums["fe.tests_total"] = int(m.group(4))
    m = _re.search(r"Errors\s+(\d+)", fe_out)
    if m:
        nums["fe.errors"] = int(m.group(1))

    contract_out = outputs.get("契约 0 漂移", "")
    m = _re.search(r"(\d+) 后端路径 × (\d+) 前端路径", contract_out)
    if m:
        nums["contract.backend_paths"] = int(m.group(1))
        nums["contract.frontend_paths"] = int(m.group(2))

    py_out = outputs.get("Python pytest", "")
    m = _re.search(r"(\d+) passed", py_out)
    if m:
        nums["python.passed"] = int(m.group(1))
    m = _re.search(r"(\d+) skipped", py_out)
    if m:
        nums["python.skipped"] = int(m.group(1))

    # 冒烟 / 备份：'总计: 13 | PASS: 13 | FAIL: 0'
    for key, gate in (("smoke", "冒烟 smoke.py"), ("backup", "备份/PITR 演练")):
        m = _re.search(r"总计: (\d+) \| PASS: (\d+)", outputs.get(gate, ""))
        if m:
            nums[f"{key}.total"] = int(m.group(1))
            nums[f"{key}.passed"] = int(m.group(2))
    # 租户隔离：'Results: 16/16 PASS'
    m = _re.search(r"Results: (\d+)/(\d+) PASS", outputs.get("租户隔离", ""))
    if m:
        nums["tenant.passed"] = int(m.group(1))
        nums["tenant.total"] = int(m.group(2))
    # 上线终验：'通过率：8/8'
    m = _re.search(r"通过率：(\d+)/(\d+)", outputs.get("上线终验", ""))
    if m:
        nums["launch.passed"] = int(m.group(1))
        nums["launch.total"] = int(m.group(2))

    return nums


def write_numbers(nums: dict) -> None:
    out = ROOT / "target" / "gate-numbers.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(nums, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n📊 实测数字已写入 {out.relative_to(ROOT)}")


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

    nums = collect_numbers({name: out for name, rc, cost, out in results})
    if nums:
        write_numbers(nums)

    # Wave4 待办池 #16：文档门禁数字校验（依赖刚写入的 gate-numbers.json）
    # 文档声明数字与实测不一致即红，杜绝人工同步漂移。
    if not args.quick and nums:
        print("\n▶ 文档数字校验 ...", flush=True)
        rc, out, cost = run("python3 scripts/doc-numbers-verify.py --fix", timeout=120)
        print(f"  {'✅ PASS' if rc == 0 else '❌ FAIL'} ({cost:.1f}s, exit={rc})")
        if rc != 0:
            print("  ---- 输出尾部 ----")
            print("\n".join(("  " + line) for line in out.strip().splitlines()[-8:]))
        results.append(("文档数字校验", rc, cost, out))

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
