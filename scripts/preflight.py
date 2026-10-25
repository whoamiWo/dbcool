#!/usr/bin/env python3
"""
部署前环境自检脚本
用法：python3 scripts/preflight.py [--json]
特性:
  - 检查 Docker 版本与守护进程
  - 检查宿主机内存/磁盘空间
  - 检查端口占用（避免冲突）
  - 检查镜像源连通性（maven/npm/pypi）
  - 检查证书占位文件
  - 检查构建产物（jar/dist）
  - 检查 .env 必填项
  - 输出 PASS/WARN/FAIL 表格，FAIL 时非 0 退出
"""

import json
import os
import platform
import socket
import subprocess
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.parent
ENV_FILE = PROJECT_ROOT / ".env"
ENV_EXAMPLE = PROJECT_ROOT / ".env.example"
CERTS_DIR = PROJECT_ROOT / "certs"
JAR_DIR = PROJECT_ROOT / "backend-java" / "target"
DIST_DIR = PROJECT_ROOT / "frontend" / "dist"

CYAN = "\033[36m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
RED = "\033[31m"
RESET = "\033[0m"

class CheckResult:
    def __init__(self, name, status, detail="", fix_hint=""):
        self.name = name
        self.status = status  # "PASS" | "WARN" | "FAIL"
        self.detail = detail
        self.fix_hint = fix_hint
    
    def is_pass(self):
        return self.status == "PASS"
    
    def is_fail(self):
        return self.status == "FAIL"

def colorize(status, text):
    if status == "PASS":
        return f"{GREEN}{text}{RESET}"
    elif status == "WARN":
        return f"{YELLOW}{text}{RESET}"
    else:
        return f"{RED}{text}{RESET}"

def run(cmd, shell=None, timeout=30):
    """
    运行命令并返回 (returncode, stdout, stderr)。
    若 cmd 为字符串，自动使用 shell=True（便于传递含参数/管道的命令）；
    若 cmd 为列表，使用 shell=False（更安全）。
    """
    if shell is None:
        shell = isinstance(cmd, str)
    try:
        result = subprocess.run(
            cmd,
            shell=shell,
            capture_output=True,
            text=True,
            timeout=timeout,
            env=os.environ.copy(),
        )
        return result.returncode, result.stdout.strip(), result.stderr.strip()
    except subprocess.TimeoutExpired:
        return 1, "", "超时"
    except FileNotFoundError as e:
        return 127, "", f"命令不存在: {e}"
    except Exception as e:
        return 1, "", str(e)

def check_docker():
    """检查 Docker 环境"""
    results = []
    
    # 检查 docker 命令
    rc, stdout, stderr = run("docker --version")
    if rc == 0:
        results.append(CheckResult("Docker 已安装", "PASS", stdout))
        
        # 检查 docker daemon
        rc2, stdout2, stderr2 = run("docker ps", timeout=5)
        if rc2 == 0:
            results.append(CheckResult("Docker daemon 运行中", "PASS"))
        else:
            results.append(CheckResult("Docker daemon 运行中", "FAIL", 
                f"docker ps 失败: {stderr2}", 
                "启动 Docker: sudo systemctl start docker"))
    else:
        results.append(CheckResult("Docker 已安装", "FAIL", 
            f"docker --version 失败: {stdout or stderr}",
            "安装 Docker: curl -fsSL https://get.docker.com | sh"))
    
    # 检查 docker compose
    rc, stdout, _ = run("docker compose version")
    if rc == 0:
        results.append(CheckResult("Docker Compose 可用", "PASS", stdout))
    else:
        results.append(CheckResult("Docker Compose 可用", "FAIL",
            "docker compose 命令不可用",
            "安装 docker-compose-plugin: sudo apt install docker-compose-plugin"))
    
    return results

