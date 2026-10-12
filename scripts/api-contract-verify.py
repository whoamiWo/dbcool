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


def main():
    print("=" * 70)
    print("PHASE97 T5: 前后端契约比对")
    print("=" * 70)

    backend = load(BACKEND_JSON)
    frontend = load(FRONTEND_JSON)

    print(f"\n后端路径数: {len(backend)}")
    print(f"前端路径数: {len(frontend)}")

    errors = []

    # 1. 前端路径在后端无对应映射 → 路径错位
    backend_paths = {m["path"] for m in backend}
    missing_in_backend = []
    for m in frontend:
        full_path = f"/api{m['path']}"
        if full_path not in backend_paths:
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

    # 4. 后端路径重复（同 method+path 出现多次）
    path_counts = {}
    for m in backend:
        key = (m["method"], m["path"])
        path_counts[key] = path_counts.get(key, 0) + 1
    duplicates = [(k, v) for k, v in path_counts.items() if v > 1]
    if duplicates:
        errors.append("后端路径重复定义：")
        for (method, path), count in duplicates:
            errors.append(f"  {method} {path} ×{count}")

    # 结果
    print("\n比对结果：")
    if errors:
        print("FAIL：发现以下漂移")
        for e in errors:
            print(e)
        print("\n" + "=" * 70)
        sys.exit(1)
    else:
        print(f"  ✅ {len(backend)} 后端路径 × {len(frontend)} 前端路径 → 0 漂移")
        print("\nPASS：0 处漂移")
        sys.exit(0)


if __name__ == "__main__":
    main()
