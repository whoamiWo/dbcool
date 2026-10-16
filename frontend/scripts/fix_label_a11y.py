#!/usr/bin/env python3
"""一次性脚本：修复 jsx-a11y/label-has-associated-control 告警。

对 <label ...>TEXT</label> 紧跟 <input ... /> 的模式，
加 htmlFor/id 关联（id 由文件名前缀+序号构成，保证唯一）。
"""
import re
import sys
from pathlib import Path

FILES = [
    "pages/FormDesigner.tsx",
    "pages/Profile.tsx",
    "pages/RolesList.tsx",
    "pages/RowAclAdmin.tsx",
    "pages/SchemaDesigner.tsx",
    "pages/SchemaEditor.tsx",
    "pages/UsersList.tsx",
    "pages/ViewDesigner.tsx",
    "pages/WorkflowDesigner.tsx",
    "pages/WorkflowInstances.tsx",
]

ROOT = Path(__file__).parent.parent / "src"

# label 开始标签（可能带属性），捕获属性部分与内部文本
LABEL_RE = re.compile(r"<label(\s[^>]*)?>(.*?)</label>", re.S)


CONTROL_RE = re.compile(r"^\s*<(input|select|textarea)\b")


def _find_label_block(lines, i, n):
    """从第 i 行起寻找完整 <label ...>...</label> 块，返回 (start, end, attrs, inner)。"""
    buf = ""
    for k in range(i, min(i + 6, n)):
        buf += ("\n" if buf else "") + lines[k]
        m = LABEL_RE.search(buf)
        if m:
            return k, m
        if "</label>" in lines[k] and not m:
            return None, None
    return None, None


def transform(text: str, prefix: str) -> tuple[str, int]:
    count = 0
    lines = text.split("\n")
    n = len(lines)
    i = 0
    while i < n:
        stripped = lines[i].strip()
        if stripped.startswith("<label"):
            end, m = _find_label_block(lines, i, n)
            if m:
                attrs = m.group(1) or ""
                # 找 label 块之后的第一个控件行
                j = end + 1
                while j < n and lines[j].strip() == "":
                    j += 1
                if j < n and CONTROL_RE.match(lines[j]) and "htmlFor" not in attrs:
                    count += 1
                    uid = f"{prefix}-lbl-{count}"
                    if attrs:
                        lines[end] = lines[end].replace(
                            f"<label{attrs}>", f'<label{attrs.rstrip()} htmlFor="{uid}">', 1
                        )
                    else:
                        lines[end] = lines[end].replace("<label>", f'<label htmlFor="{uid}">', 1)
                    if not re.search(r'\bid\s*=', lines[j]):
                        lines[j] = re.sub(r"<(input|select|textarea)", rf'<\1 id="{uid}"', lines[j], count=1)
                    i = end
        i += 1
    return "\n".join(lines), count


total = 0
for rel in FILES:
    p = ROOT / rel
    if not p.exists():
        print(f"SKIP missing: {rel}")
        continue
    src = p.read_text(encoding="utf-8")
    prefix = "".join(w[0] for w in Path(rel).stem.split("_")).lower() or "a11y"
    dst, c = transform(src, prefix)
    if c:
        p.write_text(dst, encoding="utf-8")
        print(f"{rel}: {c} labels linked")
        total += c
print(f"TOTAL: {total}")
