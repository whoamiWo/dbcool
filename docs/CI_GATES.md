# CI 门禁文档（PHASE103）

> 目标：每条门禁的作用、命令、阈值、失败怎么办。
> 与 `PHASE95_103_INDEX.md` 的门禁表保持一致。

---

## 1. 门禁总览

> **SKIP 不等于 PASS**：`monthly-gate.py` 会把 SKIP 项单列（例如无 Docker 时的 pg IT、
> 可选对象存储未启用时的附件链路）。最终结论同时打印「通过项」与「SKIP 项」。
>
> **SKIP 语义（TY4 修订，禁止假绿）**：
> - SKIP 只认机器标记 `GATE_SKIP: <count>`（迁移期兼容 `SKIP:`/`⏭️ SKIP`），且仅限
>   「备份/PITR 演练」「企业套件真实 PG (pg IT)」两项白名单门禁；其它门禁一律按退出码判定。
> - SKIP/FAIL 门禁的旧 `gate-numbers.json` 键会被删除，禁止历史 PASS 数字残留；
>   备份数字只在 `--release` 真实执行通过时写入。
> - `python3 scripts/monthly-gate.py --release` 会先运行 `scripts/acceptance-preflight.py`
>   固定验收目标（`BASE_URL`/`PG_CONTAINER`/`PG_STRICT=1`/`BACKUP_DIR`/`COMPOSE_PROJECT_NAME`/
>   `REDIS_CONTAINER`，可选 `BACKEND_SERVICE`，默认 `backend-java`；预检还会校验 BASE_URL
>   端口落在该后端服务、后端数据源指向 `PG_CONTAINER`），且任何 SKIP 一律判 FAIL；门禁真实性
>   自测见 `python3 scripts/gate-skip-selfcheck.py`。
> - **E6 数据面同栈**：preflight 不只比对环境变量，还要求 backend 与 **PG 和 Redis** 都共享
>   Docker 网络，并在 backend 容器内用 `getent hosts <compose service>` 解析到目标容器 IP。
>   service 名取自容器 label `com.docker.compose.service`（不硬编码 `postgres`/`redis`）；
>   `getent` 缺失、解析失败、读不到网络/IP 一律 **fail closed**，不能当作「跳过」。
>   这堵的是 2026-11-12 实测的跨栈事故：compose 顺序错误使 backend 接到默认网络，
>   env 检查全过但数据写入默认库/默认 redis。
> - **JWT 轮换 2.1.5 默认禁用**：`JWT_ROTATION_ENABLED=false`，`POST /api/admin/jwt-keys/rotate`
>   返回 409/501；`key-rotation-verify.py` 断言禁用生效且同一 k-0 token 在 Java/Python
>   均可用，不再执行轮换，门禁结束后无需重启 backend-java。持久化/共享 keyring 排期 2.1.6。
> - 分支保护：未配置；需仓库管理员开启并保存证据；未配置不得宣称 N2 收口。

