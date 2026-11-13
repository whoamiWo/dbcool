# 验收运行证据（2026-11-11 → 2026-11-13）

> 本文是受控证据索引（G1/D22）：每条结论都可通过「命令 + 输出摘要」复现。
> 原始日志已脱敏提交到 `docs/evidence/<date>/`（`target/` 仍为 gitignore 的工作副本）。
> 2026-11-11 的 `/tmp/kilo/*.log` 已丢失；本文不补写其内容，只记录可重跑项的真实输出。
> **2026-11-12 发现隔离栈跨栈网络逃逸事故（见 §4）；2026-11-13 在最终镜像 revision=712efb6 上重跑，16/16 PASS。2026-11-11 的数字仅作历史参考。**

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

镜像版本：`2.1.5`，OCI label `org.opencontainers.image.revision=712efb64709fdda5b7eb1a6095433efdce774cb2`
（四镜像：backend-java / backend-python / crdt-service / frontend；容器 image ID 与镜像 ID 逐一相等，见 §7 与 `docs/evidence/2026-11-12/observation-baseline.txt`）。
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

- 正向：`docs/evidence/2026-11-12/preflight-positive.log` → exit=0，含 **E6 数据面同栈实证**
  （backend 与 **PG 及 Redis** 共享 Docker 网络 + backend 内 `getent hosts <compose service>`
  解析到本栈容器 IP；service 名取自 `com.docker.compose.service` label，不硬编码）。
  Java 与 Python 两个后端的 DNS 实测见 `docs/evidence/2026-11-12/e6-network-check.txt`。
- 负向（`docs/evidence/2026-11-11/preflight-negative.log`，2026-11-12 新增 `preflight-negative-extra.log`）：

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
2. `acceptance-preflight.py` 扩展 `check_backend_same_network`（E6）：对 PG 与 Redis 都做网络成员关系 + DNS 解析实证；service 名取自 compose label；`getent` 缺失/解析失败/读不到网络或 IP 一律 fail closed。
3. `gate-skip-selfcheck.py` 扩展 E6 桩测试到 6 项（正例/PG 跨栈/Redis 跨栈/DNS 漂移/缺 getent/fail-closed），全绿；自测总计 35 条断言。

**验证**：修复后 `docker exec nocobase-acc-backend-java getent hosts postgres redis`
→ `172.20.0.9 postgres` / `172.20.0.12 redis`（本栈）；acc-postgres 出现 backend-java
连接与 ratelimit key 写入；默认库 `audit_log` 计数停止增长。

**默认栈残留与处置**（2026-11-12T23:52Z，详见 `docs/evidence/2026-11-12/default-stack-residue.txt`）：
- 事故窗口（02:22Z 前后）默认库 `audit_log` 新增 16 行（总数 270104→270120）、`users` 新增 1 行
  （`smoke_user_2654e98c`）、`approval_request` 新增 1 行；默认 redis `refresh:*` 481 个。
- 已按测试用户 ID 吊销 refresh token（`48016fec-…`：删除 `refresh:{token}` 与
  `refresh:user:{userId}` 索引，481→479），未执行 `FLUSHDB`。`ratelimit:*` 当时已自然过期（0 个）。
- `audit_log` 16 行、测试用户、审批行**全部保留未删**；是否删除/隔离由默认栈 owner 决定（计划 D3）。
  未执行 `DELETE FROM audit_log`。

## 5. 业务门禁（2026-11-13 最终镜像重跑，全部作用于隔离栈）

完整 release 门禁（16 项全 PASS，无 SKIP）：`docs/evidence/2026-11-12/monthly-gate-release.log`

```
✅ 全部 16 项门禁通过（monthly-gate exit=0）
```

