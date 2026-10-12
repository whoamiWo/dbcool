#!/usr/bin/env python3
"""
PHASE97 T1: 后端 Controller 路径全量导出

扫描 backend-java/src/main/java 所有 *Controller.java，解析
类级 @RequestMapping + 方法级 @GetMapping/@PostMapping 等注解，
输出 contracts/backend-mappings.json 作为契约 SSOT。

用法：
    python3 scripts/export-backend-mappings.py

输出示例：
    [
      {"class": "ImMessageController", "method": "GET", "path": "/api/im/messages/pins"},
      ...
    ]
"""
import json
import os
import re
from pathlib import Path

BASE_DIR = Path(__file__).parent.parent
SCAN_ROOT = BASE_DIR / "backend-java" / "src" / "main" / "java"

# Spring MVC 注解正则
CLASS_MAPPING_RE = re.compile(r'@RequestMapping\s*\(\s*"([^"]+)"')
# 方法级注解：路径可选。
# 支持三种写法：
#   @GetMapping                                  → 路径 = 类级路径
#   @GetMapping("/path")                         → 路径 = /path
#   @GetMapping(value = "/path", produces = ...) → 路径 = /path
METHOD_MAPPING_RE = re.compile(
    r'@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\s*'
    r'(?:\(\s*(?:value\s*=\s*)?"([^"]+)"[^)]*\))?'
)

# 方法路径合并
def combine_paths(class_path: str, method_path: str) -> str:
    if method_path.startswith('/'):
        return class_path.rstrip('/') + method_path
    return class_path.rstrip('/') + '/' + method_path.lstrip('/')

def scan_controllers():
    mappings = []
    for java_file in SCAN_ROOT.rglob("*Controller.java"):
        rel_path = str(java_file.relative_to(BASE_DIR))
        content = java_file.read_text(encoding='utf-8')
        lines = content.splitlines()

        class_path = None
        class_name = java_file.stem

        # 找类级 @RequestMapping
        for line in lines:
            m = CLASS_MAPPING_RE.search(line)
            if m:
                class_path = m.group(1)
                break

        if not class_path:
            continue

        # 找方法级注解
        for i, line in enumerate(lines):
            m = METHOD_MAPPING_RE.search(line)
            if m:
                # 注解名 → HTTP 方法
                # @GetMapping → GET, @PostMapping → POST, @DeleteMapping → DELETE
                http_method = m.group(1).replace("Mapping", "").upper()
                # 无路径注解（@GetMapping）→ 路径 = 类级路径本身
                method_path = m.group(2) if m.group(2) else ""
                full_path = combine_paths(class_path, method_path) if method_path else class_path
                mappings.append({
                    "class": class_name,
                    "method": http_method,
                    "path": full_path,
                    "file": rel_path,
                    "line": i + 1
                })

    return mappings

def main():
    mappings = scan_controllers()
    mappings.sort(key=lambda x: (x["class"], x["method"], x["path"]))

    out_path = BASE_DIR / "contracts" / "backend-mappings.json"
    out_path.parent.mkdir(exist_ok=True)
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(mappings, f, ensure_ascii=False, indent=2)

    print(f"导出 {len(mappings)} 个后端路径映射 → {out_path}")
    # 打印前 10 条示例
    for m in mappings[:10]:
        print(f"  {m['method']:6} {m['path']}")
    if len(mappings) > 10:
        print(f"  ... 共 {len(mappings)} 条")

if __name__ == "__main__":
    main()