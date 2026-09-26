# NocoBase 多栈项目 — 上线就绪度评估报告

> 评估时间：2026-09-26
> 评估基线：`origin/main` = `ee91b87`
> 数据来源：**全部为本轮实测**，未使用推断数字

---

## 一、执行摘要

| 场景 | 结论 |
|---|---|
| **内部 POC / 演示环境** | ✅ **可上线**（功能完整、门禁全绿） |
| **生产公测（少量真实用户）** | ⚠️ **建议补齐可观测性后再上**（缺 K8s 编排 + 监控告警） |
| **规模化生产（多租户 SaaS）** | ❌ **不建议**（缺压测、多租户加固未全覆盖、i18n 缺失） |

**核心判断**：代码质量与门禁已达标（1143 测试 0 失败、E2E 64/0），**主要短板不在功能，而在运维基础设施与规模化能力**。

---

## 二、门禁实测数据（全部真实执行）

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端单测 | `mvn -o test` | ✅ **1143 / 0 / 0 BUILD SUCCESS** |
| 前端单测 | `npm run test:run` | ✅ **250 passed / 32 files** |
| 类型检查 | `npx tsc --noEmit` | ✅ **0 errors** |
| E2E（双浏览器） | `npx playwright test` | ✅ **64 passed / 0 failed** |
| Python 编译 | `python3 -m py_compile` | ✅ OK |

**基线演进（只增不减）**：1075 → 1104 → 1125 → 1142 → **1143**

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

---

## 四、剩余缺口（按上线阻塞程度分级）

### 🔴 P0 — 生产上线阻塞
| 项 | 现状 | 说明 |
|---|---|---|
| **K8s 编排** | 全缺 | 无 Deployment / Service / Ingress / HPA，无法水平扩展 |
| **监控告警** | 全缺 | 无 Prometheus / Grafana / Micrometer；线上故障不可观测 |
| **健康检查** | 未确认 | 需 readiness / liveness 探针 |

> 这三项是「代码能跑」与「能运维」的分界线，缺失时生产事故无法发现和恢复。

### 🟡 P1 — 规模化前需补
| 项 | 现状 |
|---|---|
| 性能压测 | 未做（无基线 QPS / P99 / 并发容量数据） |
| 多租户加固（剩余 9 个 Repository） | `WikiVersion` / `MigrationJob` / `Message` / `AiConversationEntity` / `User` / `UserRole` / `ImReaction` / `ImHuddleParticipant` / `AutomationExecution` 无 tenant 过滤；多数经父实体间接隔离（风险较低），`WikiVersion`(by pageId) 与 `MigrationJob` 建议优先加固 |
| 备份恢复演练 | 链路完整（`create_backup`/`list_backups`/`restore_backup`/`run_scheduled_backup`，Redis 已 fail-closed），但**未做过真实恢复演练** |

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

**第 2 步（生产公测前）**：补齐 P0
1. K8s Deployment + Service + Ingress + HPA
2. Micrometer + Prometheus + Grafana + 关键告警规则
3. readiness / liveness 探针
4. **真实备份恢复演练**（验证 RPO/RTO）

**第 3 步（规模化前）**：补齐 P1
1. 性能压测（建立 QPS / P99 基线）
2. 剩余 9 个 Repository 租户加固（优先 `WikiVersion`、`MigrationJob`）
3. 限流改 Redisson 分布式

---

## 六、关键风险提示

1. **可观测性为零** — 当前线上故障只能靠用户反馈发现，是最紧迫的上线阻塞项。
2. **多租户为「业务层过滤」而非 SCHEMA 隔离** — 依赖开发者自觉在每个查询带 tenant 条件；已发生 1 起真实越权（本轮修复），剩余 9 处需持续加固。
3. **中文分词暂停** — 全文检索中文场景降级为 LIKE，数据量大时性能不可接受。
4. **协同编辑冲突** — Notion 式 CRDT 未做服务端合并，多人同时编辑会覆盖。

---

## 附录：验证方法说明

本报告所有门禁数字均由实际命令执行得出，未引用前序会话的推断值：
- `mvn -o test`（全量，非单类）
- `npm run test:run`（注意：本项目不可用 `npx vitest run`，会进 watch 模式）
- `npx tsc --noEmit`
- `npx playwright test`（chromium + firefox 双浏览器全量）
- `python3 -m py_compile backend-python/src/nocobase_py/{services,routers}/backup.py`
