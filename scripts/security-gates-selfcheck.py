#!/usr/bin/env python3
"""PHASE99 可失败性验证：证明 security-gates.py 真的会因 HIGH 漏洞而 FAIL。

红线 1（禁止恒真断言）：任何门禁都必须能被证伪。本脚本用**合成报告**
绕过对真实 trivy/docker 的依赖，直接喂给解析逻辑，断言：

  用例 A: 报告含 1 条 HIGH       → 期望 exit 1（阻断）
  用例 B: 报告含 1 条 CRITICAL   → 期望 exit 1（阻断）
  用例 C: 报告仅含 MEDIUM/LOW    → 期望 exit 0（放行）
  用例 D: 报告为空 Results       → 期望 exit 0（放行）
  用例 E: 报告文件损坏（非法 JSON）→ 期望 exit 2（执行错误，不得静默 PASS）

同时验证：
  用例 F: 二进制缺失时 scan-deps 返回 2（而非 0）

用法：
    python3 scripts/security-gates-selfcheck.py

退出码：0 = 全部用例符合预期；1 = 有用例不符（门禁失效）
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
GATE = BASE / "scripts" / "security-gates.py"


def _mk_trivy_report(vulns: list[dict]) -> dict:
    return {"SchemaVersion": 2, "Results": [{"Target": "x", "Vulnerabilities": vulns}]}


def _vuln(sev: str, vid: str = "CVE-TEST-0001") -> dict:
    return {"VulnerabilityID": vid, "PkgName": "test:pkg", "Severity": sev,
            "Title": "synthetic"}


def run_case(name: str, report: dict | str, expect_rc: int) -> bool:
    """把合成报告写到临时目录，直接调用解析函数（不走真实 trivy）。"""
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        # 注入一个假的 trivy 二进制：忽略参数，直接把预设报告拷到 --output 路径
        fake_bin = tmp / "bin"
        fake_bin.mkdir()
        src_report = tmp / "src.json"
        if isinstance(report, str):
            src_report.write_text(report)
        else:
            src_report.write_text(json.dumps(report))

        fake_trivy = fake_bin / "trivy"
        fake_trivy.write_text(
            "#!/usr/bin/env python3\n"
            "import sys, shutil, pathlib\n"
            "args = sys.argv[1:]\n"
            "out = None\n"
            "for i, a in enumerate(args):\n"
            "    if a == '--output':\n"
            "        out = args[i+1]\n"
            "if out:\n"
            "    pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True)\n"
            f"    shutil.copy({str(src_report)!r}, out)\n"
            "sys.exit(0)\n"
        )
        fake_trivy.chmod(0o755)

        env = dict(os.environ)
        env["PATH"] = f"{fake_bin}{os.pathsep}{env['PATH']}"
        # 让脚本把报告写到临时 target
        proc = subprocess.run(
            [sys.executable, str(GATE), "scan-deps", "--fail-on", "HIGH,CRITICAL"],
            capture_output=True, text=True, env=env, cwd=str(BASE),
        )
        ok = proc.returncode == expect_rc
        mark = "✅" if ok else "❌"
        print(f"{mark} {name}: rc={proc.returncode} (期望 {expect_rc})")
        if not ok:
            print("   stdout:", proc.stdout[-800:])
            print("   stderr:", proc.stderr[-800:])
        return ok


def main() -> int:
    print("=" * 60)
    print("PHASE99 安全门禁可失败性验证")
    print("=" * 60)
    results = []

    # 注意：scan-deps 会遍历 DEP_TARGETS，需保证至少一个目标存在（pom.xml）
    results.append(run_case(
        "A. 含 1 条 HIGH → 阻断", _mk_trivy_report([_vuln("HIGH")]), 1))
    results.append(run_case(
        "B. 含 1 条 CRITICAL → 阻断", _mk_trivy_report([_vuln("CRITICAL")]), 1))
    results.append(run_case(
        "C. 仅 MEDIUM/LOW → 放行",
        _mk_trivy_report([_vuln("MEDIUM"), _vuln("LOW")]), 0))
    results.append(run_case(
        "D. 空 Results → 放行", _mk_trivy_report([]), 0))
    results.append(run_case(
        "E. 报告损坏 → 执行错误(2)", "{ this is not json", 2))

    # F: 二进制缺失
    env = dict(os.environ)
    env["PATH"] = "/nonexistent"
    proc = subprocess.run(
        [sys.executable, str(GATE), "scan-deps"],
        capture_output=True, text=True, env=env, cwd=str(BASE),
    )
    ok = proc.returncode == 2
    print(f"{'✅' if ok else '❌'} F. trivy 缺失 → 执行错误(2): rc={proc.returncode}")
    results.append(ok)

    print("=" * 60)
    passed = sum(results)
    print(f"结果: {passed}/{len(results)} 通过")
    if passed != len(results):
        print("❌ 门禁存在失效用例 —— 违反红线 1")
        return 1
    print("✅ 全部门禁用例符合预期（可失败性成立）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
