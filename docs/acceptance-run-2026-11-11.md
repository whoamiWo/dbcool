# 验收运行证据（2026-11-11 → 2026-11-12）

> 本文是受控证据索引（G1/D22）：每条结论都可通过「命令 + 输出摘要」复现。
> 原始日志在 `target/acceptance/evidence/<date>/`（gitignore，不入库）。
> 2026-11-11 的 `/tmp/kilo/*.log` 已丢失；本文不补写其内容，只记录可重跑项的真实输出。
> **2026-11-12 发现隔离栈跨栈网络逃逸事故（见 §4），2026-11-11 的所有门禁数字仅作历史参考，以 2026-11-12 重跑为准。**

## 1. 目标指纹

```
COMPOSE_PROJECT     = nocobase-acceptance
BASE_URL            = http://127.0.0.1:18082   (backend-java)
PYTHON_URL          = http://127.0.0.1:18000   (backend-python，仅容器内/本机)
PG_CONTAINER        = nocobase-acc-postgres
REDIS_CONTAINER     = nocobase-acc-redis
BACKUP_DIR          = /home/who/multistack-project/target/acceptance/backups（必须绝对路径）
BACKEND_SERVICE     = backend-java
PGDATABASE / port   = nocobase / 5432
Prometheus          = http://127.0.0.1:19090   Alertmanager = http://127.0.0.1:19093
```

镜像版本：`2.1.5`（四镜像：backend-java / backend-python / crdt-service / frontend）。
默认栈（`nocobase`，8080/5432）**不是**验收目标，任何指向它的输出都不是证据。

## 2. 迁移双轨

```
docker exec nocobase-acc-postgres psql -U nocobase -d nocobase \
  -tc "select count(*) total, count(*) filter (where success) ok, max(version) latest from flyway_schema_history"
→ total=83  ok=83  latest=V85（其中 81 行为纯数字版本，2 行为 repeatable）
```

V83（外部绑定/回调事件表）、V84（corp_id 回填 + 孤儿事件 fail-closed + PK 换 (corp_id,event_id) + oidc_login_code）、V85（`ADD COLUMN IF NOT EXISTS event_type/claimed_at`）均 success=t。
`event_type` 默认 `'finish'` 安全：`ApprovalCallbackEventService` 只消费 `instanceAction=finish` 的事件。
历史库若 V83 因 `CREATE TABLE IF NOT EXISTS` 跳过而缺列，由 V85 幂等补齐；**不得修改已应用的 V83/V84**（checksum 已记录）。

## 3. F5 preflight 正负向

命令模板（所有验收脚本共用 env 契约）：

```bash
ACCEPTANCE_MODE=1 BASE_URL=http://127.0.0.1:18082 \
PG_CONTAINER=nocobase-acc-postgres PG_STRICT=1 \
BACKUP_DIR=/home/who/multistack-project/target/acceptance/backups \
COMPOSE_PROJECT_NAME=nocobase-acceptance BACKEND_SERVICE=backend-java \
REDIS_CONTAINER=nocobase-acc-redis \
python3 scripts/acceptance-preflight.py
```

- 正向：`evidence/2026-11-12/preflight-positive.log` → exit=0，含 **E6 数据面同栈实证**
  （backend 与 PG 共享 Docker 网络 + backend 内 `getent hosts postgres` 解析到本栈 PG 容器 IP）。
- 负向（`evidence/2026-11-11/preflight-negative.log`，2026-11-12 新增 `preflight-negative-extra.log`）：

| 用例 | 期望 | 实测 |
|---|---|---|
| N1 缺 BASE_URL | exit=1 | ✅ |
| N2 指 8080 默认栈 | exit=1 | ✅ |
| N3 PG_CONTAINER=nocobase-postgres | exit=1 | ✅ |
| N4 BASE_URL 指前端 18080 | exit=1 | ✅ |
| N5 跨栈 COMPOSE_PROJECT_NAME | exit=1 | ✅ |
| N6 缺 REDIS_CONTAINER | exit=1 | ✅（新增） |
| N7 REDIS_CONTAINER=nocobase-redis | exit=1 | ✅（新增） |
| N8 BACKUP_DIR 相对路径 | exit=1 | ✅（新增） |

## 4. 2026-11-12 跨栈网络逃逸事故（数据面隔离失效 → 已修复）

**现象**：release 门禁「租户隔离」失败（tenantb_admin 登录 429），排查发现 acc-redis 无
ratelimit key、默认栈 redis 却有同名 key。

