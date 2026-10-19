# PHASE100 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE100_GLM53_COMPOSE_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE100：Docker Compose 运维加固（🔒-SaaS-P0）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 部署形态：**Docker Compose 单机**（已确认，本批只做 Compose 路径，`k8s/` 不在范围内）
- 你的工作目录即仓库根目录

### 项目环境速查

| 事项 | 正确做法 |
|---|---|
| 看容器状态 | `docker compose ps` |
| 看日志 | `docker compose logs <service> 2>&1 \| tail -50`（**Postgres 等容器日志走 stderr**） |
| 重启服务 | `docker compose up -d --no-build <service>` |
| **改了 compose 环境变量** | **必须重建容器**才生效（不是 restart） |
| 跑后端测试 | `cd backend-java && mvn -o test`（离线） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 一键冒烟 | `python3 scripts/smoke.py` |
| 查库 | `docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A -c "<SQL>"` |
| 敏感配置 | 一律放 `.env`（已 gitignore，**禁止提交**） |

---

## §1 为什么做这一项

来自 `SAAS_LAUNCH_ASSESSMENT.md` §3.6 / §3.7（2026-10-09 实测）。

**默认密钥可以直接带上线**：`deploy.sh:60-64` 检测到 `.env` 仍是 `dev_password` 时**只 warn 不阻断**。配合 `docker-compose.yml` 里 `POSTGRES_PASSWORD:-dev_password`、`MINIO_ROOT_PASSWORD:-minio123`、RabbitMQ `guest/guest`、JWT 默认密钥（compose 与 `application.yml` 双处硬编码），意味着生产很可能就是带这些口令在跑。

**数据库端口对公网敞开 + 全站无 TLS**：`docker-compose.yml:31-32,48-49,68-70,94-96` 把 Postgres/Redis/MinIO/RabbitMQ 全部 `0.0.0.0:` 发布；`frontend/nginx.conf:10` 只有 `listen 80`。对外 SaaS 里这是可以直接被拖库的配置。

**出问题无人知晓**：Compose 全栈无资源限制、无日志轮转、无日志聚合、无 Prometheus 抓取、无告警通知渠道、无 traceId —— 故障只能靠用户投诉发现。

## §2 现状（实测）

| 项 | 现状 |
|---|---|
| 资源限制 | ❌ 无 `mem_limit`/`cpus`/`deploy.resources` |
| 日志轮转 | ❌ 无 `logging:`，stdout 无限增长 |
| 端口暴露 | ❌ Postgres/Redis/MinIO/RabbitMQ 全部 `0.0.0.0:` |
| 默认密钥 | ❌ `dev_password`/`minio123`/`guest:guest`/`dev_jwt_secret...` 双处硬编码 |
| 部署阻断 | ❌ `deploy.sh:60-64` 只 warn |
| TLS | ❌ 仅 80；`/health` 直接 `return 200`（假阳性） |
| traceId | ❌ `MdcFilter.java:33-41` 只有 tenantId/userId；Python 无结构化日志 |
| 告警 | ❌ `services/alerts.py:8-9,17` 纯内存 deque，`NOCOBASE_ALERTS_PATH=/tmp/alerts.db` |
| 部署健康检查 | ❌ `deploy.sh:77` 永不返回非 0，无回滚、无镜像 tag |

## §3 任务

### T1（P0）默认密钥必须阻断，不能只 warn

`deploy.sh` 检测到默认口令/`.env` 缺失关键项 → **exit 1**；`docker-compose.yml` 去掉 `${X:-弱口令}` 默认值兜底；`.env.example` 只放变量名与占位说明；JWT 密钥不再硬编码在 `application.yml`。

```bash
cp .env.example .env && bash deploy.sh prod ; echo "exit=$?"    # 期望非 0
grep -c "dev_password\|minio123\|dev_jwt_secret" docker-compose.yml backend-java/src/main/resources/application.yml
# 期望 0
```

### T2（P0）端口收敛

Postgres/Redis/MinIO/RabbitMQ 改 `127.0.0.1:<port>:<port>`，对外只留 80/443。

