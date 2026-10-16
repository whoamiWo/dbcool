#!/usr/bin/env python3
"""一次性脚本：为可点击的 <div onClick> 补 role/tabIndex/onKeyDown。

对以下模式的 <div ...> 添加 a11y 属性（在 `<div` 标签行插入新属性）：
  1. 含 onClick 且无 role 的 div → role="button" tabIndex={0}
     + onKeyDown 键盘处理（Enter/空格 触发 currentTarget.click()）
  2. 仅含拖拽/装饰语义的 div → role="presentation"
"""
import re
from pathlib import Path

ROOT = Path(__file__).parent.parent / "src"

CLICK_FILES = [
    ("features/im/ChannelList.tsx", 75),
    ("features/im/MessageList.tsx", 271),
    ("features/im/SearchResults.tsx", 73),
    ("pages/MessagesInbox.tsx", 88),
    ("pages/GalleryView.tsx", 129),
    ("pages/RowAclAdmin.tsx", 257),
    ("pages/RowAclAdmin.tsx", 258),
    ("pages/NotificationChannels.tsx", 238),
    ("pages/NotificationChannels.tsx", 239),
    ("pages/FormDesigner.tsx", 336),
    ("pages/FormDesigner.tsx", 383),
]

PRESENTATION_FILES = [
    ("pages/FormDesigner.tsx", 303),
    ("pages/FormDesigner.tsx", 325),
    ("pages/ErDiagram.tsx", 158),
]

ONKEYDOWN = (
    'onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") {'
    ' e.preventDefault(); e.currentTarget.click(); } }}'
)


def find_div_line(lines, ln):
    """在 ln 行附近向上找 <div 行，返回 0-based index，找不到返回 -1。"""
    for i in range(ln - 1, max(0, ln - 6), -1):
        if "<div" in lines[i]:
            return i
    return -1


def div_block_has_role(lines, div_idx):
    """判断 <div> 开始行到其后 5 行内是否已有 role=。"""
    chunk = "\n".join(lines[div_idx : div_idx + 6])
    return "role=" in chunk


def process_click(path: Path, ln: int) -> int:
    lines = path.read_text(encoding="utf-8").split("\n")
    idx = find_div_line(lines, ln)
    if idx < 0:
        print(f"  SKIP no div near line {ln} in {path.name}")
        return 0
    if div_block_has_role(lines, idx):
        print(f"  SKIP already has role {path.name}:{ln}")
        return 0
    line = lines[idx]
    # 在 `<div` 后插入属性
    inserted = line.replace("<div", f'<div role="button" tabIndex={{0}} {ONKEYDOWN}', 1)
    lines[idx] = inserted
    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"  OK {path.name}:{ln}")
    return 1


def process_presentation(path: Path, ln: int) -> int:
    lines = path.read_text(encoding="utf-8").split("\n")
    idx = find_div_line(lines, ln)
    if idx < 0:
        print(f"  SKIP no div near line {ln} in {path.name}")
        return 0
    if div_block_has_role(lines, idx):
        print(f"  SKIP already has role {path.name}:{ln}")
        return 0
    lines[idx] = lines[idx].replace("<div", '<div role="presentation"', 1)
    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"  OK {path.name}:{ln}")
    return 1


total = 0
print("=== 可点击 div ===")
for rel, ln in CLICK_FILES:
    total += process_click(ROOT / rel, ln)
print("=== 拖拽/装饰 div ===")
for rel, ln in PRESENTATION_FILES:
    total += process_presentation(ROOT / rel, ln)
print(f"TOTAL: {total}")