def check_resources():
    """检查系统资源"""
    results = []
    
    # 检查内存
    try:
        import psutil
        mem = psutil.virtual_memory()
        total_gb = mem.total / (1024**3)
        available_gb = mem.available / (1024**3)
        
        if available_gb >= 2:
            results.append(CheckResult("可用内存", "PASS", f"{available_gb:.1f} GB 可用 (总计 {total_gb:.1f} GB)"))
        elif available_gb >= 1:
            results.append(CheckResult("可用内存", "WARN", f"{available_gb:.1f} GB 可用 (总计 {total_gb:.1f} GB)，建议 ≥2 GB"))
        else:
            results.append(CheckResult("可用内存", "FAIL", f"仅 {available_gb:.1f} GB 可用 (总计 {total_gb:.1f} GB)，至少需要 1 GB"))
    except ImportError:
        # psutil 未安装，尝试读取 /proc/meminfo
        rc, stdout, _ = run("grep MemAvailable /proc/meminfo | awk '{print $2/1024/1024}'")
        if rc == 0 and stdout:
            avail_gb = float(stdout)
            if avail_gb >= 2:
                results.append(CheckResult("可用内存", "PASS", f"{avail_gb:.1f} GB 可用"))
            else:
                results.append(CheckResult("可用内存", "FAIL", f"仅 {avail_gb:.1f} GB 可用"))
        else:
            # 备用: free -h 输出解析
            rc2, stdout2, _ = run("free -h | awk '/Mem:/ {print $7}'")
            if rc2 == 0 and stdout2:
                avail_str = stdout2.strip().replace('GiB', 'g').replace('MiB', 'm')
                try:
                    avail_gb = float(avail_str)
                    if 'g' in avail_str.lower():
                        avail_gb *= 1024
                    results.append(CheckResult("可用内存", "PASS" if avail_gb >= 2 else "WARN", f"{avail_str} 可用"))
                except ValueError:
                    results.append(CheckResult("可用内存", "WARN", f"无法解析: {avail_str}", "请手动确认 ≥2 GB"))
            else:
                results.append(CheckResult("可用内存", "WARN", "无法检测内存，请手动确认 ≥2 GB"))
    
    # 检查磁盘空间
    try:
        stat = os.statvfs(PROJECT_ROOT)
        free_gb = (stat.f_bavail * stat.f_frsize) / (1024**3)
        if free_gb >= 10:
            results.append(CheckResult("可用磁盘空间", "PASS", f"{free_gb:.1f} GB 可用"))
        elif free_gb >= 5:
            results.append(CheckResult("可用磁盘空间", "WARN", f"{free_gb:.1f} GB 可用，建议 ≥10 GB"))
        else:
            results.append(CheckResult("可用磁盘空间", "FAIL", f"仅 {free_gb:.1f} GB 可用，至少需要 5 GB"))
    except Exception as e:
        results.append(CheckResult("可用磁盘空间", "WARN", f"无法检测: {e}"))
    
    return results

def check_ports():
    """检查端口占用"""
    results = []
    
    # 需要检查的关键端口
    ports_to_check = {
        "前端": 80,
        "前端 TLS": 443,
        "Java 后端": 8080,
        "Python 后端": 8000,
        "PostgreSQL": 5432,
        "Redis": 6379,
        "MinIO": 9000,
        "RabbitMQ": 5672,
        "Grafana": 3000,
        "Prometheus": 9090,
    }
    
    # 获取正在运行的 nocobase 容器及其绑定的宿主机端口
    running_ports = set()
    try:
        rc, stdout, _ = run("docker ps --filter 'name=nocobase-' --format '{{.Ports}}'")
        if rc == 0 and stdout:
            for line in stdout.splitlines():
                ports_str = line.strip()
                # 解析端口映射: "8080/tcp, 0.0.0.0:8080->8080/tcp" 或 "127.0.0.1:9000-9001->9000-9001/tcp"
                for mapping in ports_str.split(', '):
                    if '->' in mapping:
                        # 提取 "->" 前的部分
                        before_arrow = mapping.split('->')[0]
                        # 提取 IP:PORT 或 PORT（去掉 IP 前缀）
                        host_part = before_arrow.split(':')[-1]
                        # 处理端口范围: "9000-9001" -> [9000, 9001]
                        if '-' in host_part:
                            parts = host_part.split('-')
                            if len(parts) == 2 and parts[0].isdigit() and parts[1].isdigit():
                                for p in range(int(parts[0]), int(parts[1]) + 1):
                                    running_ports.add(p)
                        elif host_part.isdigit():
                            running_ports.add(int(host_part))
    except Exception:
        pass
    
    conflicts = []
    external_conflicts = []
    for name, port in ports_to_check.items():
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(1)
        result = sock.connect_ex(('127.0.0.1', port))
        sock.close()
        if result == 0:
            # 判断是否是 nocobase stack 自身占用的端口
            is_ours = port in running_ports
            if is_ours:
                results.append(CheckResult(f"端口 {port}", "PASS", f"{name} 已被本地 stack 使用"))
            else:
                conflicts.append(f"{name}:{port}")
                external_conflicts.append(f"{name}:{port}")
    
    if external_conflicts:
        results.append(CheckResult("外部端口冲突", "FAIL", 
            f"以下端口被非本 stack 进程占用: {', '.join(external_conflicts)}",
            "停止占用端口的进程，或修改 docker-compose.yml 中的端口映射"))
    elif conflicts:
        results.append(CheckResult("端口占用", "PASS", f"所有关键端口可用（{len(conflicts)} 个被本地 stack 占用）"))
    else:
        results.append(CheckResult("端口占用", "PASS", "所有关键端口可用"))
    
    return results

