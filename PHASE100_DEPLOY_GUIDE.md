# PHASE100 部署运维指南（Docker Compose 单机）

> 对应批次：`PHASE100_GLM53_COMPOSE_TASKS.md`（P0-6 默认密钥 / P0-7 可观测性）
> 前置：`SAAS_LAUNCH_ASSESSMENT.md` §3.6 / §3.7

---

## 1. 部署前检查（必做）

```bash
python3 scripts/startup-checklist.py
```

逐项校验：主密钥与强密码、端口绑定 127.0.0.1、nginx TLS + HSTS、资源限制与日志轮转、
traceId 贯通、告警持久化与 webhook、Prometheus 抓取、部署可回滚。**有 FAIL 不要部署。**

## 2. 环境变量

`.env` 从 `.env.example` 复制，**所有 `CHANGEME_*` 必须替换**：

| 变量 | 生成方式 | 未配置后果 |
|---|---|---|
| `POSTGRES_PASSWORD` | `openssl rand -base64 24` | compose 启动失败（`:?` 校验） |
| `MINIO_ROOT_PASSWORD` | `openssl rand -base64 24` | 同上 |
| `RABBITMQ_PASSWORD` | `openssl rand -base64 24` | 同上 |
| `JWT_SECRET` | `openssl rand -base64 32` | 使用默认值，生产不安全 |
| `INTEGRATION_CREDENTIAL_MASTER_KEY` | `openssl rand -base64 32` | **后端启动失败**（PHASE99 红线 1） |
| `ALERT_WEBHOOK_URL` | 告警接收端 URL | 告警仅落盘不外发，启动日志明确提示 |

## 3. 部署与回滚

```bash
# 生产部署（含默认口令阻断 + 健康检查非 0 退出）
./deploy.sh prod

# 回滚到上一个已知可用版本
./deploy.sh rollback
```

`deploy.sh prod` 会：
1. 检测 `.env` 含 `dev_password` / `minio123` / `guest` → **exit 1**
2. 检测 `INTEGRATION_CREDENTIAL_MASTER_KEY` 未配置 → **exit 1**
3. 记录本次镜像 tag 到 `.last-deployed-tag`
4. 健康检查失败 → **exit 1**（不再 `|| echo ❌` 掩盖）

## 4. TLS

`frontend/nginx.conf` 已配 443 + HSTS + 80 跳 443。证书需挂载到容器：

```yaml
# docker-compose.yml 的 frontend 服务追加
volumes:
  - /etc/letsencrypt/live/你的域名/fullchain.pem:/etc/nginx/certs/fullchain.pem:ro
  - /etc/letsencrypt/live/你的域名/privkey.pem:/etc/nginx/certs/privkey.pem:ro
```

证书申请：`certbot certonly --standalone -d 你的域名`

`/health` 已改为**真实探活**（代理到 `backend-java:8080/api/health`），后端挂掉返回 502。

### 4.1 证书自动化（PHASE110）

用 `scripts/cert-lifecycle.sh` 代替手工 certbot 调用：

```bash
# 首次签发（ACME，需 80 端口可达）
./scripts/cert-lifecycle.sh issue 你的域名

# 手动续期
./scripts/cert-lifecycle.sh renew 你的域名

# 到期检查（供 cron 每日执行 / 部署前校验；剩余 ≤30 天告警，过期则非 0 退出）
./scripts/cert-lifecycle.sh check
```

退出码：`0` 有效 / `1` 失败或已过期 / `2` 即将过期（≤30 天）。
**前置条件**：certbot 已安装、域名已解析、80 端口可达（ACME HTTP-01）、nginx 容器已运行。
`issue` / `renew` 需真实域名环境，**本地无法实测**（PHASE110 已如实登记）。

建议加每日 cron：
```cron
0 3 * * * /path/to/multistack-project/scripts/cert-lifecycle.sh check
```

## 5. 可观测性最小集

```bash
# 叠加 Prometheus（抓取 Java /actuator/prometheus + 3 条告警规则）
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
```

| 文件 | 用途 |
|---|---|
| `observability/prometheus.yml` | 抓取配置（30s 间隔） |
| `observability/alerts.yml` | 告警规则：服务 down / 5xx > 5% / P99 > 3s |
| `docker-compose.observability.yml` | Prometheus 服务定义（端口仅 127.0.0.1） |