| 门禁 | 命令 | 阈值 | 失败怎么办 |
|---|---|---|---|
| Java 测试 | `cd backend-java && mvn -o verify` | 2855，0 failures / 0 errors | 看 `target/surefire-reports/` 中对应测试报告 |
| Java 覆盖率 | `cd backend-java && mvn -o verify` | JaCoCo BUNDLE ≥ 79%（唯一阈值来源：`backend-java/pom.xml`） | 见 §3 覆盖率缺口与补测计划 |
| 前端单测 | `cd frontend && npm run test:run` | 978 passed / 121 files | 看 vitest 输出中失败的 spec |
| Playwright（含 375px） | `cd frontend && npx playwright test` | 260 tests / 13 files（`--list` 实测；chromium/firefox/mobile-390/mobile-375） | 看 Playwright 报告（`npx playwright show-report`） |
| 类型检查 | `cd frontend && npx tsc --noEmit` | 0 errors | 按报错文件与行号修 |
| E2E | `cd frontend && npx playwright test` | ≥ 135 | collab-crdt 偶发 429 见 §4 |
| Python | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 85 passed / 1 skipped | 看 pytest 输出 |
| 备份演练 | `python3 scripts/backup-e2e-verify.py` | 仅 release 实测通过后写数字；SKIP 不计 PASS | 见 `docs/backup-restore-runbook.md` |
| 管理员鉴权 | `python3 scripts/admin-authz-e2e-verify.py` | 18 / 18 | 检查 admin/user 登录状态码 |
| 越权回归 | `python3 scripts/tenant-isolation-e2e-verify.py` | 24 / 24 PASS | 检查跨租户断言 |
| 契约比对 | `python3 scripts/api-contract-verify.py` | 0 漂移 | 重跑 `scripts/export-backend-mappings.py` |
| SaaS 就绪 | `python3 scripts/saas-readiness-verify.py` | 11 / 11 | 检查 T1/T2/T3 三项 |
| 冒烟 | `python3 scripts/smoke.py` | 14/14 PASS | 检查服务是否全部 healthy |
| 生产检查 | `python3 scripts/startup-checklist.py` | 全 PASS | 按清单逐项修 |
| 企业套件真实 PG（Testcontainers） | `python3 scripts/pg-it-gate.py` | 12/12；无 Docker → SKIP | 看 `EnterpriseSuitePgIT` / `UnifiedSearchIndexRepositoryPgIT` / `ApprovalExternalBindingRepositoryPgIT` 输出 |
| 归属写入静态门禁 | `python3 scripts/actor-writer-verify.py` | PASS | 禁止控制器回退旧 UUID-less 重载 |
| 企业套件 schema smoke | `python3 scripts/verify-enterprise-suite.py` | 20/20 | 检查 V77–V84 迁移是否已应用 |
| 依赖漏洞扫描（离线替代） | `python3 scripts/java-dependency-audit.py --backend offline`（离线环境） | 0 高危 | 补 `config/known-vulnerabilities.json` 或升级依赖 |
| 密钥轮换验证 | `python3 scripts/key-rotation-verify.py` | 2.1.5 禁用模式 6/6 断言（rotate 409/501、k-0 在 Java/Python 均可用） | 见 `docs/key-rotation-runbook.md` |
| 配额端到端 | `python3 scripts/quota-e2e-verify.py` | 5/5 | 检查存储/席位配额链路 |
| 证书生命周期 | `./scripts/cert-lifecycle.sh check` | exit 0 | 见 `PHASE100_DEPLOY_GUIDE.md` §4.1 |
| 上线终验编排 | `python3 scripts/launch-final-verify.py` | 8/8 | 看失败项输出 |

---

## 2. CI 配置（PHASE103 变更）

### 2.1 Java job 改跑 `mvn verify`

`.github/workflows/ci.yml` 的 `java-test` job 从 `mvn test` 改为 `mvn verify`：

```yaml
- name: Run Java tests + verify (test profile, H2)
  working-directory: backend-java
  run: mvn verify -B -Dspring.profiles.active=test
```

**为什么**：JaCoCo 红线（BUNDLE ≥79%、audit 97% 等）绑定在 `verify` 阶段，只跑 `test` 等于红线永不生效（PHASE103 之前的真实状态）。

**可失败性验证**：`mvn -o verify` 本地实测输出：
```
Rule violated for bundle nocobase-backend: lines covered ratio is 0.63, but expected minimum is 0.79
Rule violated for package com.nocobase.notification: lines covered ratio is 0.63, but expected minimum is 0.90
→ BUILD FAILURE
```
证明红线确实生效（不是恒真断言）。

### 2.2 `@Tag("pg")` 测试在 `mvn verify` 中的语义

`pom.xml` 的 surefire 配置**没有**排除 `pg` 标签，因此 `mvn -o verify` 会执行
Testcontainers 测试（`EnterpriseSuitePgIT` 等）：

- Docker 可用：真实启动 PostgreSQL 容器并执行断言；容器/镜像不可拉取时构建会失败。
- Docker 不可用：测试类上的 `@Testcontainers(disabledWithoutDocker = true)` 自动 **SKIP**，
  构建继续通过（surefire 输出 `Tests run: ..., Skipped: ...`）。

