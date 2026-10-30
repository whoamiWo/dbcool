#!/usr/bin/env python3
"""
Wave1 T1.2 — 从 CHANGELOG.md 抽取指定版本的发布说明。

用法：
    python3 scripts/extract-changelog.py --version v2.1.1
    python3 scripts/extract-changelog.py --version 2.1.1        # v 前缀可选
    python3 scripts/extract-changelog.py --version v9.9.9       # 不存在 → exit 2

退出码：
    0 = 成功输出该版本段落
    2 = 版本不存在 / CHANGELOG 缺失 / 参数错误（禁静默产出空 Notes）
"""
import argparse
import sys
from pathlib import Path

BASE_DIR = Path(__file__).parent.parent
CHANGELOG = BASE_DIR / "CHANGELOG.md"


def extract(version: str) -> str:
    text = CHANGELOG.read_text(encoding="utf-8")
    target = version if version.startswith("v") else f"v{version}"
    # 匹配 '## v2.1.1 (2026-10-30)' 或 '## v2.1.1 — xxx'
    marker = f"## {target}"
    start = None
    for line in text.splitlines():
        if line.startswith(marker) and line.startswith("## "):
            start = line
            break
    if start is None:
        print(f"❌ 退出码 2：CHANGELOG 中不存在版本条目 {target}", file=sys.stderr)
        sys.exit(2)
    out = [start]
    started = False
    for line in text.splitlines():
        if not started:
            if line == start:
                started = True
            continue
        if line.startswith("## "):
            break
        out.append(line)
    block = "\n".join(out).strip()
    if not block.strip(start).strip():
        print(f"❌ 退出码 2：版本 {target} 条目为空段", file=sys.stderr)
        sys.exit(2)
    return block


def main() -> None:
    ap = argparse.ArgumentParser(description="抽取 CHANGELOG 指定版本段落")
    ap.add_argument("--version", required=True, help="如 v2.1.1 或 2.1.1")
    args = ap.parse_args()
    print(extract(args.version))


if __name__ == "__main__":
    main()
