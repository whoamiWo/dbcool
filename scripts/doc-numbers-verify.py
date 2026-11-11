#!/usr/bin/env python3
"""
Wave4 待办池 #16 — 文档门禁数字自动校准

问题：README / BASELINE / RISK_REGISTER / TECH_DEBT / 路线图 里的门禁数字
（Java 测试数、前端测试数、覆盖率、契约端点数、冒烟/租户/备份/终验通过数）
此前靠人工同步 —— 每轮迭代都会漂移（实测发现 README 仍写 2588/964/437×127，
实际已 2600+/972/438×130）。

机制：
  1) scripts/monthly-gate.py 跑完全量门禁后把实测数字写入 target/gate-numbers.json
  2) 本脚本按「锚点正则」从文档中定位这些数字并与实测比对：
       - 一致 → 通过
       - 不一致 → exit 1（列出文件、行号、期望值、实际声明值）
       - --fix → 直接把文档中的数字回填为实测值（自动校准）

用法：
    python3 scripts/doc-numbers-verify.py            # 校验（exit 1 = 有漂移）
    python3 scripts/doc-numbers-verify.py --fix      # 回填实测值
    python3 scripts/doc-numbers-verify.py --list     # 列出锚点与实测值

退出码：0 = 一致；1 = 存在漂移（--fix 时表示回填失败）；2 = 前置条件缺失（无实测数据）
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NUMBERS_FILE = Path(os.environ["DOC_NUMBERS_FILE"]) if os.environ.get("DOC_NUMBERS_FILE") \
    else ROOT / "target" / "gate-numbers.json"

# ============================================================
# 键的白名单/必需语义（TY4 G6：缺失键不得静默跳过）
# - OPTIONAL_KEYS：这些键允许缺失（锚点检查被跳过），仅限显式清单。
# - 其它键：锚点引用了但实测缺失 → 校验失败（防"删掉 key 绕过漂移检查"）。
# backup.* 是可选键：备份/PITR 必须在 --release 实测真实通过后才写数字；
# 未实测时文档不得宣称 PASS（README 对应行改为"未实测"说明）。
# ============================================================
OPTIONAL_KEYS = {
    "playwright.tests",       # 由 Playwright 单独运行写入，monthly-gate 不跑
    "fe.coverage_line_pct",   # 前端覆盖率另由 vitest --coverage 采集
    "backup.passed", "backup.total",  # 仅 release 实测真实通过时写入
}

# ============================================================
# 锚点表：(文件, 正则, [对应 gate-numbers 键...])
# 正则每个捕获组依次对应一个键；第一个捕获组是待校准的数字。
# ============================================================
ANCHORS = [
    ("README.md",
     r"tests-\d+%20\(backend%20(\d+)%20%2F%20frontend%20(\d+)\)",
     ["java.tests", "fe.passed"]),
    ("README.md",
     r"\| Java `mvn -o verify` \| ✅ (\d+) tests",
     ["java.tests"]),
    ("README.md",
     r"\| 前端 `npm run test:run` \| ✅ (\d+) files / \*\*(\d+) passed",
     ["fe.files", "fe.passed"]),
    ("README.md",
     r"\| 前后端契约 \| ✅ (\d+) 后端端点 × (\d+) 前端调用",
     ["contract.backend_paths", "contract.frontend_paths"]),
    ("README.md",
     r"\| 备份/PITR 演练 \| ✅ (\d+)/(\d+)",
     ["backup.passed", "backup.total"]),
    ("README.md",
     r"\| 冒烟 / 租户隔离 \| ✅ (\d+)/(\d+) · (\d+)/(\d+)",
     ["smoke.passed", "smoke.total", "tenant.passed", "tenant.total"]),
    ("README.md",
     r"\| 上线终验 \| ✅ (\d+)/(\d+)",
     ["launch.passed", "launch.total"]),
    ("README.md",
     r"Java%20coverage-([\d.]+)%25",
     ["java.coverage_line_pct"]),
    ("README.md",
     r"JaCoCo BUNDLE 行覆盖 \*\*([\d.]+)%\*\*",
     ["java.coverage_line_pct"]),
    ("TECH_DEBT.md",
     r"契约 \*\*0 漂移\*\*（(\d+) 后端 × (\d+) 前端）",
     ["contract.backend_paths", "contract.frontend_paths"]),
    ("docs/CI_GATES.md",
     r"\| Java 测试 \|[^\n]*\| (\d+)，0 failures",
     ["java.tests"]),
    ("docs/CI_GATES.md",
     r"\| Playwright（含 375px） \|[^\n]*\| (\d+) tests",
     ["playwright.tests"]),
    ("docs/CI_GATES.md",
     r"\| Python \|[^\n]*\| (\d+) passed / (\d+) skipped",
     ["python.passed", "python.skipped"]),
    ("docs/PRODUCTIZATION_ROADMAP.md",
     r"Wave 0\.5 后复测\*\*（同日）：Java (\d+) run / 0F / 0E（BUNDLE LINE ([\d.]+)）",
     ["java.tests", "java.coverage_line_pct"]),
    ("docs/PRODUCTIZATION_ROADMAP.md",
     r"✅ 已统一（Java (\d+)、前端 (\d+)/(\d+) files、BUNDLE ([\d.]+)、前端行覆盖 ([\d.]+)%、Python (\d+)/(\d+)",
     ["java.tests", "fe.passed", "fe.files", "java.coverage_line_pct", "fe.coverage_line_pct", "python.passed", "python.skipped"]),
    ("BASELINE.md",
     r"mvn test\s+# ✅ (\d+)/(\d+) PASS",
     ["java.tests", "java.tests"]),
    ("BASELINE.md",
     r"npx vitest run\s+# ✅ (\d+)/(\d+) PASS[（(](\d+) test files",
     ["fe.passed", "fe.passed", "fe.files"]),
    ("BASELINE.md",
     r"实测：(\d+) tests / (\d+) files，全部通过",
     ["fe.passed", "fe.files"]),
    ("BASELINE.md",
     r"# (\d+) passed, (\d+) skipped",
     ["python.passed", "python.skipped"]),
    ("RISK_REGISTER.md",
     r"\| 后端单测覆盖率 \|.*?\| ([\d.]+)% \|",
     ["java.coverage_line_pct"]),
    ("TECH_DEBT.md",
     r"Java \*\*(\d+)/0F/0E\*\*、前端 vitest \*\*(\d+)/(\d+)\*\*（(\d+) 文件",
     ["java.tests", "fe.passed", "fe.passed", "fe.files"]),
    ("TECH_DEBT.md",
     r"pytest tests/  →  (\d+) passed, (\d+) skipped",
     ["python.passed", "python.skipped"]),
    ("docs/PRODUCTIZATION_ROADMAP.md",
     r"\| 后端 \| `cd backend-java && mvn -o verify` \| (\d+) run / 0F / 0E",
     ["java.tests"]),
    ("docs/PRODUCTIZATION_ROADMAP.md",
     r"\| 前端单测 \| `cd frontend && npm run test:run` \| (\d+) passed / 0 failed",
     ["fe.passed"]),
]


def load_numbers() -> dict:
    if not NUMBERS_FILE.exists():
        print(f"❌ 退出码 2：缺少实测数据 {NUMBERS_FILE.relative_to(ROOT)}\n"
              f"   请先运行：python3 scripts/monthly-gate.py（会写入实测数字）")
        sys.exit(2)
    try:
        return json.loads(NUMBERS_FILE.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        print(f"❌ 退出码 2：实测数据解析失败：{e}")
        sys.exit(2)


def fmt(key: str, value) -> str:
    if key.endswith("coverage_line_pct"):
        return f"{float(value):.2f}"
    return str(int(value))


def check(numbers, fix=False):
    """遍历锚点比对/回填。返回漂移列表（fix 回填成功后漂移列表已被清空的部分仍会包含）。

    TY4 D1/D2：
    - optional 只表示"锚点/文件可以不存在"；一旦锚点匹配到文档里的数字，
      而对应实测键缺失，就是**没有证据的数字**，必须失败（不得静默跳过）。
    - 缺失键只跳过它对应的捕获组，不中断同一锚点里其它 required 键的比较。
    """
    drift = []
    for rel, pattern, keys in ANCHORS:
        anchor_optional = all(k in OPTIONAL_KEYS for k in keys)
        path = ROOT / rel
        if not path.exists():
            if anchor_optional:
                continue
            drift.append((rel, 0, pattern, "文件不存在", None))
            continue
        text = path.read_text(encoding="utf-8")
        m = re.search(pattern, text)
        if not m:
            if anchor_optional:
                continue  # 可选锚点（如未实测的备份数字）允许文档不含该行
            drift.append((rel, 0, pattern, "锚点未找到（文档结构可能已改）", None))
            continue

        line_no = text[: m.start()].count("\n") + 1
        expected_by_index: dict[int, str] = {}
        missing_required = []
        missing_optional = []
        for idx, key in enumerate(keys, start=1):
            val = numbers.get(key)
            if val is None:
                if key in OPTIONAL_KEYS:
                    missing_optional.append(key)
                else:
                    missing_required.append(key)
                continue
            expected_by_index[idx] = fmt(key, val)

        if missing_required:
            # TY4 G6：required key 缺失 → 失败，禁止"删 key 绕过漂移检查"
            drift.append((rel, line_no, pattern,
                          f"必需实测键缺失: {', '.join(missing_required)}", None))
            continue
        if not expected_by_index:
            # TY4 D1：锚点已经声明了数字，但本次没有任何实测证据（可选键也不豁免）
            drift.append((rel, line_no, pattern,
                          "锚点声明数字但实测键缺失（无证据）: "
                          f"{', '.join(missing_optional)}", None))
            continue

        actual_groups = list(m.groups())
        mismatches = []
        for idx in sorted(expected_by_index):
            actual = actual_groups[idx - 1] if idx - 1 < len(actual_groups) else None
            if actual != expected_by_index[idx]:
                mismatches.append((idx, actual, expected_by_index[idx]))
        if not mismatches:
            continue
        drift.append((
            rel, line_no, pattern,
            " / ".join(str(a) for _i, a, _e in mismatches),
            " / ".join(str(e) for _i, _a, e in mismatches),
        ))

        if fix:
            # 回填：从后往前替换，避免偏移；只替换实测存在的捕获组
            new_text = text
            for idx, _actual, expected in sorted(mismatches, key=lambda x: x[0], reverse=True):
                new_text = new_text[: m.start(idx)] + expected + new_text[m.end(idx):]
            path.write_text(new_text, encoding="utf-8")
            print(f"🔧 已回填 {rel}:{line_no}  "
                  f"{[a for _i, a, _e in mismatches]} → {[e for _i, _a, e in mismatches]}")

    return drift


def main() -> int:
    ap = argparse.ArgumentParser(description="文档门禁数字自动校准")
    ap.add_argument("--fix", action="store_true", help="把文档中的数字回填为实测值")
    ap.add_argument("--list", action="store_true", help="只列出锚点与实测值")
    args = ap.parse_args()

    numbers = load_numbers()

    if args.list:
        for f, pattern, keys in ANCHORS:
            vals = [numbers.get(k) for k in keys]
            print(f"{f}: {pattern}")
            print(f"   keys={keys} 实测={vals}")
        return 0

    drift = check(numbers, fix=args.fix)
    if args.fix and drift:
        # 回填后复核：报告真实最终状态（回填成功的项不应再被报为漂移）
        drift = check(numbers, fix=False)

    if not drift:
        print(f"✅ 文档门禁数字与实测一致（{len(ANCHORS)} 个锚点）")
        return 0

    print(f"\n❌ 发现 {len(drift)} 处文档数字漂移：")
    for rel, line_no, pattern, actual, expected in drift:
        print(f"  {rel}:{line_no}  声明={actual}  实测={expected}")
        print(f"      锚点: {pattern}")
    if args.fix:
        print("\n⚠️  --fix 未能回填全部（锚点缺失时需人工处理）")
        return 1
    print("\n💡 修复方式：python3 scripts/doc-numbers-verify.py --fix（锚点缺失时需人工改）")
    return 1


if __name__ == "__main__":
    sys.exit(main())
