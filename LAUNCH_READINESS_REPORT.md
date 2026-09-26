# NocoBase 多栈项目 — 上线就绪度评估报告

> 评估时间：2026-09-26（最后更新）
> 评估基线：`origin/main` = `01a6f47`
> 数据来源：**全部为实测**，未使用推断数字
> 变更说明：初版写于 `a87ac1b`（当时门禁 1143），此后合并 4 次提交
> （P0 可观测性、migration job 越权、P1 审计返工、IM 消息 P0 越权），本次同步刷新数据。

---

## 一、执行摘要

| 场景 | 结论 |
|---|---|
| **内部 POC / 演示环境** | ✅ **可上线**（功能完整、门禁全绿） |
| **生产公测（少量真实用户）** | ⚠️ **建议完成性能压测基线 + 备份恢复演练后再上** |
| **规模化生产（多租户 SaaS）** | ❌ **不建议**（缺压测基线、i18n；多租户为业务层过滤） |

**核心判断**：代码质量与门禁已达标（**1149 测试 0 失败**、E2E 64/0），
**P0 可观测性阻塞已解除**（K8s 编排 + Prometheus + 健康探针就位），
当前主要短板为**容量基线缺失**与**容灾演练未验证**。

---

## 二、门禁实测数据（全部真实执行）

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端单测 | `mvn -o test` | ✅ **1149 / 0 / 0 BUILD SUCCESS** |
| 前端单测 | `npm run test:run` | ✅ **250 passed / 32 files** |
| 类型检查 | `npx tsc --noEmit` | ✅ **0 errors** |
| E2E（双浏览器） | `npx playwright test` | ✅ **64 passed / 0 failed** |
| Python 编译 | `python3 -m py_compile` | ✅ OK |

**基线演进（只增不减）**：1075 → 1104 → 1125 → 1142 → 1143 → 1145 → 1146 → **1149**

**反作弊闸门**：新增 `it.skip` 0 / 删测试 0 / 弱化断言 0
（唯一 skip 为 `BiReportServiceTest` 既存项，非本轮引入）

---

## 三、已完成项

### Stage 1 — 安全收口
- `AclEnforcer` fail-closed：无角色→false、无 ACTION policy→默认拒绝、CTE 异常→ERROR
- `RowAclService`：无策略时拒绝（不再放行）
- `KeyRingService`：注入 `Environment`，非 dev profile 检测到 dev secret 即抛异常
- `AuditService`：`System.err` → `log.error`
- `application.yml`：`app.acl.fail-open: ${ACL_FAIL_OPEN:false}`

### Stage 2 — MQ 底座
- `AmqpConfig` + `AsyncTask` / `Publisher` / `Handler` / `Registry` / `Listener`
- `RabbitMqAsyncTaskPublisher`、`DeadLetterTaskListener`
- `WebhookSubscriptionService` 改 MQ 发布、`AsyncMigrationService` 改走 MQ
- `V36__mq_task_status.sql`

### Stage 3 — 集成接真
- `FeishuAppService.encryptKey`：从 `@Value("${feishu.encrypt-key:}")` 读取（原硬编码空串致签名形同虚设）
- `DingTalkController.updateWorkflowTaskStatus`：空 catch → ERROR 日志

### Stage 4 — 桩清零
- `DynamicTableManager.alterPhysicalColumn` + `ALTER_TYPE`
- 前端 `WikiPageList` 403 提示、`helpers.ts` mock glob→regex、删除 debug spec

### R1–R4 审计返工
- `TicketController.closeSession` 伪造邮箱 → 取请求 `customerEmail`/租户域
- V36 两张表只建不写 → `publisher.publish` 前置 upsert、`DeadLetterTaskListener` 持久化
- `DeadLetterTaskListener` TODO 接真，持久化失败不 ack
- **修正前轮误判**：Huddle 真实信令是原生 WS `/ws/huddle`（含房间管理+转发+JWT 鉴权），STOMP 桩 501 下线对线上无影响