**根因**：`docker compose -f docker-compose.yml -f target/acceptance/compose.isolation.yml
-f docker-compose.observability.yml` 的文件顺序错误 —— observability overlay 末尾的
`networks.nocobase-net.name: nocobase-net` 把 isolation override 的网络名改回默认栈。
新 recreate 的 backend-java/backend-python 被接到默认栈 `nocobase-net`，服务名
`postgres`/`redis` 经 Docker DNS 解析到**默认栈**容器；验收期间产生的 audit_log、
refresh token、ratelimit key 全部写入默认库/默认 redis，而 E5（env 字符串比对）全部通过。
其余 9 个 acc 容器在正确的 `nocobase-acc-net` 上（事故仅影响两个被重建的 backend）。

**修复**（已提交 `release/2.1.5-prep`）：
1. 正确顺序：`-f docker-compose.yml -f docker-compose.observability.yml -f target/acceptance/compose.isolation.yml`（isolation override 必须最后）；`target/acceptance/compose.isolation.yml` 头部加事故警告注释。
2. `acceptance-preflight.py` 新增 `check_backend_same_network`（E6）：网络成员关系 + DNS 解析实证，读不到 PG 网络信息时 fail closed。
3. `gate-skip-selfcheck.py` 新增 4 项 E6 桩测试（正例/跨栈/DNS 漂移/fail-closed），全绿。

**验证**：修复后 `docker exec nocobase-acc-backend-java getent hosts postgres redis`
→ `172.20.0.9 postgres` / `172.20.0.12 redis`（本栈）；acc-postgres 出现 backend-java
连接与 ratelimit key 写入；默认库 `audit_log` 计数停止增长。

**默认栈残留**：事故期间默认库 audit_log 增至 270120 行、默认 redis 有 ratelimit/refresh
key。属测试流量写入，未擅自清理默认库；如需清理由运维按默认栈数据保留策略处理。

## 5. 业务门禁（2026-11-12 重跑，全部作用于隔离栈）

完整 release 门禁（16 项全 PASS，无 SKIP）：`evidence/2026-11-12/monthly-gate-release.log`

```
✅ 全部 16 项门禁通过（monthly-gate exit=0）
```

| 门禁 | 实测 | 关键输出 |
|---|---|---|
| 版本一致性 | exit=0 | 10 文件 + README 徽章一致（VERSION=2.1.5） |
| 契约 0 漂移 | exit=0 | 460 后端端点 × 137 前端调用 |
| 依赖漏洞审计 | exit=0 | java 10 条漏洞 0 条 HIGH/CRITICAL；node 0 条（trivy，DB 镜像 public.ecr.aws） |
| 后端 mvn -o verify | exit=0 | 2853 tests / 0F / 0E，JaCoCo BUNDLE 行覆盖 80.25% |
| 前端 npm run test:run | exit=0 | 121 files / 978 passed / 0 failed / 0 unhandled errors |
| 前端 tsc / eslint | exit=0 | 0 errors |
| Python pytest | exit=0 | 81 passed / 1 skipped（容器内 Python 3.12） |
| 冒烟 smoke.py | 14/14 | `evidence/2026-11-12/smoke-2.1.5.log`（最终 2.1.5 镜像复测） |
| 租户隔离 | 24/24 | `evidence/2026-11-12/tenant-isolation.log` |
| 归属写入静态门禁 | exit=0 | |
| 企业套件真实 PG (pg IT) | exit=0 | 47.9s |
| 企业套件 schema smoke | exit=0 | 迁移重放 |
| 备份/PITR 演练 | 30/30 | T1 真实备份 / T2 独立恢复 / T3 PITR / T4 副本 |
| 上线终验 launch-final | 8/8 | `evidence/2026-11-12/launch-final.log`（通过率：8/8） |
| 文档数字校验 | exit=0 | 28 锚点一致（`doc-numbers-verify.py` 无 --fix） |

上线终验排障中修复并提交的三处环境陷阱：
- `launch-final-verify.py` / `key-rotation-verify.py` 限流清理：ACCEPTANCE_MODE=1 未显式
  REDIS_CONTAINER 时 fail closed（此前默认清默认栈 redis，隔离栈 key 清不掉 → smoke 429）。