def check_image_sources():
    """检查镜像源连通性"""
    results = []
    
    sources = [
        ("npm", "https://registry.npmmirror.com"),
        ("PyPI", "https://pypi.org"),
        ("Docker Hub", "https://hub.docker.com"),
    ]
    
    unreachable = []
    for name, url in sources:
        # 使用 curl -sI 直接检测 HTTP 头，不依赖管道（管道会掩盖 rc）
        rc, stdout, stderr = run(f"curl -sI --max-time 5 {url}", timeout=6)
        # curl 成功时 rc=0 且 stdout 包含 HTTP 状态码
        if rc == 0 and stdout and ("HTTP" in stdout or "Location" in stdout):
            results.append(CheckResult(f"{name} 连通性", "PASS"))
        elif rc == 0 and not stdout:
            # 可能有重定向但无响应头
            results.append(CheckResult(f"{name} 连通性", "WARN", f"无响应头"))
        else:
            results.append(CheckResult(f"{name} 连通性", "WARN",
                f"检测失败: {stderr or '无响应'}"))
            unreachable.append(name)
    
    if unreachable:
        results.append(CheckResult("镜像源连通性", "WARN",
            f"以下镜像源检测失败: {', '.join(unreachable)}",
            "配置镜像代理或检查网络"))
    else:
        results.append(CheckResult("镜像源连通性", "PASS"))
    
    return results

def check_env_requirements():
    """检查 .env 必填项"""
    results = []
    
    # 检查 .env 是否存在
    if not ENV_FILE.exists():
        results.append(CheckResult(".env 文件", "FAIL",
            f".env 不存在 ({ENV_FILE})",
            "运行: python3 scripts/init-env.py"))
        return results
    
    # 读取 .env
    env_content = ENV_FILE.read_text()
    
    # 必填变量（基于 compose 中的 :? 语法）
    required_vars = [
        "POSTGRES_PASSWORD",
        "MINIO_ROOT_USER",
        "MINIO_ROOT_PASSWORD",
        "JWT_SECRET",
        "RABBITMQ_USER",
        "RABBITMQ_PASSWORD",
        "INTEGRATION_CREDENTIAL_MASTER_KEY",
    ]
    
    missing = []
    for var in required_vars:
        # 检查变量是否在 .env 中且非空
        found = False
        for line in env_content.splitlines():
            line = line.strip()
            if line.startswith("#"):
                continue
            if line.startswith(var + "="):
                value = line.split("=", 1)[1].strip()
                if value and not value.startswith("CHANGEME"):
                    found = True
                break
        
        if not found:
            missing.append(var)
    
    if missing:
        results.append(CheckResult(".env 必填项", "FAIL",
            f"缺少或无效变量: {', '.join(missing)}",
            "运行: python3 scripts/init-env.py"))
    else:
        results.append(CheckResult(".env 必填项", "PASS", f"{len(required_vars)} 项全部满足"))
    
    return results

def check_certificates():
    """检查证书文件"""
    results = []
    
    fullchain = CERTS_DIR / "fullchain.pem"
    privkey = CERTS_DIR / "privkey.pem"
    
    if not fullchain.exists() or not privkey.exists():
        results.append(CheckResult("证书文件", "FAIL",
            f"证书文件不存在 ({CERTS_DIR}/)",
            "运行: bash scripts/gen-selfsigned-cert.sh"))
    else:
        results.append(CheckResult("证书文件", "PASS",
            f"{fullchain.name} + {privkey.name} 已存在"))
        
        # 检查证书有效期
        rc, stdout, _ = run(f"openssl x509 -in {fullchain} -checkend 86400 -noout")
        # checkend 86400: 如果 24 小时内过期则失败
        if rc == 0:
            results.append(CheckResult("证书有效期", "PASS"))
        else:
            results.append(CheckResult("证书有效期", "WARN",
                "证书即将过期或无效",
                "运行: bash scripts/gen-selfsigned-cert.sh"))
    
    return results

