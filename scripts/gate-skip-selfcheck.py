#!/usr/bin/env python3
"""门禁真实性自测（TY4 WP1）。

不依赖真实 mvn/vitest/docker，用模拟的门禁输出验证：
1. 备份输出 `GATE_SKIP: 30`、rc=0 → 开发模式汇总 SKIP，不计 PASS，不写 `backup.passed`；
   release 模式返回非 0。
2. 旧格式 `⏭️  SKIP` 得到同一结果（迁移期兼容，绝不判 PASS）。
3. 旧 `gate-numbers.json` 含 backup 30/30、本次 backup SKIP → 旧值被删除。
4. 删除 required key 后文档校验失败；锚点已声明数字但实测键缺失 → 失败（D1）；
   mixed 锚点缺 optional 键不中断 required 比较（D2）；可选锚点整体不存在才跳过。
5. 正常 backup PASS → 才写真实通过数。

用法：python3 scripts/gate-skip-selfcheck.py
退出码：0 = 全部通过；1 = 自测失败。
"""
import importlib.util
import io
import json
import sys
import tempfile
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
failures = []


def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)  # type: ignore[union-attr]
    return mod


def check(name: str, ok: bool, detail: str = "") -> None:
    print(f"  {'✓ PASS' if ok else '✗ FAIL'} {name}" + (f" — {detail}" if detail else ""))
    if not ok:
        failures.append(name)


def fake_gate_run(backup_output: str, backup_rc: int = 0):
    """替换 monthly-gate.run：只对备份门禁返回模拟输出，其它门禁一律成功。"""
    def _run(cmd: str, timeout: int = 1800):
        if "acceptance-preflight" in cmd:
            return 0, "验收目标预检通过（模拟）", 0.0
        if "backup-e2e-verify" in cmd:
            return backup_rc, backup_output, 0.0
        return 0, (
            "Tests run: 1, Failures: 0, Errors: 0\n"
            "Test Files 1 passed\nTests 1 passed\nResults: 1/1 PASS\n"
            "总计: 1 | PASS: 1 | FAIL: 0\n通过率：1/1\n"
        ), 0.0

    return _run


def run_monthly_gate(mod, tmp: Path, argv, backup_output: str, backup_rc: int = 0):
    """在隔离 ROOT 下跑 monthly-gate.main，返回 (rc, 输出, gate-numbers)。"""
    old_root, old_argv = mod.ROOT, sys.argv
    mod.ROOT = tmp
    mod.run = fake_gate_run(backup_output, backup_rc)
    sys.argv = ["monthly-gate.py", *argv]
    buf = io.StringIO()
    try:
        with redirect_stdout(buf):
            rc = mod.main()
    finally:
        mod.ROOT, sys.argv = old_root, old_argv
    numbers_file = tmp / "target" / "gate-numbers.json"
    numbers = json.loads(numbers_file.read_text(encoding="utf-8")) if numbers_file.exists() else {}
    return rc, buf.getvalue(), numbers