### Stage 5 — 对标补齐
- `PlaybookServiceTest`（**21 用例**）：定义解析 fail-closed、workflow 编译缓存复用、SLA due_at、Checklist 越界 400、`markOverdue` 升级、复盘页生成/无 KB 跳过
- `ProjectBoardControllerTest`（**17 用例**）：Trello 列 CRUD、卡片移动、Checklist、Label，覆盖 400/404
- 核实 **Slack `thread_ts` / Mattermost `root_id` 后端已实现**，无需新增

### Stage 6 — 多租户隔离（真实漏洞修复）
- **发现并修复跨租户越权**：`WorkflowTaskRepository.findByAssigneeAndStatus` 按 assignee 查待办不带租户条件，
  而 `UserEntity.tenantId` 非空 + `UserTenantEntity` 允许切换多租户 → 用户切到租户 B 仍看到租户 A 的待办
- 修复：`WorkflowTaskEntity` 加 `tenant_id` 冗余 + **V37 迁移**（加列 + 从实例回填存量 + 索引）
  + 3 处创建点继承租户 + `myTasks` 改用 `findByTenantIdAndAssigneeAndStatus`
- 新增越权用例 `myTasks_scopesQueryToCurrentTenant`（`verify(never())` 证明旧查询已停用）

### Stage 7 — P0 可观测性（上线最大阻塞已解除）
- **K8s 编排**（`k8s/`，24 个资源，YAML 全部校验通过）：
  Namespace/ConfigMap/Secret、PostgreSQL(StatefulSet+PVC)/Redis/**RabbitMQ**/MinIO、
  backend-java(Deployment 3 副本 + HPA 3~10 + PDB + startupProbe)、backend-python、frontend、Ingress、ServiceMonitor
- **指标与告警**：`micrometer-registry-prometheus` 依赖；`application.yml` exposure 含 `prometheus`
  + `health.probes.enabled=true`（readiness/liveness 分组）+ `percentiles-histogram` + SLO(100ms~3s)；
  `PrometheusRule` 5 条告警（实例掉线、5xx>5%、P99>3s、Pod 频繁重启、RabbitMQ 不可用）
- **探针选型**：liveness `/actuator/health/liveness`（失败才重启）、readiness `/readiness`（仅摘流量）、
  startupProbe（最多等 150s，防慢启动被误杀）
- **修复配置与依赖脱节**：`docker-compose.yml` 此前**缺 RabbitMQ**（Stage 2 MQ 底座实际依赖它）
  → 补服务 + `backend-java` 的 `RABBITMQ_HOST/PORT` 与 `depends_on` + 数据卷

### 跨租户越权封堵汇总（6 类，均已修复并推送）

| # | 越权点 | 危害 | 修复方式 |
|---|---|---|---|
| 1 | `ImMessageController` `list`/`thread`/`search` | 读**他租户全部消息内容**（最严重） | 补 `@AuthenticationPrincipal` + `assertMember` 频道成员校验；`thread` 经 `mustGet` 取消息再校验 |
| 2 | `AutomationRuleController` `update`/`delete` | **跨租户改/删**自动化规则（写越权） | 补 `user.tenantId()` + `getRule(ruleId, tenantId)` 校验 |
| 3 | `CollectionController.getJob` | 读他租户迁移任务详情与错误信息 | 补认证(401) + 租户校验(403) |
| 4 | `WorkflowTaskRepository.findByAssigneeAndStatus` | 切租户后仍见他租户待办 | `tenant_id` 冗余 + V37 迁移 + `findByTenantIdAndAssigneeAndStatus` |
| 5 | `ImMessageController.listReactions` | 读他租户消息的 reaction（userId 集合） | 补认证 + 消息归属校验 |
| 6 | `ImHuddleController.listParticipants` | 读他租户语音会话参与者 | 传 `user.tenantId()` + `getHuddle` 归属校验 |

> **方法论**：本项目多租户防御在 **Service/Controller 层**，不在 Repository 层。
> 因此「Repository 无 tenant 过滤」≠ 漏洞，须**逐条追调用链**再下结论。
> 审计中曾误判 `WikiVersionRepository`（实际 `WikiController` 已做 tenant 校验，本就安全），已修正。

---

## 四、剩余缺口（按上线阻塞程度分级）

### 🔴 P0 — 生产上线阻塞
**当前状态：已解除**（`8a42d3b` 补齐，详见第三章 Stage 7）