def check_build_artifacts():
    """检查构建产物"""
    results = []
    
    # Java jar
    jars = list(JAR_DIR.glob("*.jar")) if JAR_DIR.exists() else []
    if jars:
        largest_jar = max(jars, key=lambda f: f.stat().st_size)
        results.append(CheckResult("Java 构建产物", "PASS",
            f"{largest_jar.name} ({largest_jar.stat().st_size // (1024*1024)} MB)"))
    else:
        results.append(CheckResult("Java 构建产物", "FAIL",
            f"未找到 jar 文件 ({JAR_DIR}/)",
            "运行: make build-java"))
    
    # Frontend dist
    if DIST_DIR.exists() and any(DIST_DIR.rglob("*.html")):
        html_files = list(DIST_DIR.glob("*.html"))
        if html_files:
            results.append(CheckResult("前端构建产物", "PASS",
                f"{len(html_files)} 个 HTML 文件"))
        else:
            results.append(CheckResult("前端构建产物", "WARN",
                "dist 目录存在但无 HTML 文件"))
    else:
        results.append(CheckResult("前端构建产物", "FAIL",
            f"未找到 dist 目录 ({DIST_DIR})",
            "运行: make build-frontend"))
    
    return results

def check_compose_files():
    """检查 compose 文件"""
    results = []
    
    compose_files = [
        PROJECT_ROOT / "docker-compose.yml",
        PROJECT_ROOT / "docker-compose.observability.yml",
    ]
    
    for cf in compose_files:
        if cf.exists():
            results.append(CheckResult(f"Compose 文件: {cf.name}", "PASS"))
        else:
            results.append(CheckResult(f"Compose 文件: {cf.name}", "WARN",
                f"文件不存在 ({cf})",
                "该文件可选，缺失时跳过对应功能"))
    
    return results

def main():
    print()
    print(f"{CYAN}{'='*60}{RESET}")
    print(f"{CYAN}  部署前环境自检 (Phase 9 Production Ready){RESET}")
    print(f"{CYAN}{'='*60}{RESET}")
    print()
    
    checks = [
        ("Docker 环境", check_docker),
        ("系统资源", check_resources),
        ("端口占用", check_ports),
        ("镜像源连通性", check_image_sources),
        (".env 配置", check_env_requirements),
        ("证书文件", check_certificates),
        ("构建产物", check_build_artifacts),
        ("Compose 文件", check_compose_files),
    ]
    
    all_results = []
    for name, check_func in checks:
        try:
            results = check_func()
            all_results.extend(results)
        except Exception as e:
            all_results.append(CheckResult(name, "WARN", f"检查异常: {e}"))
    
    # 输出结果表格
    print(f"{CYAN}{'检查项':<25} {'状态':<8} {'详情':<50}{RESET}")
    print(f"{CYAN}{'-'*90}{RESET}")
    
    has_fail = False
    for result in all_results:
        status_padded = f"{result.status:<8}"
        colored_status = colorize(result.status, status_padded)
        if result.is_fail():
            has_fail = True
            print(f"{result.name:<25} {colored_status} {result.detail[:50]}")
            if result.fix_hint:
                print(f"  → {CYAN}修复建议:{RESET} {result.fix_hint}")
        else:
            print(f"{result.name:<25} {colored_status} {result.detail[:50]}")
    
    print()
    
    # 汇总
    fail_count = sum(1 for r in all_results if r.is_fail())
    warn_count = sum(1 for r in all_results if r.status == "WARN")
    pass_count = sum(1 for r in all_results if r.status == "PASS")
    
    print(f"{CYAN}汇总:{RESET} {pass_count} PASS, {warn_count} WARN, {fail_count} FAIL")
    
    if has_fail:
        print()
        print(f"{RED}❌ 自检未通过，请修复上述问题后重新运行{RESET}")
        return 1
    else:
        print()
        print(f"{GREEN}✅ 自检通过，可以开始部署{RESET}")
        return 0

if __name__ == "__main__":
    # 支持 --json 输出
    if "--json" in sys.argv:
        results = []
        for name, check_func in [
            ("Docker 环境", check_docker),
            ("系统资源", check_resources),
            ("端口占用", check_ports),
            (".env 配置", check_env_requirements),
            ("证书文件", check_certificates),
            ("构建产物", check_build_artifacts),
            ("Compose 文件", check_compose_files),
        ]:
            try:
                check_results = check_func()
                results.extend([
                    {"name": r.name, "status": r.status, "detail": r.detail, "fix_hint": r.fix_hint}
                    for r in check_results
                ])
            except Exception as e:
                results.append({"name": name, "status": "ERROR", "detail": str(e), "fix_hint": ""})
        
        print(json.dumps({"results": results}, indent=2, ensure_ascii=False))
        sys.exit(1 if any(r["status"] == "FAIL" for r in results) else 0)
    else:
        sys.exit(main())
