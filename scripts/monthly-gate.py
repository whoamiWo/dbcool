#!/usr/bin/env python3
"""
Wave4 月度门禁 —— 一条命令跑完全量门禁，输出汇总与失败项。

用法：
    python3 scripts/monthly-gate.py                # 全量（默认，开发模式：允许 SKIP）
    python3 scripts/monthly-gate.py --release      # 发布模式：先跑验收目标预检，任何 SKIP 都是 FAIL
    python3 scripts/monthly-gate.py --quick        # 快档：版本/契约/依赖/冒烟（不含 mvn/vitest/E2E）
    python3 scripts/monthly-gate.py --list         # 只列出将执行的门禁项

退出码：0 = 全部通过；1 = 有门禁 FAIL（release 模式含 SKIP）；2 = 执行错误（依赖缺失 / 环境异常）

SKIP 语义（禁止假绿）：
- 只有显式白名单门禁允许 SKIP；脚本用机器标记 ``GATE_SKIP: <count>`` 表达，
  同时兼容旧输出 ``SKIP:`` / ``⏭️ SKIP``（迁移期，绝不能判 PASS）。
- SKIP 项单独计数、不计 PASS；开发模式跳过项**不写入** ``target/gate-numbers.json``，
  并删除同名门禁的旧键，避免历史 PASS 数字残留。
- ``--release`` 下任一 SKIP 一律 FAIL。
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
    ("依赖漏洞审计", "python3 scripts/java-dependency-audit.py --backend offline", True),
    ("后端 mvn -o verify", "cd backend-java && mvn -o verify", False),
    ("前端 npm run test:run", "cd frontend && npm run test:run", False),
    ("前端 tsc --noEmit", "cd frontend && npx tsc --noEmit", False),
    ("前端 eslint src", "cd frontend && npx eslint src", False),
    ("Python pytest", "cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q", False),
    ("冒烟 smoke.py", "python3 scripts/smoke.py", True),
    ("租户隔离", "python3 scripts/tenant-isolation-e2e-verify.py", False),
    ("归属写入静态门禁", "python3 scripts/actor-writer-verify.py", False),
    ("企业套件真实 PG (pg IT)", "python3 scripts/pg-it-gate.py", False),
    ("企业套件 schema smoke", "python3 scripts/verify-enterprise-suite.py", False),
    ("备份/PITR 演练", "python3 scripts/backup-e2e-verify.py", False),
    ("上线终验", "python3 scripts/launch-final-verify.py", False),
]

# 显式允许 SKIP 的门禁白名单（仅限环境不具备，例如无 Docker / 无备份环境）。
# 其它门禁出现 "SKIP" 字样不改变判定 —— 一律按退出码判 PASS/FAIL。
SKIP_CAPABLE = {"备份/PITR 演练", "企业套件真实 PG (pg IT)"}

# 门禁 → gate-numbers.json 键前缀：门禁本次执行但 SKIP/FAIL 时删除这些旧键，
# 禁止历史 PASS 数字继续被文档引用（TY4 G1/G6）。
GATE_KEY_PREFIXES = {
    "后端 mvn -o verify": ("java.",),
    "前端 npm run test:run": ("fe.",),
    "契约 0 漂移": ("contract.",),
    "Python pytest": ("python.",),
    "冒烟 smoke.py": ("smoke.",),
    "租户隔离": ("tenant.",),
    "备份/PITR 演练": ("backup.",),
    "上线终验": ("launch.",),
}



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

# 机器可读 SKIP 标记：GATE_SKIP: <count>
_SKIP_MACHINE_RE = _re.compile(r"^GATE_SKIP:\s*(\d+)\s*$", _re.MULTILINE)
# 迁移期兼容旧输出：独立的 "SKIP:" 或 "⏭️ SKIP" 行
_SKIP_LEGACY_RE = _re.compile(r"^(?:(?!GATE_SKIP:).)*(?:⏭️\s*SKIP|SKIP:)", _re.MULTILINE)


def skip_marker_count(out: str) -> int:
    """从门禁输出中读取 SKIP 数量；无机器标记时回退旧格式（数量未知按 1 计）。"""
    hits = _SKIP_MACHINE_RE.findall(out or "")
    if hits:
        return sum(int(h) for h in hits)
    return 1 if _SKIP_LEGACY_RE.search(out or "") else 0


def detect_skip(name: str, rc: int, out: str) -> bool:
    """判定该门禁是否为“环境不具备而 SKIP”。

    只对白名单门禁生效；失败（rc != 0）永远不是 SKIP。分类依据是显式标记，
    不做“输出里随便出现 SKIP 就算”的模糊匹配。
    """
    if rc != 0 or name not in SKIP_CAPABLE:
        return False
    return skip_marker_count(out) > 0


def collect_numbers(outputs: dict, skipped_names=()) -> dict:
    """从各门禁输出文本中抽取关键实测数字。

    ``outputs`` 只应包含本次**成功且未被 SKIP** 的门禁输出 —— SKIP/FAIL 的门禁
    不得产出通过数字（否则备份 SKIP 会被记成 30/30 PASS）。
    """
    nums = {}
    skipped_names = set(skipped_names)

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
    # 备份只有真实执行（非 SKIP）时才允许写通过数；SKIP 的 "总计/PASS" 不是通过证据。
    for key, gate in (("smoke", "冒烟 smoke.py"), ("backup", "备份/PITR 演练")):
        if gate in skipped_names:
            continue
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


def write_numbers(nums: dict, drop_prefixes=(), path: Path | None = None) -> None:
    """写入 target/gate-numbers.json。

    - 只合并本次**实际成功执行**的门禁键；未执行的门禁（如 Playwright --list）保留旧值。
    - ``drop_prefixes`` 里的前缀对应“本次执行了但 SKIP/FAIL”的门禁，其旧键必须删除，
      禁止历史 PASS 数字残留并被文档校验/--fix 回填（TY4 G1）。
    """
    out = path or (ROOT / "target" / "gate-numbers.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    merged = dict(nums)
    if out.exists():
        try:
            previous = json.loads(out.read_text(encoding="utf-8"))
            if isinstance(previous, dict):
                merged = {**previous, **nums}
        except Exception as e:  # noqa: BLE001
            print(f"⚠️ 读取既有 gate-numbers.json 失败（将重建）: {e}")
    for prefix in drop_prefixes:
        for key in [k for k in merged if k.startswith(prefix)]:
            del merged[key]
    out.write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try:
        shown = out.relative_to(ROOT)
    except ValueError:
        shown = out
    print(f"\n📊 实测数字已写入 {shown}")



def main() -> int:
    ap = argparse.ArgumentParser(description="Wave4 月度门禁")
    ap.add_argument("--quick", action="store_true", help="快档（只跑轻量门禁）")
    ap.add_argument("--list", action="store_true", help="只列出门禁项")
    ap.add_argument("--release", action="store_true",
                    help="发布模式：先跑验收目标预检；任何 SKIP 一律判 FAIL")
    ap.add_argument("--timeout", type=int, default=1800, help="单门禁超时秒数（默认 1800）")
    args = ap.parse_args()

    gates = [(n, c) for (n, c, q) in GATES if (not args.quick or q)]
    if args.list:
        print(f"将执行 {len(gates)} 项门禁：")
        for n, c in gates:
            print(f"  - {n}: {c}")
        return 0

    doc_suffix = " + 文档数字校验" if not args.quick else ""
    print("=" * 72)
    print(f"Wave4 月度门禁（共 {len(gates)} 项主门禁{doc_suffix}，工作目录 {ROOT}"
          f"{'，发布模式 --release' if args.release else ''}）")
    print("=" * 72)

    if args.release:
        # 发布门禁必须先固定验收目标（WP2）：BASE_URL/PG_CONTAINER/PG_STRICT 等，
        # 防脚本打到默认栈/默认库；预检不过直接终止。
        print("\n▶ 验收目标预检（acceptance-preflight）...", flush=True)
        rc, out, cost = run("python3 scripts/acceptance-preflight.py", timeout=120)
        print(out.rstrip() if out.strip() else "  (无输出)")
        if rc != 0:
            print(f"\n❌ 验收目标预检未通过（exit={rc}），release 门禁终止。"
                  f"请先按 scripts/acceptance-preflight.py 的提示固定隔离栈目标。")
            return 2

    results = []
    for name, cmd in gates:
        print(f"\n▶ {name} ...", flush=True)
        rc, out, cost = run(cmd, timeout=args.timeout)
        # SKIP 判定只认显式标记（GATE_SKIP: / 迁移期兼容旧格式），且仅限白名单门禁；
        # release 模式下 SKIP 即 FAIL。
        skipped = detect_skip(name, rc, out)
        if skipped and args.release:
            status = "❌ FAIL（release 模式禁止 SKIP）"
            results.append((name, 1, cost, out, False, True))
            print(f"  {status} ({cost:.1f}s, exit={rc})")
            continue
        if skipped:
            status = "⏭️  SKIP"
        else:
            status = "✅ PASS" if rc == 0 else "❌ FAIL"
        print(f"  {status} ({cost:.1f}s, exit={rc})")
        if rc != 0:
            print("  ---- 输出尾部 ----")
            print("\n".join(("  " + line) for line in out.strip().splitlines()[-8:]))
        results.append((name, rc, cost, out, skipped, False))

    # 只采集本次真实成功且未 SKIP 的门禁数字；SKIP/FAIL 门禁的旧键必须删除。
    skipped_names = {name for name, rc, cost, out, skipped, _rel in results if skipped}
    failed_names = {name for name, rc, cost, out, skipped, _rel in results if rc != 0 and not skipped}
    nums = collect_numbers(
        {name: out for name, rc, cost, out, skipped, _rel in results
         if rc == 0 and not skipped},
        skipped_names=skipped_names,
    )
    drop_prefixes = tuple(
        prefix for name in (skipped_names | failed_names)
        for prefix in GATE_KEY_PREFIXES.get(name, ())
    )
    if nums or drop_prefixes:
        write_numbers(nums, drop_prefixes=drop_prefixes)

    # Wave4 待办池 #16：文档门禁数字校验（依赖刚写入的 gate-numbers.json）
    # 文档声明数字与实测不一致即红，杜绝人工同步漂移。
    if not args.quick and (nums or drop_prefixes):
        print("\n▶ 文档数字校验 ...", flush=True)
        # TY4 D3：release 模式只校验、不自动改文档；否则「边改文档边通过」等于假绿。
        doc_cmd = "python3 scripts/doc-numbers-verify.py"
        if not args.release:
            doc_cmd += " --fix"
        print(f"  模式: {'只校验（release）' if args.release else '校验并回填（开发）'}", flush=True)
        rc, out, cost = run(doc_cmd, timeout=120)
        print(f"  {'✅ PASS' if rc == 0 else '❌ FAIL'} ({cost:.1f}s, exit={rc})")
        if rc != 0:
            print("  ---- 输出尾部 ----")
            print("\n".join(("  " + line) for line in out.strip().splitlines()[-8:]))
        results.append(("文档数字校验", rc, cost, out, False, False))

    print("\n" + "=" * 72)
    print("汇总")
    print("=" * 72)
    for name, rc, cost, _out, skipped, release_skip in results:
        if skipped:
            print(f"  ⏭️  {name}: SKIP ({cost:.1f}s)")
        else:
            print(f"  {'✅' if rc == 0 else '❌'} {name}: exit={rc} ({cost:.1f}s)")
    failed = [n for n, rc, _, _o, skipped, release_skip in results
              if (rc != 0 and not skipped) or release_skip]
    if failed:
        print(f"\n❌ {len(failed)} 项未通过：{', '.join(failed)}")
        return 1
    passed = len(results) - len(skipped_names)
    if skipped_names:
        print(f"\n✅ {passed} 项通过；⏭️  {len(skipped_names)} 项 SKIP（不计 PASS）："
              f"{', '.join(skipped_names)}")
    else:
        print(f"\n✅ 全部 {len(results)} 项门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