| 项 | 原状态 | 现状态 |
|---|---|---|
| K8s 编排 | 全缺 | ✅ `k8s/` 24 资源（Deployment/Service/Ingress/HPA/PDB） |
| 监控告警 | 全缺 | ✅ Micrometer + Prometheus + 5 条 PrometheusRule |
| 健康检查 | 未确认 | ✅ readiness / liveness / startupProbe 均配置 |

### 🟡 P1 — 规模化前需补
| 项 | 现状 |
|---|---|
| **性能压测** | **未做**（无 QPS / P99 / 并发容量基线）；脚本 `perf/load-test.js`(k6) 已就绪，**待 staging 执行**后填基线 |
| **备份恢复演练** | 链路完整（`create_backup`/`list_backups`/`restore_backup`/`run_scheduled_backup`，Redis 已 fail-closed），但**从未做过真实恢复验证**（RPO/RTO 未知） |
| 多租户持续审计 | 已封堵 6 类越权（见上表）；架构上仍为**业务层过滤**而非 SCHEMA 隔离，新增接口须持续核查归属校验 |

### 🟢 P2 — 体验 / 对标
| 项 | 现状 |
|---|---|
| i18n | 全缺（仅中文） |
| Airtable 公式/汇总字段 | 后端可算，**前端 UI 未接线** |
| Notion 协同 | CRDT 未做服务端合并，多人编辑会互相覆盖 |
| Trello 看板前端 | 后端 API 齐全，**前端未接线**（`BoardView`/`BoardColumn` 为坏死代码） |
| 批量操作 API | 缺失 |
| 微信客服 | 未独立于微信小程序 |
| 限流 | 内存态（多实例部署失效，需 Redisson） |
| FTS 中文分词 | 暂停（Alpine 无 zhparser、无 gcc/make/git，不可编译） |

---

## 五、上线建议（分阶段）

**第 1 步（当前可做）**：内部 POC / 演示环境上线
- 门禁已达标，功能闭环完整，风险可控

**第 2 步（生产公测前）**：补齐验证缺口
1. **性能压测**：在 staging 执行 `perf/load-test.js`，记录 QPS / P95 / P99 / 错误率 / 拐点
2. **真实备份恢复演练**：验证 `restore_backup` 可恢复，量化 RPO / RTO
3. （可选）Grafana 面板 + 日志采集（EFK / Loki）

**第 3 步（规模化前）**：补齐 P1 / P2
1. 限流改 Redisson 分布式（当前内存态，多实例部署失效）
2. i18n（当前仅中文）
3. 分布式追踪（OpenTelemetry + Jaeger）
4. 多租户持续审计（新增接口须带归属校验）

---

## 六、关键风险提示

1. ~~**可观测性为零**~~ — **已解除**：K8s 编排 + Prometheus 指标 + 健康探针 + 5 条告警已就位（`8a42d3b`）。
2. **多租户为「业务层过滤」而非 SCHEMA 隔离** — 架构性风险：依赖开发者自觉在 Service/Controller 层做归属校验。
   本轮已封堵 6 类真实越权（含最严重的 IM 消息跨租户读取），但**新增接口仍需持续审计**。
3. **容量基线缺失** — 无 QPS / P99 / 并发容量数据，无法判断扩容时机；压测脚本已就绪，待 staging 执行。
4. **容灾未验证** — 备份链路代码完整，但**从未做过真实恢复演练**，RPO/RTO 未知。
5. **中文分词暂停** — 全文检索中文场景降级为 LIKE，数据量大时性能不可接受。
6. **协同编辑冲突** — Notion 式 CRDT 未做服务端合并，多人同时编辑会覆盖。

---

## 附录：验证方法说明

本报告所有门禁数字均由实际命令执行得出，未引用前序会话的推断值：
- `mvn -o test`（全量，非单类）
- `npm run test:run`（注意：本项目不可用 `npx vitest run`，会进 watch 模式）
- `npx tsc --noEmit`
- `npx playwright test`（chromium + firefox 双浏览器全量）
- `python3 -m py_compile backend-python/src/nocobase_py/{services,routers}/backup.py`
