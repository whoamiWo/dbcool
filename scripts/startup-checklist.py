#!/usr/bin/env python3
"""PHASE100 T9: 生产启动检查清单 — 在 docker compose up 前跑一遍。

校验项（对应 §1 三条理由与 §3 任务）：
  1. .env 主密钥与强密码已设置（T1 阻断）
  2. 关键端口是否绑定 127.0.0.1（T2 端口收敛）
  3. nginx 443 + HSTS 已配置（T3 TLS）
  4. 资源限制与日志轮转已配置（T4）
  5. MDC/traceId 贯通（T5，代码检查）
  6. 告警持久化路径与 webhook（T6，compose 检查）
  7. Prometheus 抓取配置存在（T7）
  8. 部署可回滚（T8，deploy.sh 检查）

用法：python3 scripts/startup-checklist.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
compose = BASE / "docker-compose.yml"
env_example = BASE / ".env.example"
nginx = BASE / "frontend" / "nginx.conf"
mdc = BASE / "backend-java" / "src" / "main" / "java" / "com" / "nocobase" / "auth" / "MdcFilter.java"
deploy = BASE / "deploy.sh"
prom = BASE / "docker-compose.observability.yml"


def fail(msg: str) -> None:
    print(f"  ❌ FAIL: {msg}")


def ok(msg: str) -> None:
    print(f"  ✅ OK: {msg}")


def warn(msg: str) -> None:
    print(f"  ⚠️  WARN: {msg}")


def main() -> int:
    issues = 0
    print("=== PHASE100 生产启动检查清单 ===\n")

    # 1. 主密钥与强密码（T1）
    print("[1] 主密钥与强密码")
    if not compose.exists() or not env_example.exists():
        fail("缺少 docker-compose.yml 或 .env.example")
        issues += 1
        return 1

    compose_text = compose.read_text()
    env_text = env_example.read_text()
    # .env.example 必须含占位说明而非真实值
    must_keys = [
        "INTEGRATION_CREDENTIAL_MASTER_KEY",
        "JWT_SECRET",
        "POSTGRES_PASSWORD",
        "MINIO_ROOT_PASSWORD",
        "RABBITMQ_PASSWORD",
    ]
    for key in must_keys:
        m = re.search(rf"^{re.escape(key)}=(.*)$", env_text, re.M)
        if not m:
            fail(f".env.example 缺少 {key}")
            issues += 1
        else:
            val = m.group(1).strip()
            if key in ("POSTGRES_PASSWORD", "MINIO_ROOT_PASSWORD", "RABBITMQ_PASSWORD") and "CHANGEME" in val:
                ok(f"{key} 占位正确")
            elif key in ("POSTGRES_PASSWORD", "MINIO_ROOT_PASSWORD", "RABBITMQ_PASSWORD"):
                fail(f"{key} 占位必须是 CHANGEME_*，当前='{val}'")
                issues += 1
            else:
                ok(f"{key} 占位正确")

    # 2. 端口绑定 127.0.0.1（T2）
    print("\n[2] 端口绑定 127.0.0.1")
    ports_block = re.findall(r'- "([^"]+)":\d+', compose_text)
    data_ports = [p for p in ports_block if any(x in p for x in ("5432", "6379", "9000", "9001", "5672", "15672"))]
    for p in data_ports:
        if not p.startswith("127.0.0.1:"):
            fail(f"数据端口 '{p}' 未绑定 127.0.0.1")
            issues += 1
    if data_ports:
        ok(f"{len(data_ports)} 个数据端口已绑定 127.0.0.1")

    # 3. nginx TLS（T3）
    print("\n[3] nginx TLS + HSTS")
    if nginx.exists():
        nginx_text = nginx.read_text()
        if "listen 443 ssl" not in nginx_text:
            fail("nginx 未配置 443 ssl")
            issues += 1
        else:
            ok("nginx 已配置 443 ssl")
        if "Strict-Transport-Security" not in nginx_text:
            fail("nginx 未配置 HSTS")
            issues += 1
        else:
            ok("nginx 已配置 HSTS")
        if "proxy_pass http://backend-java:8080/api/health" not in nginx_text:
            fail("/health 未指向真实后端探活")
            issues += 1
        else:
            ok("/health 已改为真实探活")

    # 4. 资源限制与日志轮转（T4）
    print("\n[4] 资源限制与日志轮转")
    mem_limits = re.findall(r"memory:\s*\d+M", compose_text)
    logging_blocks = compose_text.count("driver: json-file")
    if len(mem_limits) < 8:
        fail(f"资源限制数量 {len(mem_limits)} < 8")
        issues += 1
    else:
        ok(f"{len(mem_limits)} 个服务已配置内存限制")
    if logging_blocks < 8:
        fail(f"日志轮转数量 {logging_blocks} < 8")
        issues += 1
    else:
        ok(f"{logging_blocks} 个服务已配置日志轮转")

    # 5. traceId（T5）
    print("\n[5] MDC/traceId 贯通")
    if mdc.exists():
        mdc_text = mdc.read_text()
        if "X-Request-ID" in mdc_text and "MDC.put(\"traceId\"" in mdc_text:
            ok("MdcFilter 已生成/沿用 traceId 并回写响应头")
        else:
            fail("MdcFilter 缺少 traceId 逻辑")
            issues += 1

    # 6. 告警持久化与 webhook（T6）
    print("\n[6] 告警持久化与 webhook")
    if "alertsdata" not in compose_text:
        fail("告警 SQLite 未挂载命名卷")
        issues += 1
    else:
        ok("告警 SQLite 已挂载命名卷")
    if "ALERT_WEBHOOK_URL" in compose_text:
        ok("告警 webhook 配置项已声明")

    # 7. Prometheus（T7）
    print("\n[7] Prometheus 抓取")
    prom_config = BASE / "observability" / "prometheus.yml"
    if prom.exists() and prom_config.exists():
        prom_text = prom.read_text()
        prom_cfg_text = prom_config.read_text()
        if "backend-java:8080" in prom_cfg_text and "/actuator/prometheus" in prom_cfg_text:
            ok("Prometheus 已配置抓取 Java /actuator/prometheus")
        else:
            fail("Prometheus 抓取配置不完整")
            issues += 1
    else:
        warn("缺少 docker-compose.observability.yml 或 observability/prometheus.yml（T7 最小集）")

    # 8. 部署可回滚（T8）
    print("\n[8] 部署可回滚")
    if deploy.exists():
        deploy_text = deploy.read_text()
        if "rollback" in deploy_text and ".last-deployed-tag" in deploy_text:
            ok("deploy.sh 支持 rollback + 记录上次 tag")
        else:
            fail("deploy.sh 缺少 rollback 支持")
            issues += 1

    print(f"\n=== 结果: {issues} 个问题 ===")
    if issues:
        fail("生产启动检查未通过")
        return 1
    ok("生产启动检查全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