```bash
ss -lntp | grep -E ':(5432|6379|9000|9001|15672)\b'   # 不得出现 0.0.0.0
```

### T3（P0）TLS

`nginx.conf` 加 443 + 证书（路径走环境变量/挂载，**不入库**）、80 跳 443、HSTS；`/health` 改**真实探活**（探后端，不是 `return 200`）。

验收：`curl -I https://<域名>` 200 且有 `Strict-Transport-Security`；后端挂掉时 `/health` 非 200。

### T4（P0）资源限制与日志轮转

每服务加 `mem_limit`/`cpus`（或 `deploy.resources.limits`）+ `logging: json-file` 与 `max-size`/`max-file`。

验收：`docker inspect <容器>` 能看到 `Memory` 限制与 `LogConfig.max-size`。

### T5（P0）traceId / requestId 贯通

`MdcFilter.java:33-41` 增加 `traceId`（无上游时生成，有 `X-Request-ID` 时沿用），响应头回写；Python 侧加同样 requestId 与结构化日志。

```bash
docker compose logs backend-java 2>&1 | grep <traceId>
docker compose logs backend-python 2>&1 | grep <traceId>   # 两条都必须命中
```

### T6（P0）告警必须有真实接收渠道

`services/alerts.py` 的纯内存 deque 改**持久化 + 可外发**（至少 webhook），落盘到命名卷（不再 `/tmp`）。最小可用：备份失败、服务不可用、5xx 突增三类能真正发出。

验收：**故意让备份调度失败**，5 分钟内在接收端看到告警（贴出实际收到的内容）。

### T7（P1）Compose 下可观测性最小集

加 Prometheus 抓取 Java `/actuator/prometheus`；至少 3 条告警规则（服务 down、5xx 比例、P99 延迟），参照 `k8s/06-monitoring.yaml:38-101` 移植。**不要**为了这个引入 OTel collector —— 要么部署 collector，要么明确关掉 OTel（`application.yml:110-114` 采样 0.1 但无 collector，trace 静默丢弃）。

### T8（P1）部署可回滚与可追溯

`deploy.sh:77` 健康检查必须返回非 0；支持回滚到上一个已知可用版本；镜像/构建产物带 tag（不用浮动 latest）。

验收：故意部署坏版本 → 脚本非 0 退出 → 一键回滚成功。

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/smoke.py` | 全通 |
| `docker compose ps` | 全部 healthy |

## §5 红线（违反即打回）

1. **禁止把真实密钥/证书写进仓库** —— 一律 `.env` 或挂载
2. **禁止"只 warn 不阻断"** —— 不合规配置必须让部署失败
3. **禁止 `return 200` 的假探活**
4. **禁止恒真断言** —— 每条验收必须能失败（"故意部署坏版本必须非 0 退出"）
5. **禁止留下静默丢弃** —— 告警发不出要显式报错；trace 没 collector 就关掉 OTel
6. **禁止在演练中破坏生产数据** —— 恢复类操作一律独立容器
7. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 退出码 + `grep` 计数为 0
2. T2 `ss -lntp` 输出
3. T3 TLS/HSTS 输出 + 后端挂掉时 `/health` 非 200
4. T4 `docker inspect` 片段
5. T5 同一 traceId 的 Java/Python 两条日志
6. T6 实际收到的告警内容
7. T7 抓取与告警规则 + OTel 决策与理由
8. T8 坏版本非 0 退出 + 回滚成功
9. 门禁实测数字 + commit hash + `git status`（干净且已推送）
10. **遗留项**（强制，不得省略）

## §7 可复用

- 告警规则参考：`k8s/06-monitoring.yaml:38-101`
- 日志栈参考：`k8s/07-logging.yaml`（Loki 存 `/tmp` 是反面教材，不要照抄）
- 既有 MDC：`backend-java/src/main/java/com/nocobase/auth/MdcFilter.java`
- 一键冒烟：`python3 scripts/smoke.py`

-----END PROMPT-----
