#!/usr/bin/env python3
"""
版本一致性校验脚本（Wave 0 T0.1）。

功能：读根目录 VERSION 文件作为单一真相源，逐一比对 10 处源文件中的版本号。

用法:
    python3 scripts/version-consistency-verify.py           # 只校验
    python3 scripts/version-consistency-verify.py --fix     # 从 VERSION 写回各文件（幂等）

退出码:
    0 = 全部一致
    1 = 发现不一致
    2 = 执行错误（VERSION 缺失、源文件缺失、解析失败、版本号不符合 X.Y.Z 格式）

红线：源文件缺失不得静默跳过，必须 exit 2（否则就是恒真门禁）。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 待校验的 10 处版本号位置：相对路径 -> (格式, 定位 key)
TARGETS = {
    "frontend/package.json": ("json", "version"),
    "backend-java/pom.xml": ("xml", "project.version"),
    "backend-python/pyproject.toml": ("toml_top", "version"),
    "backend-python/src/nocobase_py/__init__.py": ("py_assign", "__version__"),
    "backend-python/src/nocobase_py/config.py": ("py_assign", "app_version"),
    "backend-java/src/main/java/com/nocobase/health/HealthController.java": ("java_put", 'put("version"'),
    "backend-java/src/main/java/com/nocobase/config/OpenApiConfig.java": ("java_method", '.version('),
    "contracts/errors.yaml": ("yaml", "version"),
    "contracts/schemas/common.schema.json": ("json", "version"),
    "README.md": ("md_badge", "badge"),
}

SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")

# 各文件中出现的其他合法 semver（依赖版本等），仅用于 README 徽章替换保护
README_HEAD_LINES = 40


def read_text(p: Path) -> str:
    if not p.exists():
        raise FileNotFoundError(f"源文件缺失：{p}")
    return p.read_text(encoding="utf-8")


def extract_version(content: str, kind: str, key: str) -> str:
    """根据文件格式提取版本号。解析失败抛 ValueError。"""
    if kind == "json":
        data = json.loads(content)
        if key not in data:
            raise ValueError(f"JSON 缺少顶层 key '{key}'")
        return str(data[key]).strip()

    if kind == "xml":
        # 只取 com.nocobase 项目自身的 <version>，跳过 parent（spring-boot-starter-parent）
        m = re.search(
            r"<groupId>com\.nocobase</groupId>\s*<artifactId>nocobase-backend</artifactId>\s*<version>([^<]+)</version>",
            content,
        )
        if not m:
            raise ValueError("pom.xml 未找到 com.nocobase:nocobase-backend 的 <version>")
        return m.group(1).strip()

    if kind == "toml_top":
        m = re.search(r'^version\s*=\s*"([^"]+)"', content, re.MULTILINE)
        if not m:
            raise ValueError("pyproject.toml 缺少顶层 version")
        return m.group(1).strip()

    if kind == "py_assign":
        # 允许缩进（config.py 里 app_version 位于类体内）与类型注解
        m = re.search(
            rf'^\s*{re.escape(key)}\s*(?::\s*[^=]+)?=\s*"([^"]+)"', content, re.MULTILINE
        )
        if not m:
            raise ValueError(f"Python 文件缺少 {key} 赋值")
        return m.group(1).strip()

    if kind == "java_put":
        m = re.search(re.escape(key) + r',\s*"([^"]+)"\)', content)
        if not m:
            raise ValueError("HealthController 未找到 put(\"version\", ...)")
        return m.group(1).strip()

    if kind == "java_method":
        m = re.search(re.escape(key) + r'"([^"]+)"\)', content)
        if not m:
            raise ValueError("OpenApiConfig 未找到 .version(...)")
        return m.group(1).strip()

    if kind == "yaml":
        m = re.search(r'^version:\s*["\']?([^"\'\s]+)["\']?\s*$', content, re.MULTILINE)
        if not m:
            raise ValueError("errors.yaml 缺少 version 字段")
        return m.group(1).strip()

    if kind == "md_badge":
        head = "\n".join(content.splitlines()[:README_HEAD_LINES])
        m = re.search(r"\bv(\d+\.\d+\.\d+)\b", head)
        if not m:
            raise ValueError(f"README.md 前 {README_HEAD_LINES} 行未找到 v 语义化版本")
        return m.group(1)

    raise ValueError(f"未知格式：{kind}")


def write_version(path: Path, kind: str, key: str, old_ver: str, new_ver: str) -> None:
    """把 new_ver 写回文件（--fix 模式）。只替换 extract_version 命中的那一处。"""
    content = path.read_text(encoding="utf-8")

    if kind == "json":
        data = json.loads(content)
        data[key] = new_ver
        content = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    elif kind == "xml":
        content = content.replace(
            f"<artifactId>nocobase-backend</artifactId>\n        <version>{old_ver}</version>",
            f"<artifactId>nocobase-backend</artifactId>\n        <version>{new_ver}</version>",
            1,
        )
    elif kind == "toml_top":
        content = content.replace(f'version = "{old_ver}"', f'version = "{new_ver}"', 1)
    elif kind == "py_assign":
        content = content.replace(f'{key} = "{old_ver}"', f'{key} = "{new_ver}"', 1)
    elif kind == "java_put":
        content = content.replace(f'{key}, "{old_ver}")', f'{key}, "{new_ver}")', 1)
    elif kind == "java_method":
        content = content.replace(f'{key}"{old_ver}")', f'{key}"{new_ver}")', 1)
    elif kind == "yaml":
        content = re.sub(
            r'^(version:\s*)(["\']?)\d+\.\d+\.\d+\2(\s*)$',
            rf'\g<1>\g<2>{new_ver}\g<2>\g<3>',
            content,
            count=1,
            flags=re.MULTILINE,
        )
    elif kind == "md_badge":
        # 只替换头部第一处 v 版本（badge）
        head_lines = content.splitlines(keepends=True)
        for i, line in enumerate(head_lines[:README_HEAD_LINES]):
            if re.search(rf"\bv{re.escape(old_ver)}\b", line):
                head_lines[i] = re.sub(
                    rf"\bv{re.escape(old_ver)}\b", f"v{new_ver}", line
                )
                break
        content = "".join(head_lines)

    path.write_text(content, encoding="utf-8")


def check_all(expected: str):
    """遍历 TARGETS 校验。返回 (mismatches, errors)。"""
    mismatches = []
    errors = []

    for rel_path, (kind, key) in TARGETS.items():
        try:
            content = read_text(ROOT / rel_path)
            ver = extract_version(content, kind, key)
            if not SEMVER_RE.match(ver):
                raise ValueError(f"提取到的版本号不符合 X.Y.Z 格式：{ver}")
            if ver == expected:
                print(f"✅ {rel_path} = {ver}")
            else:
                print(f"⚠️  {rel_path} = {ver}（预期 {expected}）")
                mismatches.append((rel_path, kind, key, ver))
        except Exception as e:
            print(f"❌ {rel_path}: {e}", file=sys.stderr)
            errors.append(rel_path)

    return mismatches, errors


def main() -> int:
    parser = argparse.ArgumentParser(description="版本一致性校验（单一真相源：VERSION）")
    parser.add_argument("--fix", action="store_true", help="从 VERSION 写回各文件")
    args = parser.parse_args()

    version_file = ROOT / "VERSION"
    if not version_file.exists():
        print("❌ VERSION 文件缺失", file=sys.stderr)
        return 2

    expected = version_file.read_text(encoding="utf-8").strip()
    if not SEMVER_RE.match(expected):
        print(f"❌ VERSION 内容不符合 X.Y.Z 格式：{expected!r}", file=sys.stderr)
        return 2

    print(f"🎯 基准版本（VERSION）：{expected}")
    print()

    mismatches, errors = check_all(expected)
    if errors:
        print()
        print("🛑 存在执行错误（源文件缺失 / 解析失败），校验无法完成", file=sys.stderr)
        return 2

    if not mismatches:
        print()
        print("✅ 所有版本一致")
        return 0

    if not args.fix:
        print()
        print(f"❌ 发现 {len(mismatches)} 处版本不一致")
        return 1

    # --fix 模式：写回后真实复检（不复用上面结果，保证 --fix 输出可信）
    print()
    print(f"🔧 正在从 VERSION 写回 {len(mismatches)} 处…")
    for rel_path, kind, key, old_ver in mismatches:
        write_version(ROOT / rel_path, kind, key, old_ver, expected)
        print(f"🔧 {rel_path}: {old_ver} -> {expected}")

    print("✅ 写回完成，重新校验：")
    print()
    rerun_mismatches, rerun_errors = check_all(expected)
    if rerun_errors:
        print()
        print("🛑 写回后仍存在执行错误", file=sys.stderr)
        return 2
    if rerun_mismatches:
        print()
        print(f"❌ 写回后仍有 {len(rerun_mismatches)} 处不一致（--fix 未覆盖，需人工检查）")
        return 1

    print()
    print("✅ 所有版本一致（--fix 写回并复检通过）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