| 门禁 | 实测 | 关键输出 |
|---|---|---|
| 版本一致性 | exit=0 | 10 文件 + README 徽章一致（VERSION=2.1.5） |
| 契约 0 漂移 | exit=0 | 460 后端端点 × 137 前端调用 |
| 依赖漏洞审计 | exit=0 | java 10 条漏洞 0 条 HIGH/CRITICAL；node 0 条（trivy，DB 镜像 public.ecr.aws） |
| 后端 mvn -o verify | exit=0 | 2853 tests / 0F / 0E，JaCoCo BUNDLE 行覆盖 80.27% |
| 前端 npm run test:run | exit=0 | 121 files / 978 passed / 0 failed / 0 unhandled errors |
| 前端 tsc / eslint | exit=0 | 0 errors |
| Python pytest | exit=0 | 85 passed / 1 skipped（门禁在本机 Python 3.14 执行；运行时镜像为 Python 3.12；含 `test_scheduler_loop.py` 4 项） |
| 冒烟 smoke.py | 14/14 | `docs/evidence/2026-11-12/smoke-2.1.5.log`（最终 2.1.5 镜像复测） |
| 租户隔离 | 24/24 | `docs/evidence/2026-11-12/tenant-isolation.log` |
| 归属写入静态门禁 | exit=0 | |
| 企业套件真实 PG (pg IT) | exit=0 | 47.9s |
| 企业套件 schema smoke | exit=0 | 迁移重放 |
| 备份/PITR 演练 | 30/30 | T1 真实备份 / T2 独立恢复 / T3 PITR / T4 副本 |
| 上线终验 launch-final | 8/8 | 通过率 8/8（输出见 `monthly-gate-release.log` 的「上线终验」段） |
| 文档数字校验 | exit=0 | 28 锚点一致（`doc-numbers-verify.py` 无 --fix） |

上线终验排障中修复并提交的环境陷阱：
- `launch-final-verify.py` / `key-rotation-verify.py` 限流清理：ACCEPTANCE_MODE=1 未显式
  REDIS_CONTAINER 时 fail closed（此前默认清默认栈 redis，隔离栈 key 清不掉 → smoke 429）。
- preflight 强制 BACKUP_DIR 绝对路径（launch-final 以 `scripts/` 为 cwd，相对路径错位）。
- `security-gates.py scan-deps` 支持 `TRIVY_DB_REPOSITORY`：默认 mirror.gcr.io 在本环境
  SYN-SENT 超时卡死；改指 `public.ecr.aws/aquasecurity/trivy-db` 后真实完成扫描。
- `launch-final-verify.py` 备份网关检查：先清隔离栈限流（避免把 429 误判 401），并拒绝
  `kid != k-0` 的 token —— 2.1.5 默认禁用轮换，若仍出现 `k-1` 说明旧镜像/旧配置，
  必须显式报错，而不是继续 POST 得到误导性 401。

## 6. 告警 E2E 时间线

- 2026-11-11：停 backend-java 注入 → Prometheus `NocoBaseJavaDown` firing → Alertmanager
  → Python SQLite（`alerts` 表 `alertmanager.NocoBaseJavaDown`）+ 外部 webhook → 恢复后 resolved。
  当日原始日志已随 `/tmp` 丢失；SQLite 行仍在（`docs/evidence/2026-11-12/alerts-db.txt`，
  status=resolved，startsAt=2026-11-12T02:11:15Z，endsAt=2026-11-12T03:05:15Z —— 2026-11-12
  凌晨容器重建时再次被 Alertmanager 补发并自动 resolved）。
- 2026-11-13T00:27Z 当前：`curl http://127.0.0.1:19090/api/v1/alerts` → 0 条 firing；
  `curl http://127.0.0.1:19093/api/v2/alerts` → 0 条
  （`docs/evidence/2026-11-12/prometheus-alerts.json` / `alertmanager-alerts.json`）。

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

**验证**：最终 2.1.5 镜像（revision=712efb6，含修复，OCI label 可证，见
`docs/evidence/2026-11-12/image-provenance.txt`）于 2026-11-13T00:00Z 重新部署；backend-python 日志跨多个 60s 巡检周期 0 次 `Event loop is closed`，restarts=0。
发布门禁（16/16）结束后重启 backend-java 恢复 JWT k-0，24h 观察窗口自 **2026-11-13T00:26:34Z**
（最后一次计划内重启）重新计时。