这不是「跳过门禁」：`python3 scripts/pg-it-gate.py` 是独立的真实 PG 门禁，会解析
surefire 报告并对「SKIP」单独判定（`monthly-gate.py` 只认白名单门禁输出的
`GATE_SKIP: <count>` 机器标记，迁移期兼容旧 `SKIP:`/`⏭️  SKIP` 字样；其它门禁
一律按退出码判定，见文首 TY4 语义）。
若希望本地 `verify` 完全不触碰 Docker，可显式加 `-DexcludedGroups=pg`，但 CI 的
`mvn verify` 保持默认（有 Docker 时真实执行）。

### 2.3 移除 `|| true`

- `Makefile:108` `mvn checkstyle:check -q || true` → 移除（lint 失败必须退出）
- `Makefile:115` `mvn spotless:apply || true` → 移除
- `.github/workflows/ci.yml` cleanup 步骤的 `kill ... || true` **保留**，已加注释说明：进程可能不存在，不影响门禁结论

验证：
```bash
grep -rn "|| true" Makefile .github/workflows/ scripts/ | wc -l   # 1（仅 cleanup 例外，已注释）
```

---

## 3. 覆盖率缺口与补测计划（遗留项）

### 3.1 当前状态（2026-10-13 实测 PHASE104）

```
总 missed=4929 covered=9856 ratio=0.654
覆盖率目标以 `backend-java/pom.xml` 为准（当前 79%）；下方缺口数字为历史记录，仅作趋势参考
本批净增 806 行覆盖（CollectionServiceCRUDTest +21 + NotificationCoverageBoostTest +19 + TriggerRateLimiterTest +12 + WorkflowTaskEntityTest +4 = 56 tests）
```

### 3.2 各包缺口（按 missed 行数排序）

| 包 | missed / total | 主要缺口类 |
|---|---|---|
| `com.nocobase.meta` | 545 / 1266 | `CollectionService` (299), `DynamicTableManager` (148) |
| `com.nocobase.wiki` | 493 / 1179 | `WikiController` (231), `WikiBlockService` (63) |
| `com.nocobase.im` | 412 / 936 | `HuddleService` (81), 其余分散 |
| `com.nocobase.integration.dingtalk` | 380 / 580 | `DingTalkController` (114), `DingTalkOrgSyncService` (77) |
| `com.nocobase.project` | 219 / 569 | `ProjectController` (80), `ProjectBoardController` (73) |
| `com.nocobase.notification` | 196 / 542 | `WeChatPersonalDispatcher` (56), `FeishuPersonalDispatcher` (51) |
| `com.nocobase.realtime` | 184 / 240 | `RealtimeService` (78) |
| `com.nocobase.ai` | 167 / 304 | `AiAssistantService` (76), `AgentService` (54) |
| `com.nocobase.plugin` | 154 / 405 | `PluginMarketplaceService` (99) |
| `com.nocobase.automation` | 151 / 297 | 本批已补 20 测试，剩余为边界分支 |

### 3.3 补测计划（按优先级）

**P0（业务关键，预计 +800 行）**：
1. `CollectionService` / `DynamicTableManager`（meta 包，299+148 行）—— 核心数据引擎，单测可覆盖大部分逻辑
2. `HuddleService`（im 包，81 行）—— 依赖 Redis，用 mock 覆盖
3. `AiAssistantService` / `AgentService`（ai 包，76+54 行）—— mock LLM 调用

**P1（集成适配器，预计 +600 行）**：
4. `DingTalkController` / `DingTalkOrgSyncService`（380 行）—— 用 MockMvc 覆盖
5. `ProjectController` / `ProjectBoardController`（153 行）—— MockMvc
6. `WikiController` / `WikiBlockService`（294 行）—— MockMvc + mock repository

**P2（外部渠道，预计 +400 行）**：
7. `WeChatPersonalDispatcher` / `FeishuPersonalDispatcher` / `WeComDispatcher`（144 行）—— mock HTTP 客户端
8. `PluginMarketplaceService`（99 行）—— mock plugin loader

**Controller 层统一策略**：用 `@WebMvcTest` + MockMvc，参照 `RowAclControllerTest` 的既有模式。

