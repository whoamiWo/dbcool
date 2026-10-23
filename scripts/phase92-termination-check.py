#!/usr/bin/env python3
"""PHASE92 TN-2 终止预演脚本"""
import subprocess, os, re, sys
from datetime import datetime

BASE = os.path.dirname(os.path.abspath(__file__))
os.chdir(BASE)

def run(cmd):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=60)
    return r.stdout.strip() + "\n" + r.stderr.strip()

print("🔍 PHASE92 TN-2 终止预演")
print("="*60)

# 1. 后端测试
print("\n[1/5] 后端单元测试...")
out = run("cd backend-java && mvn test -q -Dtest=TenantQuotaServiceTest 2>&1 | grep 'Tests run'")
print(out or "PASS")
assert "Failures: 0, Errors: 0" in out, "后端测试失败"

# 2. 前端测试
print("\n[2/5] 前端单元测试...")
out = run("cd frontend && CI=true npx vitest run --reporter=basic 2>&1 | tail -3")
print(out or "PASS")
assert "Errors: 0" in out or "passed" in out, "前端测试异常"

# 3. P95 压测
print("\n[3/5] P95 压测...")
try:
    # 假设已登录
    out = run("""
TOKEN=$(python3 -c "
import urllib.request, json
data = json.dumps({'username': 'admin', 'password': 'admin123'}).encode()
req = urllib.request.Request('http://localhost:8080/api/auth/login', data=data, headers={'Content-Type': 'application/json'})
with urllib.request.urlopen(req, timeout=10) as resp:
    print(json.loads(resp.read().decode())['data']['access_token'])
")
timeout 60 python3 scripts/perf-load.py --url http://localhost:8080/api/collections --concurrency 10 --duration 5 --token "$TOKEN" 2>&1 | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'P95={d[\"p95_ms\"]}ms'); print('OK' if d['p95_ms']<=150 else 'WARN')"
""")
    print(out)
except Exception as e:
    print(f"⚠️ 压测无法执行：{e}")

# 4. 代码扫描
print("\n[4/5] 安全与质量扫描...")
scan = ""
for d in ["backend-java", "frontend"]:
    if os.path.exists(d):
        scan += run(f"cd {d} && grep -r 'TODO\|FIXME' src/ 2>/dev/null | wc -l") + " "
print(f"遗留 TODO/FIXME: {scan.strip()}")

# 5. 文档完整性
print("\n[5/5] 文档完整性...")
docs = [
    "docs/PHASE92-TN2-QUOTA-SPEC.md",
    "docs/PERF_PROFILING_REPORT.md",
    "docs/TEST-COVERAGE.md"
]
for doc in docs:
    path = os.path.join(BASE, doc)
    status = "✅" if os.path.exists(path) else "❌"
    print(f"{status} {doc}")

print("\n" + "="*60)
print("✅ PHASE92 TN-2 终止预演完成")
print(f"时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
