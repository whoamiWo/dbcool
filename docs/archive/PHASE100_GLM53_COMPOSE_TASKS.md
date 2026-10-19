# PHASE100 任务书：Docker Compose 运维加固（🔒-SaaS-P0）

> 选题依据：`SAAS_LAUNCH_ASSESSMENT.md` §3.6（P0-6 默认密钥 + 全端口 + 无 TLS）、§3.7（P0-7 可观测性为零）
> 部署形态已确认为 **Docker Compose 单机** —— 本批只做 Compose 路径，`k8s/` 不在范围内（其短板另案）。

---

## §1 为什么做这个（三条理由）

### 1.1 默认密钥可以直接带上线

`deploy.sh:60-64` 检测到 `.env` 仍是 `dev_password` 时**只 warn 不阻断**。
配合 `docker-compose.yml` 里 `POSTGRES_PASSWORD:-dev_password`、`MINIO_ROOT_PASSWORD:-minio123`、RabbitMQ `guest/guest`、JWT 默认密钥（compose 与 `application.yml` **双处硬编码**），意味着**生产环境很可能就是带着这些口令在跑**。

### 1.2 数据库端口对公网敞开 + 全站无 TLS

`docker-compose.yml:31-32,48-49,68-70,94-96` 把 Postgres / Redis / MinIO / RabbitMQ 全部 `0.0.0.0:` 发布；
`frontend/nginx.conf:10` 只有 `listen 80`。对外 SaaS 里这是可以直接被拖库的配置。

### 1.3 出问题无人知晓

Compose 全栈无资源限制、无日志轮转、无日志聚合、无 Prometheus 抓取、无告警通知渠道、无 traceId。
**故障只能靠用户投诉发现。**

## §2 现状（实测）

| 项 | 现状 |
|---|---|
| 资源限制 | ❌ compose 全文无 `mem_limit`/`cpus`/`deploy.resources` |
| 日志轮转 | ❌ 无 `logging:` 配置，stdout 无限增长 |
| 端口暴露 | ❌ Postgres/Redis/MinIO/RabbitMQ 全部 `0.0.0.0:` |
| 默认密钥 | ❌ `dev_password` / `minio123` / `guest:guest` / `dev_jwt_secret...` 双处硬编码 |
| 部署阻断 | ❌ `deploy.sh:60-64` 只 warn |
| TLS | ❌ `nginx.conf:10` 仅 80；`/health` 直接 `return 200`（假阳性探活） |
| actuator / Swagger | 部分由 PHASE95 T4 处理，本批**复核** |
| traceId | ❌ `MdcFilter.java:33-41` 只有 tenantId/userId；Python 侧无结构化日志 |
| 告警 | ❌ `services/alerts.py:8-9,17` 纯内存 deque，`NOCOBASE_ALERTS_PATH=/tmp/alerts.db`，重启即丢 |
| 日志聚合 | ❌ Compose 路径无（仅 k8s 有 `07-logging.yaml`，且 Loki 存 `/tmp`） |
| 部署健康检查 | ❌ `deploy.sh:77` `curl ... && echo ✅ \|\| echo ❌` 永不返回非 0，无回滚、无镜像 tag |

## §3 八项任务

### T1（P0）默认密钥必须阻断，不能只 warn

- `deploy.sh` 检测到仍是默认口令（或 `.env` 缺失关键项）时 **exit 1**
- `docker-compose.yml` 去掉 `${X:-弱口令}` 形式的**默认值兜底**（缺变量应直接失败，而不是静默用弱口令）
- 提供 `.env.example`，**只放变量名与占位说明，不放真实口令**
- JWT 密钥不再硬编码在 `application.yml`（走环境变量；dev 可用 `KeyRingService` 既有机制）

**验收（必须会失败）**：
```bash
# 故意保留默认口令执行部署，必须非 0 退出并明确告知哪一项不合规
cp .env.example .env && bash deploy.sh prod ; echo "exit=$?"    # 期望非 0
grep -c "dev_password\|minio123\|dev_jwt_secret" docker-compose.yml backend-java/src/main/resources/application.yml
# 期望 0
```

### T2（P0）端口收敛

Postgres / Redis / MinIO / RabbitMQ 改为 `127.0.0.1:<port>:<port>`（或仅在需要时暴露），对外只留 80/443。

```bash
ss -lntp | grep -E ':(5432|6379|9000|9001|15672)\b'   # 不得出现 0.0.0.0
```

### T3（P0）TLS

- `frontend/nginx.conf` 增加 443 + 证书配置、80 跳 443、HSTS
- 证书路径走环境变量/挂载（**不入库**）
- `/health` 改为**真实探活**（探后端，不是 `return 200`）

