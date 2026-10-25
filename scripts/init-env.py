#!/usr/bin/env python3
"""
环境初始化脚本：从 .env.example 生成 .env 并填充强密钥
用法：python3 scripts/init-env.py
特性:
  - 幂等：.env 已存在时不覆盖
  - 自动生成所有密钥类变量 (POSTGRES_PASSWORD, MINIO_ROOT_PASSWORD, RABBITMQ_PASSWORD, JWT_SECRET, INTEGRATION_CREDENTIAL_MASTER_KEY)
  - 使用 openssl rand 生成随机字节
  - 生成后打印各密钥的指纹 (SHA256 前 16 字符)，不暴露明文
"""

import os
import subprocess
import hashlib
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.parent
ENV_EXAMPLE = PROJECT_ROOT / ".env.example"
ENV_FILE = PROJECT_ROOT / ".env"

CYAN = "\033[36m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
RESET = "\033[0m"

def log(msg):
    print(f"{CYAN}[init-env]{RESET} {msg}")

def ok(msg):
    print(f"{GREEN}✅{RESET} {msg}")

def warn(msg):
    print(f"{YELLOW}⚠️{RESET} {msg}")

def err(msg):
    print(f"❌ {msg}")
    sys.exit(1)

def generate_random_base64(length_bytes=32):
    """使用 openssl rand -base64 生成随机字符串"""
    try:
        result = subprocess.run(
            ["openssl", "rand", "-base64", str(length_bytes)],
            capture_output=True,
            text=True,
            check=True
        )
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        err(f"openssl rand 失败: {e.stderr}")
    except FileNotFoundError:
        err("openssl 未安装，请先安装 openssl")

def compute_fingerprint(value):
    """计算值的 SHA256 指纹 (前 16 字符)"""
    return hashlib.sha256(value.encode()).hexdigest()[:16]

def parse_template(template_content):
    """解析 .env.example，识别需要填充的变量"""
    variables = {}
    for line in template_content.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        
        # 判断是否需要生成密钥
        needs_generation = False
        if "PASSWORD" in key.upper() and value.startswith("CHANGEME"):
            needs_generation = True
        elif "SECRET" in key.upper() and value.startswith("CHANGEME"):
            needs_generation = True
        elif "KEY" in key.upper() and value.startswith("CHANGEME"):
            needs_generation = True
        elif "USER" in key.upper() and value.startswith("CHANGEME"):
            needs_generation = True
        
        variables[key] = {
            "current": value,
            "needs_generation": needs_generation
        }
    
    return variables

def main():
    log("开始环境初始化")
    
    # 检查 .env.example 是否存在
    if not ENV_EXAMPLE.exists():
        err(f".env.example 不存在：{ENV_EXAMPLE}")
    
    # 检查 .env 是否已存在
    if ENV_FILE.exists():
        warn(f".env 已存在：{ENV_FILE}")
        response = input("是否覆盖？(y/N): ").strip().lower()
        if response != "y":
            ok("跳过，保留现有 .env")
            return
        log("将覆盖现有 .env")
    
    # 读取模板
    template_content = ENV_EXAMPLE.read_text()
    variables = parse_template(template_content)
    
    # 生成新内容
    new_lines = []
    generated_keys = {}
    
    for line in template_content.splitlines():
        stripped = line.strip()
        
        # 保留注释和空行
        if not stripped or stripped.startswith("#"):
            new_lines.append(line)
            continue
        
        if "=" not in stripped:
            new_lines.append(line)
            continue
        
        key, value = stripped.split("=", 1)
        key = key.strip()
        value = value.strip()
        
        if key in variables and variables[key]["needs_generation"]:
            # 生成新值
            if "PASSWORD" in key.upper():
                # 密码：至少 16 字节
                new_value = generate_random_base64(24)
            elif "SECRET" in key.upper() or "KEY" in key.upper():
                # 密钥：至少 32 字节
                new_value = generate_random_base64(32)
            elif "USER" in key.upper():
                # 用户名：用随机 base64 去掉特殊字符
                raw = generate_random_base64(16)
                new_value = "".join(c for c in raw if c.isalnum())
            else:
                # 默认：24 字节
                new_value = generate_random_base64(24)
            
            generated_keys[key] = new_value
            new_lines.append(f"{key}={new_value}")
        else:
            new_lines.append(line)
    
    # 写入 .env
    try:
        ENV_FILE.write_text("\n".join(new_lines) + "\n")
        ok(f".env 已生成：{ENV_FILE}")
    except Exception as e:
        err(f"写入 .env 失败：{e}")
    
    # 打印密钥指纹 (不暴露明文)
    print()
    print(f"{CYAN}生成的密钥指纹 (SHA256 前 16 字符):{RESET}")
    for key, value in generated_keys.items():
        fingerprint = compute_fingerprint(value)
        print(f"  {key}: {fingerprint}")
    
    print()
    ok("环境初始化完成!")
    print(f"{CYAN}下一步:{RESET} 运行 './deploy.sh preflight' 进行环境自检")

if __name__ == "__main__":
    main()