## 8. 已知限制与未验证项

- **24h 观察窗口未到期**：基线 `docs/evidence/2026-11-12/observation-baseline.txt`
  （窗口起点 2026-11-13T00:26:34Z），到期 2026-11-14T00:26Z 复核 RestartCount / StartedAt /
  firing 告警 / 5xx 后才算完成 W1。任何计划内重建/重启都会重新计时；当前首检
  Prometheus/Alertmanager 均为 0 条 firing，应用容器 restarts=0。
- **GHCR push 未完成**：本机 gh token 无 `write:packages`（`permission_denied`），
  四镜像已本地构建（`docs/evidence/2026-11-12/build-images-final.log`），OCI
  `revision=712efb6`、`version=2.1.5`；容器 image ID 与镜像 ID 逐一相等。
  多架构 manifest 需 buildx（本机无插件），`build-images.sh` 已改为无 buildx 时
  单架构经典 build、多架构明确 exit 2。单架构本地镜像不得冒充 GHCR 发布物。
- **分支保护（W4）未启用**：需仓库管理员操作；发布说明须标注。
- **PR 合并被 Checks 权限阻塞**：PR #7 head=712efb6 / state=OPEN / UNSTABLE；
  `gh pr checks 7` 因 token 缺 `checks:read`/`actions:read` 返回 403，无法确认 UNSTABLE
  是 pending 还是失败。按 D2 应先合并 #7、再关闭 #6（superseded by f026329），但本轮
  改动尚未 commit/push，合并/关闭/tag 均未执行；不得 admin 强合。见
  `docs/evidence/2026-11-12/gh-pr-status.txt`。
- **JWT 轮换在 2.1.5 默认禁用（已收口，不再靠重启恢复）**：`application.yml` 新增
  `app.jwt.rotation.enabled=${JWT_ROTATION_ENABLED:false}`；禁用时
  `POST /api/admin/jwt-keys/rotate` 返回 **409**，不生成新 key、不写审计，只读快照保留并
  返回 `data.enabled=false`。`key-rotation-verify.py` 在禁用模式断言 **6/6**：`enabled=false`、
  rotate 409/501、密钥环前后一致、同一 `k-0` token 在 Java 与 Python 均可用。发布门禁
  **不再执行轮换**，结束后也**不需要重启 backend-java**。长期方案（持久化/共享 keyring，
  让 Python 也能验签 `k-1`）排期 **2.1.6**；在此之前不得把“轮换后重启”作为发布步骤。
- **外部集成（W6 / §13.2）未验证**：钉钉外推、飞书同步、OIDC 仍默认关闭；
  需真实测试企业/IdP 凭证，不得以「代码支持」充当通过。
- **设计语义**：ROW/CUSTOM 聚合 403 为设计行为；workflow 实例 tenant-wide 可读；
  历史 `created_by` 不自动回填（需 reindex）。
- **备份 API 网关路由（已修复并复测）**：`/api/admin/backup` 经 18080 曾返回 401，
  根因不是 Java 转发鉴权，而是 nginx `location /api/` 把所有 `/api/*` 转给 Java，而 Java
  没有该路由；端点只在 Python。已在 `frontend/nginx.conf`、`nginx.conf.template`、
  `render_nginx.py` 增加 `location ^~ /api/admin/backup` → `backend-python:8000`（放在
  `/api/` 之前，只放行该精确前缀）。新镜像实测：ADMIN POST 200、无 JWT 401、非 ADMIN 403；
  Java 直连 18082 仍 401，文档明确“备份只经网关/Python”。见
  `docs/evidence/2026-11-12/backup-gateway.log`。
- **默认栈事故残留已按保守策略处置**：测试用户 refresh token 已吊销（481→479），
  audit_log 16 行/用户 1 行/审批 1 行保留未删，详见 §4 与
  `docs/evidence/2026-11-12/default-stack-residue.txt`。