- preflight 强制 BACKUP_DIR 绝对路径（launch-final 以 `scripts/` 为 cwd，相对路径错位）。
- `security-gates.py scan-deps` 支持 `TRIVY_DB_REPOSITORY`：默认 mirror.gcr.io 在本环境
  SYN-SENT 超时卡死；改指 `public.ecr.aws/aquasecurity/trivy-db` 后真实完成扫描。

## 6. 告警 E2E 时间线

- 2026-11-11：停 backend-java 注入 → Prometheus `NocoBaseJavaDown` firing → Alertmanager
  → Python SQLite（`alerts` 表 `alertmanager.NocoBaseJavaDown`）+ 外部 webhook → 恢复后 resolved。
  当日原始日志已随 `/tmp` 丢失；SQLite 行仍在（`evidence/2026-11-12/alerts-db.txt`，
  status=resolved，startsAt=2026-11-12T02:11:15Z，endsAt=2026-11-12T03:05:15Z —— 2026-11-12
  凌晨容器重建时再次被 Alertmanager 补发并自动 resolved）。
- 2026-11-12 当前：`curl http://127.0.0.1:19090/api/v1/alerts` → 0 条 firing；
  `curl http://127.0.0.1:19093/api/v2/alerts` → 0 条
  （`evidence/2026-11-12/prometheus-alerts.json` / `alertmanager-alerts.json`）。

## 7. Python SIGSEGV 事故（2026-11-11 → 2026-11-12，已根因修复）

**现象**：默认栈与验收栈 backend-python 每 ~60s 各崩一次（`exit=139`，dmesg
`uvicorn[...]: segfault at 0 ip 0000000000000000 ... error 14 in python3.12`），
`/api/health/ready` 间歇 503（`redis 不健康: Event loop is closed`）。

**根因**：后台调度线程 `alert.cache_hit_rate_check`（60s）在 worker 线程直接
`asyncio.run(cache.should_warn_low_hit_rate())`，临时事件循环销毁后共享 async Redis
客户端绑定失效 → 主循环使用报错并在下一次巡检 SIGSEGV。

**修复**（commit 17c9f82，PR #6；已 cherry-pick 进 `release/2.1.5-prep` = f026329）：
- `services/scheduler.py`：`set_main_loop()` / `run_coro()`（主循环 running 时
  `asyncio.run_coroutine_threadsafe`，否则退化独立循环）。
- `main.py` lifespan 注册/注销主循环；`_reset_quotas` 用 `run_coro`。
- `redis_client.get_redis()` 事件循环亲和性守卫（旧循环已关闭/非当前循环时重建客户端）。
- 回归测试 `tests/test_scheduler_loop.py` 4 项全绿；本机全量 85 passed / 1 skipped。

**验证**：最终 2.1.5 镜像（含修复）部署后，跨多个 60s 巡检周期 0 次 `Event loop is closed`、
restarts=0。24h 观察窗口自 2026-11-12T04:30Z（最后一次计划内重建）重新计时。

## 8. 已知限制与未验证项

- **24h 观察窗口未到期**：基线 `evidence/2026-11-12/observation-baseline.txt`
  （2026-11-12T04:30Z 起），到期 2026-11-13T04:30Z 复核 RestartCount / StartedAt /
  firing 告警 / 5xx 后才算完成 W1。测试导致的计划内重建会重新计时。
- **GHCR push 未完成**：本机 gh token 无 `write:packages`（`permission_denied`），
  四镜像已本地构建（`evidence/2026-11-12/build-images.log`），digest 以本地
  `docker images --digests` 为准；多架构 manifest 需 buildx（本机无插件），
  `build-images.sh` 已改为无 buildx 时单架构经典 build+push、多架构明确拒绝。
- **分支保护（W4）未启用**：需仓库管理员操作；发布说明须标注。
- **外部集成（W6 / §13.2）未验证**：钉钉外推、飞书同步、OIDC 仍默认关闭；
  需真实测试企业/IdP 凭证，不得以「代码支持」充当通过。
- **设计语义**：ROW/CUSTOM 聚合 403 为设计行为；workflow 实例 tenant-wide 可读；
  历史 `created_by` 不自动回填（需 reindex）。
- **备份 API 经 java 代理（18082）返回 401**：直连 python（18000）正常；
  属 java 侧 `/api/admin/backup` 转发鉴权问题，未在本轮修复范围内，
  备份演练使用直连 python 触发 + `docker cp` 取出（命令见 §5 备份行日志）。
- **默认栈被事故写入的残留**（audit_log / refresh / ratelimit key）未清理，见 §4。
