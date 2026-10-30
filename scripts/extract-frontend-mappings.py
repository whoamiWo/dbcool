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

# 匹配 client.xxx('/path') / apiClient.xxx(`/path`)
# Wave0.5 A-8：此前正则要求方法名后直接跟 "("，而项目里大量调用带泛型参数
# （apiClient.post<{ code: number; data: ImPin }>('/im/messages/pins')），
# 这类调用全部未被收录 → 契约门禁形同虚设（Pin/阅后即焚正是这种写法）。
# 现支持可选泛型段：<...>。
PATH_RE = re.compile(
    r'(?:client|apiClient)\.(?:get|post|put|patch|delete)\s*(?:<[^>]*>)?\s*\(\s*[\'`]([^\'`]+)[\'`]'
)

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
                # Wave0.5 A-8：模板字面量路径（如 /im/messages/${id}/burn/read）此前被整段跳过，
                # 导致 Pin/阅后即焚/Checklist 这类真实断链逃过契约门禁。
                # 现在把 ${...} 归一化为 {}（与后端 @PathVariable 占位符一致）再纳入比对。
                path = re.sub(r'\$\{[^}]*\}', '{}', path)
                if path.startswith('/api/'):
                    # baseURL 已带 /api，重复前缀交由 api-contract-verify.py 报错
                    pass
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