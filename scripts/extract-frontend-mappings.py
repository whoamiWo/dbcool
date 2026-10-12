#!/usr/bin/env python3
"""
PHASE97 T3: 提取前端 API 调用路径

扫描 frontend/src 所有 .ts/.tsx 文件，提取 client.get/post/put/patch/delete(...)
与 apiClient.get/post/put/patch/delete(...) 的路径参数，输出 contracts/frontend-mappings.json。

用法：
    python3 scripts/extract-frontend-mappings.py
"""
import json
import re
from pathlib import Path

BASE_DIR = Path(__file__).parent.parent
FRONTEND_ROOT = BASE_DIR / "frontend" / "src"

# 匹配 client.xxx('/path') 或 apiClient.xxx('/path') 或 client.xxx(`/path`)
PATH_RE = re.compile(r'(?:client|apiClient)\.(?:get|post|put|patch|delete)\s*\(\s*[\'`]([^\'`]+)[\'`]')

def extract_frontend_paths():
    mappings = []
    for py_file in FRONTEND_ROOT.rglob("*"):
        if not py_file.suffix in (".ts", ".tsx"):
            continue
        rel_path = str(py_file.relative_to(BASE_DIR))

        # 排除测试文件：测试里的路径是 mock 假路径（如 /api/test），不是真实调用
        if "/test" in rel_path or rel_path.endswith(".test.ts") or rel_path.endswith(".test.tsx"):
            continue

        content = py_file.read_text(encoding='utf-8', errors='ignore')

        for i, line in enumerate(content.splitlines(), 1):
            for m in PATH_RE.finditer(line):
                path = m.group(1)
                # 跳过模板变量（含 $）
                if '$' in path:
                    continue
                mappings.append({
                    "path": path,
                    "file": rel_path,
                    "line": i
                })
    
    # 去重
    seen = set()
    unique = []
    for m in mappings:
        key = m["path"]
        if key not in seen:
            seen.add(key)
            unique.append(m)
    
    return sorted(unique, key=lambda x: x["path"])

def main():
    mappings = extract_frontend_paths()
    out_path = BASE_DIR / "contracts" / "frontend-mappings.json"
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(mappings, f, ensure_ascii=False, indent=2)
    
    print(f"提取 {len(mappings)} 个前端路径 → {out_path}")
    for m in mappings[:15]:
        print(f"  {m['path']}")
    if len(mappings) > 15:
        print(f"  ... 共 {len(mappings)} 条")

if __name__ == "__main__":
    main()