### 3.4 为什么本批没做完

- 净增 2593 行覆盖 ≈ 6-7 个同等规模测试文件，预估 3-4 小时
- 本批同时要完成 T2/T3/T4/T6/T7，时间预算不足
- 按红线 3：**不降阈值**，如实登记缺口与计划

### 3.5 PHASE104 实际进展

本批新增 4 个测试文件、56 个测试，净增约 500 行覆盖：

| 测试文件 | 测试数 | 覆盖目标 |
|---|---|---|
| `CollectionServiceCRUDTest` | 21 | meta 包 `CollectionService` CRUD 边界 |
| `NotificationCoverageBoostTest` | 19 | notification 包 dispatcher 私有方法 + WebClient 路径（修复 `FeishuPersonalDispatcher.buildCardMessage` 不可变列表 bug） |
| `TriggerRateLimiterTest` | 12 | workflow 包 `TriggerRateLimiter`（Redis 路径 + 内存回退） |
| `WorkflowTaskEntityTest` | 4 | workflow 包 `WorkflowTaskEntity` getter/setter |

包级变化：
- `workflow`: 0.92 → 0.94（接近 0.95 目标）
- `notification`: 0.63 → 0.81
- 总体: 0.637 → 0.654（当前红线 0.79，最终由 `pom.xml` 单一维护）

---

## 4. 已知偶发失败

### collab-crdt E2E 偶发 429

`frontend/e2e/collab-crdt.spec.ts` 3 个 browser project 并发真实登录，撞 `ratelimit.login=5/300s`（基于来源 IP）。本地不重试故红，CI 下 `retries:2` 洗绿，每轮失败个数随机。

**处理**：PHASE95 已登记为遗留；PHASE102 容量基线确认这是设计限流，非容量问题。

---

## 5. 路径触发范围（PHASE103 T7 结论）

`backend-ci.yml` 的 `paths` 只含 `backend-java/**`：

```yaml
on:
  push:
    paths:
      - 'backend-java/**'
```

**结论：有意为之**。理由：
- 后端 job 只跑 Java 测试，前端/Python 改动不会影响 Java 测试结果
- 共享契约（`contracts/`）由主 `ci.yml` 的 contract job 覆盖（`needs: [java-test]`）
- 若未来 `contracts/` 变更需要触发后端门禁，在 `backend-ci.yml` 的 paths 加一行即可

---

## 6. 安全扫描（PHASE103 T3 基线）

### 6.1 前端 npm audit

```bash
cd frontend && npm audit --omit=dev --registry=https://registry.npmjs.org
→ 2 moderate (react-router 6.x → 7.18.4, breaking change)
```

**处理计划**：PHASE104 升级 `react-router-dom` 到 7.18.4（需验证 breaking changes）。

### 6.2 Python bandit SAST

```bash
cd backend-python && bandit -r src -f json
→ 0 HIGH / 4 MEDIUM / 26 LOW (total 4594 LOC)
```

**MEDIUM 明细**（均为误报或可接受风险）：
| ID | 文件 | 行号 | 原因 | 处理 |
|---|---|---|---|---|
| B104 | config.py | 25 | uvicorn bind 0.0.0.0 | 容器内服务，正常 |
| B608 | alert_store.py | 154 | SQL 拼接 | 参数化查询（`?` 占位符），误报 |
| B108 | backup.py | 42 | `/tmp/backups` | 配置目录，非临时文件创建，误报 |
| B104 | embedding_service.py | 101 | uvicorn bind 0.0.0.0 | 容器内服务，正常 |

**B310 已加 nosec**：webhook.py:85（URL 来自管理员配置，非用户输入）。

**阻断阈值设定**：
- HIGH: 0（发现即阻断 CI）
- MEDIUM: ≤ 10（当前 4，需逐个评审）
- LOW: ≤ 50（当前 26）
- npm audit moderate: 0（当前 2，PHASE104 修复）

### 6.3 Java OWASP Dependency Check

尚未集成（需额外安装插件）。计划 PHASE104 加入。

### 6.4 Trivy 容器扫描

尚未集成（需下载二进制）。计划 PHASE104 加入。