**OTel 决策**：Compose 下未部署 collector，`application.yml` 的采样概率默认改为 **0**（显式关闭）。
需要分布式追踪时，先部署 collector 再设 `TRACING_SAMPLING_RATE=0.1`。
（此前默认 0.1 指向不存在的 `localhost:4318`，span 被静默丢弃 —— 红线 5）

## 6. 告警

- **持久化**：`NOCOBASE_ALERTS_PATH=/data/alerts.db`（命名卷 `nocobase-alertsdata`），重启不丢
- **外发**：配 `ALERT_WEBHOOK_URL` 后，告警 emit 即推送到 webhook（支持 generic/feishu/slack）
- **未配 webhook**：启动日志打印明确提示「告警仅落盘不外发」，不静默

## 7. 端口收敛

| 服务 | 绑定 |
|---|---|
| Postgres 5432 | `127.0.0.1:5432` |
| Redis 6379 | `127.0.0.1:6379` |
| MinIO 9000/9001 | `127.0.0.1:9000` / `127.0.0.1:9001` |
| RabbitMQ 5672/15672 | `127.0.0.1:5672` / `127.0.0.1:15672` |
| Java 8080 | `${JAVA_PORT:-8080}`（经 nginx 反代，不对公网） |
| 前端 80/443 | `${FRONTEND_PORT:-80}` |

## 8. 资源限制与日志轮转

每个服务均配 `deploy.resources.limits.memory`（Java 1G / Python 768M / 其余 512M）
与 `logging: json-file` + `max-size: 10m` + `max-file: 3`。

## 9. traceId 贯通

- Java：`MdcFilter` 沿用 `X-Request-ID` 或生成 16 位 hex，写入 MDC 与响应头
- Python：`trace_id_middleware` 同样逻辑，`nocobase_py.main.get_trace_id()` 可取

```bash
docker compose logs backend-java 2>&1 | grep <traceId>
docker compose logs backend-python 2>&1 | grep <traceId>
```

## 10. 门禁

| 门禁 | 命令 | 基线 |
|---|---|---|
| 后端单测 | `cd backend-java && mvn -o test` | > 1411 |
| 前端单测 | `cd frontend && npm run test:run` | ≥ 382 |
| 类型检查 | `cd frontend && npx tsc --noEmit` | 0 errors |
| E2E | `cd frontend && npx playwright test` | ≥ 135 |
| Python | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed / 1 skipped |
| 备份演练 | `python3 scripts/backup-e2e-verify.py` | 30 / 30 |
| 管理员鉴权 | `python3 scripts/admin-authz-e2e-verify.py` | 18 / 18 |
| 越权回归 | `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS |
| 契约比对 | `python3 scripts/api-contract-verify.py` | 0 漂移 |
| SaaS 就绪 | `python3 scripts/saas-readiness-verify.py` | 11/11 |
| 冒烟 | `python3 scripts/smoke.py` | 11/11 |
| 生产检查 | `python3 scripts/startup-checklist.py` | 全 PASS |
| 依赖漏洞扫描（离线替代） | `python3 scripts/java-dependency-audit.py` | 0 高危 |
| 密钥轮换验证 | `python3 scripts/key-rotation-verify.py` | 3/3 断言 |
| 配额端到端 | `python3 scripts/quota-e2e-verify.py` | 5/5 |

## 11. 运维闭环（PHASE110）

| 主题 | 入口 |
|---|---|
| 证书签发/续期/到期 | `scripts/cert-lifecycle.sh`（§4.1） |
| 密钥轮换（JWT/DB/MinIO/MQ/集成主密钥） | `docs/key-rotation-runbook.md` + `scripts/key-rotation-verify.py` |
| 灰度放量 + 快速回滚 | `docs/release-rollout-runbook.md` + `./deploy.sh rollback` |
| 上线前全量门禁 | `scripts/launch-final-verify.py`（8 项串行） |

**当前上线结论**：对外 SaaS **仍不可上线**，唯一阻断项为 P0-3
（AI 会话删除假实现，见 `docs/PHASE110_UPGRADE_REPORT.md` §5）。其余 P0 均已实测修复。
