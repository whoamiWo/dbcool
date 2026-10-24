#!/usr/bin/env python3
"""PHASE92 TN-2 终止预演脚本"""
import subprocess, os, sys, json, re
from datetime import datetime

BASE = "/home/who/multistack-project"
os.chdir(BASE)

def run(cmd, timeout=120):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout, cwd=BASE)
        return (r.stdout + r.stderr).strip()
    except Exception as e:
        return f"ERROR: {e}"

def get_token():
    import urllib.request
    data = json.dumps({'username': 'admin', 'password': 'admin123'}).encode()
    req = urllib.request.Request(
        'http://localhost:8080/api/auth/login',
        data=data,
        headers={'Content-Type': 'application/json'}
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode())['data']['access_token']

print("🔍 PHASE92 TN-2 终止预演")
print("="*60)

# 1. 后端测试（TenantQuotaServiceTest 15 个用例；不加 -q，避免 surefire 抑制 Tests run 行）
print("\n[1/5] 后端单元测试...")
out = run("cd backend-java && mvn test -Dtest=TenantQuotaServiceTest 2>&1 | grep -E 'Tests run:.*Failures'")
print(f"  {out}")
if "Failures: 0, Errors: 0" not in out:
    print("  ⚠️ 测试输出异常")

# 2. 前端测试
print("\n[2/5] 前端单元测试...")
out = run("cd frontend && CI=true npx vitest run 2>&1 | grep -E 'Test Files|Tests ' | tail -2")
print(f"  {out}")

# 3. P95 压测
print("\n[3/5] P95 压测 (30 VU)...")
try:
    token = get_token()
    cmd = f'timeout 60 python3 scripts/perf-load.py --url http://localhost:8080/api/collections --concurrency 30 --duration 10 --token "{token}"'
    out = run(cmd)
    # perf-load.py 输出多行 pretty-print JSON；用正则提取首 { 到末 } 子串再解析
    try:
        m = re.search(r'\{.*\}', out, re.DOTALL)
        d = json.loads(m.group(0)) if m else {}
        p95 = d.get('p95_ms', 0)
        print(f"  P95={p95}ms {'✅' if p95 <= 300 else '⚠️'}（阈值 ≤300ms）")
    except Exception as e:
        print(f"  ⚠️ 压测结果解析失败 {e}")
        print(f"  原始输出前 200 字符: {out[:200]}...")
except Exception as e:
    print(f"  ⚠️ 压测无法执行：{e}")

# 4. 代码扫描
print("\n[4/5] 安全与质量扫描...")
for d in ["backend-java", "frontend"]:
    n = run(f"cd {d} && grep -r 'TODO\\|FIXME' src/ 2>/dev/null | wc -l")
    print(f"  {d}: {n.strip()} 个 TODO/FIXME")

# 5. 文档完整性
print("\n[5/5] 文档完整性...")
docs = [
    "docs/PERF_PROFILING_REPORT.md", 
    "docs/CI_GATES.md",
    "docs/PHASE134_TERMINATION_PRECHECK.md"
]
for doc in docs:
    path = os.path.join(BASE, doc)
    status = "✅" if os.path.exists(path) else "❌"
    print(f"  {status} {doc}")

print("\n" + "="*60)
print("✅ PHASE92 TN-2 终止预演完成")
print(f"时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
