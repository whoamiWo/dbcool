#!/usr/bin/env python3
"""
PHASE97 T5: 前后端契约比对 —— 零漂移门禁

从 contracts/backend-mappings.json 与 contracts/frontend-mappings.json 比对：
- 前端调用路径必须全部在后端存在（消除路径错位）
- 前端路径不得含双前缀 /api/api（baseURL 已带 /api）
- 后端路径不得含空方法或缺少 URL

退出码：0 = PASS，1 = FAIL

用法：
    python3 scripts/api-contract-verify.py
"""
import json
import sys
from pathlib import Path

BASE_DIR = Path(__file__).parent.parent
BACKEND_JSON = BASE_DIR / "contracts" / "backend-mappings.json"
FRONTEND_JSON = BASE_DIR / "contracts" / "frontend-mappings.json"


def load(path):
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)


def normalize(path):
    """Wave0.5 A-8：把路径参数段（{id} / {}）归一化为 {}，实现模式级比对。
    前端模板字面量（/im/messages/{}/burn/read）与后端 @PathVariable
    （/api/im/messages/{id}/burn/read）的参数名可以不同，但段数与字面量必须一致。
    查询串（?...）不参与路径比对。"""
    import re as _re
    path = path.split('?', 1)[0]
    return _re.sub(r'\{[^/]*\}', '{}', path)


# Wave0.5 A-8：显式例外清单 —— 前端模板段实为"字面量枚举"的合法调用。
# 格式：(前端归一化模式, 允许变量段匹配字面量的段索引)。逐条人工维护，
# 禁止默认宽松匹配（否则 POST /im/messages/{}/pin 会被 /im/messages/pins 吞掉，掩盖真漂移）。
APPROVED_WILDCARDS = {
    ("/api/workflows/tasks/{}/{}", 4),  # 第 4 段 ${op} ∈ {approve, reject}，对应后端 /tasks/{id}/approve|reject
}


def wildcard_match(front_pattern: str, back_pattern: str, seg_idx: int) -> bool:
    f = front_pattern.strip('/').split('/')
    b = back_pattern.strip('/').split('/')
    if len(f) != len(b) or seg_idx >= len(f):
        return False
    for i, (fp, bp) in enumerate(zip(f, b)):
        if i == seg_idx and fp == '{}':
            continue  # 该位允许变量段 ↔ 字面量枚举
        if fp == bp:
            continue  # 其余位按归一化字面比较
        return False
    return True


def main():
    print("=" * 70)
    print("PHASE97 T5: 前后端契约比对")
    print("=" * 70)

    backend = load(BACKEND_JSON)
    frontend = load(FRONTEND_JSON)

    print(f"\n后端路径数: {len(backend)}")
    print(f"前端路径数: {len(frontend)}")

    errors = []

    # 1. 前端路径在后端无对应映射 → 路径错位（Wave0.5 A-8：改为模式级比对 + 段级回退）
    backend_patterns = {normalize(m["path"]) for m in backend}
    missing_in_backend = []
    loose_matched = 0
    for m in frontend:
        full_path = f"/api{m['path']}"
        norm = normalize(full_path)
        if norm in backend_patterns:
            continue
        # 回退：仅显式例外清单允许"变量段 ↔ 字面量枚举"
        if any(wildcard_match(norm, bp, idx)
               for (pat, idx) in APPROVED_WILDCARDS if pat == norm
               for bp in backend_patterns):
            loose_matched += 1
            continue
        missing_in_backend.append({**m, "full_path": full_path})

    if missing_in_backend:
        errors.append("前端调用路径在后端无对应映射（路径错位）：")
        for m in missing_in_backend:
            errors.append(f"  {m['full_path']} ← {m['file']}:{m['line']}")

    # 2. 前端路径含双前缀 /api/
    double_prefixed = [m for m in frontend if m["path"].startswith("/api/")]
    if double_prefixed:
        errors.append("前端路径重复携带 /api 前缀（baseURL 已带 /api）：")
        for m in double_prefixed:
            errors.append(f"  {m['path']} ← {m['file']}:{m['line']}")

    # 3. 后端路径无 HTTP method（扫描缺失注解的映射）
    no_method = [m for m in backend if not m.get("method")]
    if no_method:
        errors.append("后端路径缺少 HTTP method 注解：")
        for m in no_method:
            errors.append(f"  {m['path']} ← {m['file']}:{m['line']}")

    # 4. 后端路径重复（同 method+path 在**同一栈**出现多次）
    # Wave0.5 A-8：Java/Python 两栈各自实现 /api/health 属合法架构，跨栈不算重复。
    path_counts = {}
    for m in backend:
        key = (m.get("stack", "java"), m["method"], m["path"])
        path_counts[key] = path_counts.get(key, 0) + 1
    duplicates = [(k, v) for k, v in path_counts.items() if v > 1]
    if duplicates:
        errors.append("后端路径重复定义：")
        for (stack, method, path), count in duplicates:
            errors.append(f"  [{stack}] {method} {path} ×{count}")

    # 结果
    print("\n比对结果：")
    if errors:
        print("FAIL：发现以下漂移")
        for e in errors:
            print(e)
        print("\n" + "=" * 70)
        sys.exit(1)
    else:
        print(f"  ✅ {len(backend)} 后端路径 × {len(frontend)} 前端路径 → 0 漂移"
              f"（其中 {loose_matched} 条为模板段↔字面量枚举的段级回退匹配）")
        print("\nPASS：0 处漂移")
        sys.exit(0)


if __name__ == "__main__":
    main()