验收：`curl -I https://<域名>` 返回 200 且有 `Strict-Transport-Security`；后端挂掉时 `/health` 返回非 200。

### T4（P0）资源限制与日志轮转

- 每个服务加 `mem_limit` / `cpus`（或 `deploy.resources.limits`）
- 加 `logging: driver: json-file` + `max-size` + `max-file`

**验收**：`docker inspect <容器>` 能看到 `Memory` 限制与 `LogConfig` 的 `max-size`；写满日志的模拟测试下文件数被限制。

### T5（P0）traceId / requestId 贯通

- `MdcFilter.java:33-41` 增加 `traceId`（无上游时生成，有 `X-Request-ID` 时沿用）
- 响应头回写 `X-Request-ID`
- Python 侧加同样的 requestId 与结构化日志（至少 KV 格式，含 requestId）

**验收**：取一次跨栈请求，Java 与 Python 日志能用同一个 ID grep 到（贴出两条日志）。
```bash
docker compose logs backend-java 2>&1 | grep <traceId>
docker compose logs backend-python 2>&1 | grep <traceId>   # 必须都有
```

### T6（P0）告警必须有真实接收渠道

- `services/alerts.py` 的纯内存 deque 改为**持久化 + 可外发**（至少 webhook；告警落盘到命名卷，不再 `/tmp`）
- 最小可用：备份失败、服务不可用、5xx 突增三类告警能真正发到配置的通知渠道
- 验收：**故意让备份调度失败**，5 分钟内在接收端看到告警（贴出实际收到的内容）

### T7（P1）Compose 下可观测性最小集

- 加 Prometheus 抓取 Java `/actuator/prometheus`（Compose 服务 + 抓取配置）
- 至少 3 条告警规则：服务 down、5xx 比例、P99 延迟（参照 `k8s/06-monitoring.yaml:38-101` 已有规则，移植到 Compose 可用形式）
- **不要**为了这个引入 OTel collector（当前 `application.yml:110-114` 采样 0.1 但无 collector，trace 静默丢弃）——要么部署 collector，要么明确关掉 OTel 避免误导

### T8（P1）部署可回滚与可追溯

- `deploy.sh:77` 的健康检查必须**返回非 0**
- 支持回滚到上一个已知可用版本（镜像 tag 或代码 commit）
- 镜像/构建产物带 tag（不使用浮动 latest）

验收：故意部署一个坏版本，脚本必须非 0 退出且能一键回滚到上一个版本。

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| 一键冒烟 | `python3 scripts/smoke.py` 全通 |
| `docker compose ps` | 服务全部 healthy |

## §5 红线（违反即打回）

1. **禁止把真实密钥/证书写进仓库** —— 一律 `.env`（已 gitignore）或挂载
2. **禁止"只 warn 不阻断"** —— 不合规配置必须让部署失败
3. **禁止用 `return 200` 的假探活** —— 探活必须反映真实后端状态
4. **禁止恒真断言** —— 每条验收必须能失败（例如"故意部署坏版本必须非 0 退出"）
5. **禁止留下静默丢弃** —— 告警发不出去要显式报错，trace 没 collector 就关掉 OTel
6. **禁止在演练中破坏生产数据** —— 涉及恢复的操作一律在独立容器进行
7. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 默认口令阻断的实际退出码 + `grep` 计数为 0
2. T2 `ss -lntp` 输出（无 `0.0.0.0` 暴露）
3. T3 `curl -I` 的 TLS/HSTS 输出 + 后端挂掉时 `/health` 非 200
4. T4 `docker inspect` 的资源与日志配置片段
5. T5 同一次请求的 Java/Python 两条日志（同一 traceId）
6. T6 实际收到的告警内容（备份失败演练）
7. T7 抓取与告警规则（是否部署 collector / 是否关闭 OTel 的决策与理由）
8. T8 坏版本部署非 0 退出 + 回滚成功
9. 门禁实测数字 + commit hash + `git status`（干净且已推送）
10. **遗留项**（强制，不得省略）

## §7 可复用（别重造）

- 告警规则参考：`k8s/06-monitoring.yaml:38-101`（5 条规则，移植到 Compose 可用形式）
- 日志栈参考：`k8s/07-logging.yaml`（注意其 Loki 存 `/tmp` 是反面教材）
- 既有 MDC：`backend-java/src/main/java/com/nocobase/auth/MdcFilter.java`
- 一键冒烟：`python3 scripts/smoke.py`
- **提醒**：改了 compose 环境变量**必须重建容器**才生效