def main() -> int:
    monthly = load_module("monthly_gate", "monthly-gate.py")

    print("== 1/2. 机器标记与旧格式 SKIP 识别 ==")
    check("GATE_SKIP: 30 → 识别为 SKIP",
          monthly.detect_skip("备份/PITR 演练", 0, "总计: 30 | PASS: 30\nGATE_SKIP: 30"))
    check("旧格式 ⏭️  SKIP → 同样识别为 SKIP",
          monthly.detect_skip("备份/PITR 演练", 0, "⏭️  SKIP 备份文件存在 — not found"))
    check("旧格式 SKIP: → 同样识别为 SKIP",
          monthly.detect_skip("企业套件真实 PG (pg IT)", 0, "SKIP: Docker 不可用"))
    check("非白名单门禁出现 SKIP 字样也不判 SKIP",
          not monthly.detect_skip("冒烟 smoke.py", 0, "⏭️  [3] 附件: SKIP — MinIO 未启用"))
    check("SKIP 输出但 rc!=0 → 不是 SKIP（是 FAIL）",
          not monthly.detect_skip("备份/PITR 演练", 1, "GATE_SKIP: 30"))
    check("GATE_SKIP: 0 → 不判 SKIP（跳过数为 0 即真跑）",
          not monthly.detect_skip("备份/PITR 演练", 0, "总计: 30 | PASS: 30\nGATE_SKIP: 0"))

    print("\n== 1. 备份 SKIP：开发模式 SKIP、release 模式 FAIL，均不写 30/30 ==")
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        (tmp / "target").mkdir()
        (tmp / "target" / "gate-numbers.json").write_text(
            json.dumps({"playwright.tests": 260, "backup.passed": 30, "backup.total": 30}),
            encoding="utf-8")
        rc, out, numbers = run_monthly_gate(
            monthly, tmp, [], "总计: 30 | PASS: 30 | FAIL: 0 | SKIP: 30\nGATE_SKIP: 30")
        check("开发模式 exit=0（SKIP 允许）", rc == 0, f"rc={rc}")
        check("开发模式汇总明确列出 SKIP", "SKIP" in out and "不计 PASS" in out)
        check("旧 backup 30/30 被删除", "backup.passed" not in numbers and "backup.total" not in numbers)
        check("本次未执行门禁的旧键（playwright.tests）保留", numbers.get("playwright.tests") == 260)

        (tmp / "target" / "gate-numbers.json").write_text(
            json.dumps({"backup.passed": 30, "backup.total": 30}), encoding="utf-8")
        rc, out, numbers = run_monthly_gate(
            monthly, tmp, ["--release"], "总计: 30 | PASS: 30\nGATE_SKIP: 30")
        check("release 模式 backup SKIP → 非 0", rc != 0, f"rc={rc}")
        check("release 模式指出 SKIP 被禁止", "release 模式禁止 SKIP" in out)
        check("release 模式不写 backup 通过数", "backup.passed" not in numbers)

    print("\n== 2. 旧格式 SKIP 得到同一结果 ==")
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        (tmp / "target").mkdir()
        (tmp / "target" / "gate-numbers.json").write_text(
            json.dumps({"backup.passed": 30, "backup.total": 30}), encoding="utf-8")
        rc, out, numbers = run_monthly_gate(
            monthly, tmp, [], "  ⏭️  SKIP 备份文件存在 — not found")
        check("旧格式：开发模式 exit=0 且标记 SKIP", rc == 0 and "SKIP" in out)
        check("旧格式：backup 数字不残留",
              "backup.passed" not in numbers and "backup.total" not in numbers)

    print("\n== 5. 正常备份 PASS 才写真实通过数 ==")
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        (tmp / "target").mkdir()
        rc, _out, numbers = run_monthly_gate(
            monthly, tmp, [], "总计: 30 | PASS: 30 | FAIL: 0 | SKIP: 0\nGATE_SKIP: 0")
        check("备份 PASS：exit=0", rc == 0, f"rc={rc}")
        check("备份 PASS：写入 30/30",
              numbers.get("backup.passed") == 30 and numbers.get("backup.total") == 30)

    print("\n== 4. 文档校验：无证据数字即失败（D1），mixed 锚点不中断（D2） ==")
    doc = load_module("doc_numbers_verify", "doc-numbers-verify.py")
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        docfile = tmp / "DOC.md"
        docfile.write_text("备份 30/30\nJava 2853 tests\n", encoding="utf-8")
        old_root, old_anchors = doc.ROOT, doc.ANCHORS
        doc.ROOT = tmp
        try:
            backup_anchor = ("DOC.md", r"备份 (\d+)/(\d+)", ["backup.passed", "backup.total"])
            doc.ANCHORS = [backup_anchor]
            drift = doc.check({"backup.passed": 30, "backup.total": 30})
            check("optional 锚点有实测证据且一致 → 通过", drift == [], f"drift={drift}")
            drift = doc.check({"java.tests": 2853})
            check("D1：锚点声明数字但实测键缺失 → 失败（不再静默跳过）",
                  len(drift) == 1 and "无证据" in str(drift[0][3]), f"drift={drift}")
            docfile_before = docfile.read_text(encoding="utf-8")
            drift = doc.check({"java.tests": 2853}, fix=True)
            check("D1：无证据时 --fix 不改写文档",
                  len(drift) == 1 and docfile.read_text(encoding="utf-8") == docfile_before,
                  f"drift={drift}")
            docfile.write_text("Java 2853 tests\n", encoding="utf-8")
            drift = doc.check({"backup.passed": 30, "backup.total": 30})
            check("optional 锚点整体不存在 → 仍允许跳过", drift == [], f"drift={drift}")

            mixed_anchor = ("DOC.md", r"Java (\d+) tests (\d+\.\d+)%",
                            ["java.tests", "fe.coverage_line_pct"])
            doc.ANCHORS = [mixed_anchor]
            docfile.write_text("Java 2853 tests 77.12%\n", encoding="utf-8")
            drift = doc.check({"java.tests": 2900})
            check("D2：mixed 锚点 optional 缺失但 required 漂移 → 仍失败",
                  len(drift) == 1 and "2900" in str(drift[0][4]), f"drift={drift}")
            drift = doc.check({"java.tests": 2853})
            check("D2：mixed 锚点 optional 缺失、required 一致 → 跳过该组",
                  drift == [], f"drift={drift}")

            doc.ANCHORS = [("DOC.md", r"Java (\d+) tests", ["java.tests"])]
            docfile.write_text("备份 30/30\nJava 2853 tests\n", encoding="utf-8")
            drift = doc.check({})
            check("required 键缺失 → 失败",
                  len(drift) == 1 and "必需实测键缺失" in str(drift[0][3]), f"drift={drift}")
            check("required 键缺失时 --fix 不回填",
                  doc.check({}, fix=True) and docfile.read_text(encoding="utf-8") ==
                  "备份 30/30\nJava 2853 tests\n")
        finally:
            doc.ROOT, doc.ANCHORS = old_root, old_anchors

    print("\n" + "=" * 60)
    if failures:
        print(f"❌ 门禁自测失败 {len(failures)} 项：{', '.join(failures)}")
        return 1
    print("✅ 门禁真实性自测全部通过（SKIP 不计 PASS、旧键不残留、required 键缺失即红）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
