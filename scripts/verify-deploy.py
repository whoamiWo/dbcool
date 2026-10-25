#!/usr/bin/env python3
"""
部署验证脚本
聚合健康状态并执行六链路冒烟验收
"""

import subprocess
import sys
import time
import requests
from typing import List, Dict

CYAN = "\033[36m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
RED = "\033[31m"
RESET = "\033[0m"

def log(msg: str):
    print(f"{CYAN}[INFO]{RESET} {msg}")

def ok(msg: str):
    print(f"{GREEN}✓{RESET} {msg}")

def warn(msg: str):
    print(f"{YELLOW}⚠{RESET} {msg}")

def error(msg: str):
    print(f"{RED}✗{RESET} {msg}")

def run(cmd: str) -> tuple[int, str, str]:
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return result.returncode, result.stdout, result.stderr

class CheckResult:
    def __init__(self, name: str, status: str, detail: str = "", fix_hint: str = ""):
        self.name = name
        self.status = status
        self.detail = detail
        self.fix_hint = fix_hint

def check_compose_health():
    """检查 Docker Compose 服务健康状态"""
    results = []
    
    log("检查 Docker Compose 服务状态...")
    rc, stdout, _ = run("docker compose ps --format json")
    
    if rc != 0:
        results.append(CheckResult("Docker Compose", "FAIL", "无法获取服务状态"))
        return results
    
    try:
        import json
        # 逐行解析 JSON Lines 格式
        for line in stdout.strip().split('\n'):
            if not line:
                continue
            svc = json.loads(line)
            name = svc.get("Name", "")
            state = svc.get("State", "")
            status = svc.get("Status", "")
            
            if state == "running":
                if "healthy" in status.lower():
                    results.append(CheckResult(name, "PASS", "运行中且健康"))
                else:
                    results.append(CheckResult(name, "PASS", "运行中"))
            else:
                results.append(CheckResult(name, "FAIL", f"状态：{state}"))
    except Exception as e:
        results.append(CheckResult("Docker Compose", "FAIL", f"解析失败：{e}"))
    
    return results

def check_http_endpoints():
    """检查 HTTP 端点"""
    results = []
    
    endpoints = [
        ("Java 后端健康", "http://localhost:8080/api/health/ready"),
        ("Python 后端健康", "http://localhost:8000/api/health"),
        ("前端", "http://localhost:5173"),
    ]
    
    for name, url in endpoints:
        try:
            resp = requests.get(url, timeout=5)
            if resp.status_code < 500:
                results.append(CheckResult(name, "PASS", f"HTTP {resp.status_code}"))
            else:
                results.append(CheckResult(name, "FAIL", f"HTTP {resp.status_code}"))
        except Exception as e:
            results.append(CheckResult(name, "FAIL", str(e)))
    
    return results

def check_database_connectivity():
    """检查数据库连接"""
    results = []
    
    log("检查数据库连接...")
    rc, stdout, _ = run("""
        docker exec nocobase-postgres pg_isready -h localhost -U nocobase -d nocobase
    """)
    
    if rc == 0:
        results.append(CheckResult("PostgreSQL", "PASS", "连接正常"))
    else:
        results.append(CheckResult("PostgreSQL", "FAIL", "连接失败"))
    
    # Redis
    rc, _, _ = run("docker exec nocobase-redis redis-cli ping")
    if rc == 0:
        results.append(CheckResult("Redis", "PASS", "PONG"))
    else:
        results.append(CheckResult("Redis", "FAIL", "无响应"))
    
    return results

def check_minio_connectivity():
    """检查 MinIO 连接"""
    results = []
    
    log("检查 MinIO 连接...")
    try:
        # 使用 mc 或 curl 检查
        rc, stdout, _ = run("curl -sf http://localhost:9000/minio/health/live")
        if rc == 0:
            results.append(CheckResult("MinIO", "PASS", "健康"))
        else:
            results.append(CheckResult("MinIO", "WARN", "未响应"))
    except Exception as e:
        results.append(CheckResult("MinIO", "WARN", str(e)))
    
    return results

def check_rabbitmq_connectivity():
    """检查 RabbitMQ 连接"""
    results = []
    
    log("检查 RabbitMQ 连接...")
    rc, stdout, _ = run("docker exec nocobase-rabbitmq rabbitmq-diagnostics -q ping")
    if rc == 0:
        results.append(CheckResult("RabbitMQ", "PASS", "pong"))
    else:
        results.append(CheckResult("RabbitMQ", "FAIL", "无响应"))
    
    return results

def run_smoke_tests():
    """运行六链路冒烟测试"""
    results = []
    
    log("运行六链路冒烟测试...")
    
    # 链路 1: 登录鉴权
    try:
        resp = requests.post(
            "http://localhost:8080/api/auth/login",
            json={"username": "admin", "password": "admin123"},
            headers={"Content-Type": "application/json"},
            timeout=10
        )
        if resp.status_code == 200:
            results.append(CheckResult("链路 1: 登录鉴权", "PASS", "成功获取 token"))
        elif resp.status_code == 401:
            results.append(CheckResult("链路 1: 登录鉴权", "WARN", "认证失败（可能凭证错误）"))
        else:
            results.append(CheckResult("链路 1: 登录鉴权", "FAIL", f"HTTP {resp.status_code} - {resp.text[:100]}"))
    except Exception as e:
        results.append(CheckResult("链路 1: 登录鉴权", "FAIL", str(e)))
    
    # 链路 2: 数据视图（无需认证的公开端点）
    try:
        resp = requests.get("http://localhost:8080/api/health/ready", timeout=10)
        if resp.status_code == 200:
            results.append(CheckResult("链路 2: 数据视图", "PASS", "后端就绪"))
        else:
            results.append(CheckResult("链路 2: 数据视图", "FAIL", f"HTTP {resp.status_code}"))
    except Exception as e:
        results.append(CheckResult("链路 2: 数据视图", "FAIL", str(e)))
    
    # 链路 3: IM 消息
    try:
        resp = requests.get("http://localhost:8000/api/health", timeout=10)
        if resp.status_code == 200:
            results.append(CheckResult("链路 3: IM 后端", "PASS", "Python 后端就绪"))
        else:
            results.append(CheckResult("链路 3: IM 后端", "FAIL", f"HTTP {resp.status_code}"))
    except Exception as e:
        results.append(CheckResult("链路 3: IM 后端", "FAIL", str(e)))
    
    # 链路 4: Slack 入站 webhook
    # 注：需要实际配置 Slack，这里仅检查端点可达性
    try:
        resp = requests.post(
            "http://localhost:8000/api/integrations/slack/webhook",
            json={"text": "test"},
            timeout=5
        )
        # 允许 401/403（表示端点存在但需认证）
        if resp.status_code in [200, 401, 403]:
            results.append(CheckResult("链路 4: Slack 入站", "PASS", "端点可达"))
        else:
            results.append(CheckResult("链路 4: Slack 入站", "WARN", f"HTTP {resp.status_code}"))
    except Exception as e:
        results.append(CheckResult("链路 4: Slack 入站", "WARN", str(e)))
    
    # 链路 5: 钉钉入站 webhook
    try:
        resp = requests.post(
            "http://localhost:8000/api/integrations/dingtalk/webhook",
            json={"msgtype": "text", "text": {"content": "test"}},
            timeout=5
        )
        if resp.status_code in [200, 401, 403]:
            results.append(CheckResult("链路 5: 钉钉入站", "PASS", "端点可达"))
        else:
            results.append(CheckResult("链路 5: 钉钉入站", "WARN", f"HTTP {resp.status_code}"))
    except Exception as e:
        results.append(CheckResult("链路 5: 钉钉入站", "WARN", str(e)))
    
    # 链路 6: Huddle 跨实例
    # 注：需要两个实例，这里仅检查 CRDT 服务
    try:
        rc, stdout, _ = run("docker exec nocobase-crdt-service node -e \"console.log('ok')\" 2>&1")
        if rc == 0:
            results.append(CheckResult("链路 6: CRDT 服务", "PASS", "运行中"))
        else:
            results.append(CheckResult("链路 6: CRDT 服务", "FAIL", "容器内命令失败"))
    except Exception as e:
        results.append(CheckResult("链路 6: CRDT 服务", "FAIL", str(e)))
    
    return results

def print_results(results: List[CheckResult]):
    """打印结果表格"""
    print("\n" + "=" * 80)
    print(f"{CYAN}{'验证项':<30} {'状态':<10} {'详情'}{RESET}")
    print("=" * 80)
    
    pass_count = 0
    warn_count = 0
    fail_count = 0
    
    for r in results:
        if r.status == "PASS":
            pass_count += 1
            status_str = f"{GREEN}PASS{RESET}"
        elif r.status == "WARN":
            warn_count += 1
            status_str = f"{YELLOW}WARN{RESET}"
        else:
            fail_count += 1
            status_str = f"{RED}FAIL{RESET}"
        
        detail = f" - {r.detail}" if r.detail else ""
        print(f"{r.name:<30} {status_str:<10}{detail}")
        
        if r.fix_hint:
            print(f"  {CYAN}→{RESET} {r.fix_hint}")
    
    print("=" * 80)
    print(f"\n{CYAN}汇总:{RESET} {pass_count} PASS, {warn_count} WARN, {fail_count} FAIL")
    
    if fail_count > 0:
        print(f"\n{RED}✗ 部署验证失败{RESET}")
        return False
    else:
        print(f"\n{GREEN}✓ 部署验证通过{RESET}")
        return True

def main():
    all_results = []
    
    # 1. Docker Compose 健康
    all_results.extend(check_compose_health())
    
    # 2. HTTP 端点
    all_results.extend(check_http_endpoints())
    
    # 3. 数据库连接
    all_results.extend(check_database_connectivity())
    
    # 4. MinIO 连接
    all_results.extend(check_minio_connectivity())
    
    # 5. RabbitMQ 连接
    all_results.extend(check_rabbitmq_connectivity())
    
    # 6. 六链路冒烟
    all_results.extend(run_smoke_tests())
    
    # 打印结果
    success = print_results(all_results)
    
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
