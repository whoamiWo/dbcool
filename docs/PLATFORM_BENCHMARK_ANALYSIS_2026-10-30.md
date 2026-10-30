# DBCool 企业级综合平台 · 七强对标实证分析与差距补齐路线图

> **本文是当前唯一权威的对标结论**（取代 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md`、
> `docs/PLATFORM_GAP_ANALYSIS_2026-10-26.md`、`docs/CAPABILITY_MATRIX_DETAIL.md`、
> `docs/EMBED_INTEGRATION_ASSESSMENT.md` 中所有与当前代码冲突的判定）。
>
> - 评估基线：`02b3a59` + 工作区未提交改动，实测于 2026-10-30 @02b3a59
> - 评估对象：DBCool（Java 21 Spring Boot 3.4 + Python FastAPI + React 18 + Node CRDT；PostgreSQL + Redis + RabbitMQ + MinIO）
> - 对标产品：NocoDB、NocoBase、Slack、RocketChat、Mattermost、Trello、Airtable、Notion
> - 方法论：**只读代码实证**（按调用链追溯「谁调用它」）+ 实测门禁，不引用未验证数字
> - 本批次只产出分析与文档，**不改业务代码、不 commit**

---

## 0. 实测门禁基线（全部为本次实际命令输出）

| 项 | 命令 | 实测结果（实测于 2026-10-30 @02b3a59） |
|---|---|---|
| 后端单测 + 覆盖率 | `cd backend-java && mvn -o verify` | `Tests run: 2585, Failures: 0, Errors: 0, Skipped: 0`；JaCoCo `All coverage checks have been met.`；BUILD SUCCESS |
| 后端覆盖率数值 | `jacoco.csv` 汇总 | BUNDLE **line 0.8006**（红线 0.79）、instruction 0.7813、branch 0.6409 |
| 前端单测 | `cd frontend && npm run test:run` | `Test Files 118 passed (118)` / `Tests 962 passed (962)`，**0 failed**；⚠️ `Errors 5 errors`（ErDiagram.test.tsx 等用例执行后 unhandled error，详见 §4 P2-12） |
| 前端类型检查 | `npx tsc --noEmit` | 0 errors |
| Python 单测 | `PYTHONPATH=src python3 -m pytest tests/ -q` | `68 passed, 1 skipped` |
| 版本一致性 | `python3 scripts/version-consistency-verify.py` | 10/10 一致 = 2.1.1，exit 0；自检 A（改坏 package.json）exit 1；自检 B（删 VERSION）exit 2；自检 C（`--fix` 幂等）exit 0 |
| 依赖漏洞 | `python3 scripts/java-dependency-audit.py` | 阻断级 0；1 条 MEDIUM（logback-core 1.5.18 / CVE-2023-6481，1.5.x 已移除 receiver 功能，实际不受影响） |
| Playwright E2E | — | **未实测**（本批次未执行浏览器用例，不宣称通过） |

---

## 1. 一句话结论

> **DBCool 的「广度整合」目标已基本达成**：数据引擎、文档/Wiki、IM、看板/项目、工作流、AI、钉钉/企微/飞书/微信嵌入、外部数据源、计费、多租户、可观测性全部在代码里**真实接线**，且绝大多数历史 P0（备份主库、钉钉登录 405、块编辑 404、Huddle 多副本、CRDT 整篇替换、K8s Secret 错配）**已证实修复**。
>
> **当前真正的问题已从「功能缺失」转为「断链与深度不足」**：
> ① 块编辑器裸 `fetch` 不带 JWT → 自动保存 401；② CRDT 未订阅初始化通道；③ Pin / 阅后即焚 / Checklist API 前后端路径断链；④ Slack token 仍内存态；⑤ RocketChat 0 实现；⑥ 集成市场页面无路由；⑦ Wiki 版本对比/分享/收藏/导出缺失；⑧ 工作流缺 webhook 触发与重试。
>
> 对 **200 人企业内部自用**：修完 §4 的 6 项 P0（约 8–12 人日）即可达标；对 **对外商业化**：仍需补 §4「对外必补」清单（密钥托管、PITR、真实支付网关、灰度发布、租户配额计量闭环）。

---

## 2. 已被本次实证推翻的旧结论（重要，避免再按旧报告返工）

| 旧报告结论 | 当前代码事实 | 证据 |
|---|---|---|
| 块编辑自动保存打不存在端点（404） | 端点已存在且 DTO 对齐；**新问题变成裸 fetch 无 JWT → 401** | `WikiController.java:478-500`；`NotionStyleEditor.tsx:343-346` |
| 模板列表端点不匹配 | 前后端路径已一致 | `NotionStyleEditor.tsx:401` ↔ `WikiController.java:661-694` |
| 反向链接未 unwrap 信封 | 已按 `data?.data` 取值 | `NotionStyleEditor.tsx:383-388` |
| CRDT 整篇替换 | 已改为 LCS 字符级 diff | `CollabEditor.tsx:47-123,286-299` |
| K8s CRDT Secret 复数名错配 → 必 CreateContainerConfigError | 已统一为单数 `nocobase-secret` + 大写 key | `k8s/11-crdt-service.yaml:48-57` ↔ `k8s/00-base.yaml:48,53-54` |
| 备份不含 Postgres 主库 + docker exec 硬编码 | 已 pg_dump -Fc，fail-closed，Redis 改直连 | `backend-python/.../backup.py:90-121,21,141` |
| 钉钉登录主入口 405 | 后端同时提供 GET+POST，且有防回归测试 | `DingTalkController.java:83,91`；`DingTalkLoginPage.test.tsx:49` |
| Huddle 内存路由与多副本冲突 | 已 Redis pub/sub 跨副本 | `HuddleSignalingHandler.java:140-183` |
| 工作流定时内存态、无 Cron | 已 Redis 分布式锁 + `last_triggered_at` 持久化 + CronExpression | `WorkflowScheduler.java:93-111,162-176` |
| 第二套 ExpressionEvaluator 死实现 | 已唯一（common 版） | `common/ExpressionEvaluator.java` |
| IM 高级搜索 MessageSearchService 0 注入 | 已接入 advanced-search / mentions，租户过滤下推 SQL | `ImMessageController.java:41,51,244,280` |
| Slack/Mattermost 入站只打日志 | 已接 `InboundMessageService`；Mattermost 已 fail-close | `SlackAppService.java:189-198`；`MattermostAppService.java:61-91` |
| 字段类型仅 13 种 | 已 25 种 | `meta/FieldDef.java:72-93` |
| 视图级 group by 仅 BI 有 | TableView 已走后端 aggregate | `pages/TableView.tsx:153-179`；`CollectionController.java:998-1019` |
| 限流只有 workflow 触发 | 已有 GlobalRateLimitFilter（登录/IM/上传/回调） | `ratelimit/GlobalRateLimitFilter.java:81-116` |
| 压测只压 2 个只读接口 | 已含 4 条写路径 | `perf/load-test.js:12-15,194-231` |
| 无外部数据源 / 微信 OAuth / JS-SDK 签名 / 计费 / Checklist / AI 提示词模板 | 全部已实现并接线 | 见 §3 各域证据 |

---

## 3. 七强对标能力矩阵（判定口径：①存在文件 ②已接线 ③生产可用）

图例：✅ 具备且接线 ｜ 🟡 部分/断链/深度不足 ｜ ❌ 缺失 ｜ ➖ 不适用

| # | 能力维度 | Airtable | Notion | NocoDB | NocoBase | Slack | RocketChat | Trello | **DBCool 判定** | 关键证据 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 数据/表格引擎 | ✅ | 🟡 | ✅ | ✅ | ➖ | ➖ | ➖ | ✅ | `CollectionController.java:307-543`；`DynamicTableManager.java:362-373` |
| 2 | 字段类型丰富度 | ✅20+ | 🟡 | ✅ | ✅ | ➖ | ➖ | ➖ | 🟡 25 种，缺 user/button | `FieldDef.java:72-93` |
| 3 | 公式/汇总/查找 | ✅ | 🟡 | ✅ | ✅ | ➖ | ➖ | ➖ | 🟡 24 函数，DATEDIFF 跨月有误 | `FormulaFunctions.java:32-47,458-465` |
| 4 | 视图多样性 | ✅ | ✅ | ✅ | ✅ | ➖ | ➖ | ✅ | 🟡 甘特无后端类型、日历翻月坏 | `ViewEntity.java:29-31`；`CalendarView.tsx:175-186` |
| 5 | ACL 精细度 | ✅ | ✅ | ✅ | ✅ | 🟡 | 🟡 | 🟡 | ✅ 字段/行/操作级（应用层过滤） | `AclEnforcer.java:133-147`；`RowAclService.java:45-93` |
| 6 | 文档/知识库 | ➖ | ✅标杆 | 🟡 | 🟡 | ➖ | ➖ | ➖ | 🟡 缺版本对比/分享/收藏/导出 | `WikiPageService.java:424-444`（share 无端点） |
| 7 | 块级编辑 | ➖ | ✅标杆 | ❌ | 🟡 | ➖ | ➖ | ➖ | ❌ 自动保存 401（裸 fetch） | `NotionStyleEditor.tsx:343-346` |
| 8 | 实时协同（字符级） | 🟡 | ✅标杆 | ❌ | ❌ | ➖ | ➖ | ➖ | 🟡 字符级已成，初始化通道未订阅 | `CollabEditor.tsx:47-123`；`stompClient.ts:108-129` |
| 9 | 检索（FTS+向量） | 🟡 | ✅ | 🟡 | 🟡 | ✅ | 🟡 | ➖ | 🟡 向量混合检索后端全、前端未接 | `WikiEmbeddingService.java:125-197`；`WikiSearch.tsx:50` |
| 10 | IM 频道/线程/回执 | ➖ | ➖ | ➖ | ➖ | ✅标杆 | ✅ | ➖ | ✅ | `ImMessageController.java:194,318-375` |
| 11 | IM 生态（机器人/应用市场/联邦） | ➖ | ➖ | ➖ | ➖ | ✅极强 | ✅ | ➖ | ❌ 机器人/联邦无；市场页无路由 | `IntegrationsPage.tsx:17`；`router.tsx` 0 命中 |
| 12 | 语音/Huddle | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ | ➖ | ✅ WebRTC + Redis 跨副本 | `HuddleSignalingHandler.java:140-183` |
| 13 | 看板/项目（拖拽/清单/甘特） | 🟡 | 🟡 | 🟡 | 🟡 | ➖ | ➖ | ✅标杆 | 🟡 全链路在，交互仍 prompt() | `BoardView.tsx:147-156,175` |
| 14 | 工作流/自动化 | ✅强 | 🟡 | 🟡 | ✅ | ✅ | 🟡 | ✅ | 🟡 缺 webhook 触发与节点重试 | `WorkflowEngine.java:127-131` |
| 15 | 集成生态 | ✅强 | 🟡 | 🟡 | ✅ | ✅极强 | ✅ | ✅ | 🟡 后端真、前端不可达、RocketChat 0 | `IntegrationMarketController.java:37-72` |
| 16 | 钉钉/企微/飞书/微信嵌入 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ 核心差异化优势 | `JsSignatureController.java:54-104`；`DingTalk/WeCom/Feishu/WeChat*Provider` |
| 17 | 外部数据源 | 🟡 | 🟡 | ✅ | ✅ | ➖ | ➖ | ➖ | 🟡 仅 mysql/postgresql | `ExternalDataSourceConnectionFactory.java:41-77` |
| 18 | AI 能力 | 🟡 | ✅ | 🟡 | 🟡 | 🟡 | 🟡 | ➖ | ✅ Agent/提示词模板/RAG | `AiController.java:21-126` |
| 19 | 多租户/配额 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | ✅ Schema 隔离 + 配额过滤器 | `SchemaTenantConnectionProvider.java:25-49`；`TenantQuotaFilter` |
| 20 | 运维（备份/限流/压测/审计/观测） | ➖ | ➖ | 🟡 | 🟡 | ➖ | 🟡 | ➖ | 🟡 无 PITR、无灰度 | `backup.py:90-121`；k8s 无 canary |
| 21 | 计费/商业化 | ✅ | ✅ | ➖ | 🟡 | ✅ | ➖ | ✅ | 🟡 默认关闭、无真实支付平台 | `BillingController.java:31`；`application.yml:279` |
| 22 | 移动端 | ✅ | ✅ | 🟡 | 🟡 | ✅强 | ✅ | ✅ | 🟡 底部导航 + 3 处 @media | `AppLayout.tsx:199-204`；`styles.css:404,613,656` |
| 23 | 自部署 | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ | ❌ | ✅ 结构性优势 | docker-compose + k8s 全套 |

### 三条结构性判断（沿用并更新）

1. **「可自部署 + 一体化 + 中文平台嵌入」仍是不可复制的组合优势**：Airtable/Notion/Slack/Trello 不可自部署；NocoDB/NocoBase/RocketChat 单一用途且无钉钉/企微/飞书/微信嵌入。**定位不要改**。
2. **差距已从「有没有」变成「通不通」**：本批次 4 个并行核查共确认 **20+ 处真实断链/半桩**（见 §4），多为 1–3 人日级修复。**先把已建能力打通，再谈新增能力**。
3. **生态深度（机器人、应用市场、模板市场、联邦）是长期代差**，不适合在维护模式下追赶，应作为「开放 API + Webhook + 插件 SPI」的低成本替代路线。

---

## 4. 差距清单（按 2026-10-30 代码重排）

### 🔴 P0：已建能力「不通」，用户必然踩到（约 8–12 人日）

| # | 差距 | 现状与危害 | 证据 |
|---|---|---|---|
| P0-1 | **块编辑器裸 fetch 无 JWT** | 自动保存/模板/反向链接/应用模板全部走原生 `fetch`，apiClient 的 JWT 拦截器不生效 → 401；虽有 Alert 提示，但块编辑链路实际不可用 | `NotionStyleEditor.tsx:343-346` vs `api/client.ts:148-157` |
| P0-2 | **CRDT 初始化通道未订阅** | 前端只订 topic，后端初始状态发 `/user/queue/collab-init` → 新加入成员拿不到文档初始态；且合并成功分支 payload 只有 `state` 无 `update` | `stompClient.ts:108-129`；`RealtimeCollabController.java:47-63`；`RealtimeService.java:124-143` |
| P0-3 | **IM Pin 前后端路径断链** | 前端 `/im/messages/{id}/pin` vs 后端 `/im/messages/pins` → 置顶 404 | `features/im/api.ts:203-207`；`ImMessageController.java:69,86` |
| P0-4 | **阅后即焚 markBurnRead 无后端端点** | 前端调 `/im/messages/{id}/burn/read`，后端无 Mapping | `features/im/api.ts:261` |
| P0-5 | **Checklist API 组路径断链** | `features/project/api.ts` 写 `/projects/boards/...`，后端 `/api/project-boards/...` → 该组 API 404 | `features/project/api.ts:100-110`；`ProjectBoardController.java:169-355` |
| P0-6 | **日历翻月失效 + filter 语法不匹配** | 月视图硬编码 `new Date()`；前端发 `field_gte=value`，后端只认 `field:op:value` 且 op 无 gte/lte → 服务端过滤形同虚设 | `CalendarView.tsx:55-67,175-186`；`CollectionController.java:455-469` |

### 🟡 P1：企业落地前必须补（约 25–40 人日）

| # | 差距 | 证据 |
|---|---|---|
| P1-1 | **Slack bot token 内存态**（重启丢失、不加密、多副本不一致）；对照钉钉/企微/飞书/微信已走 TenantCredentialService + 加密托管，Slack 是唯一落后项 | `SlackAppService.java:58,146-148` |
| P1-2 | **feishu/wecom/wechat/slack 平台级配置三处缺位**（application.yml / docker-compose / k8s 均无对应段，encrypt-key 未声明），只能依赖租户级凭证表兜底 | `FeishuAppService.java:39`；`SlackAppService.java:46-51` |
| P1-3 | **K8s Secret 全为 CHANGE_ME 占位**，无 Sealed Secrets / Vault 接线 | `k8s/00-base.yaml:51-59` |
| P1-4 | **集成市场前端不可达**（IntegrationsPage 存在但 router.tsx 无路由）；PluginRegistry/安装状态为内存态 | `pages/IntegrationsPage.tsx:17`；`PluginRegistry.java:24` |
| P1-5 | **Wiki 混合检索未接前端**（pgvector 后端完整，前端搜索页只用 ILIKE 版）；FTS 索引在库但代码不查 tsvector | `WikiSearch.tsx:50`；`WikiSearchService.java:22-42` |
| P1-6 | **工作流缺 webhook / 表单提交触发**，设计器仅暴露 manual；**节点失败无重试/DLQ**（DLQ 基础设施只覆盖 AsyncTask） | `WorkflowDesigner.tsx:135,394`；`WorkflowEngine.java:127-131` |
| P1-7 | **MentionParser 死代码**，真实 @提及解析缺失（只匹配字面 @user/@channel/@here） | `im/MentionParser.java:26-31` |
| P1-8 | **无 PITR / WAL 归档**，备份仅逻辑备份，RPO 受备份间隔限制 | 全仓无 WAL 归档实现 |
| P1-9 | **API Key 无专属限流维度** | `GlobalRateLimitFilter.java:81-116` |
| P1-10 | **钉钉组织架构同步只拉根部门第一页**（cursor 固定 0），sync-status 无历史持久化 | `DingTalkOrgSyncService.java:158-162`；`DingTalkController.java:331` |
| P1-11 | **钉钉审批回调业务推进为注释空壳**（未调 WorkflowEngine，靠 Controller 侧补位） | `DingTalkApprovalService.java:135-139` |
| P1-12 | **移动端响应式覆盖窄**（仅 IM/表格/BI 三处 @media） | `styles.css:404,613,656` |
| P1-13 | **无灰度/蓝绿发布策略**（仅 HPA/PDB） | `k8s/02-backend-java.yaml:112-157` |
| P1-14 | **外部数据源仅 mysql/postgresql**，MySQL 驱动为反射可选依赖 | `ExternalDataSourceConnectionFactory.java:21,41-77` |

### 🟢 P2：体验与深度对标（可迭代）

| # | 差距 | 证据 |
|---|---|---|
| P2-1 | 字段类型缺 `user`、`button` | `FieldDef.java:49-51,92` |
| P2-2 | 甘特视图无后端类型（`type=gantt` 会 400）；ViewDesigner 只能建 table/kanban/detail/timeline | `ViewEntity.java:30`；`ViewDesigner.tsx:190-195` |
| P2-3 | 筛选 op 仅 7 个，缺 `>=`/`<=`/`in`/`notIn`/`between` | `CollectionController.java:455-471` |
| P2-4 | 公式函数 24 个，DATEDIFF 用 `Period.getDays()` 跨月算错；缺 MONTH/TODAY/IFERROR | `FormulaFunctions.java:458-465` |
| P2-5 | Wiki 版本对比（diff）无端点无 UI；Wiki 分享无 API；收藏/导出完全缺失 | `WikiPageService.java:424-444` |
| P2-6 | 批量插入 API 无前端调用点；CSV 导入逐条 insert 非批量事务 | `TableView.tsx:325`；`CollectionController.java:860-913` |
| P2-7 | 行级/字段级 ACL 为应用层过滤，未下推 SQL（limit 先裁剪再过滤，分页口径可能失真） | `CollectionController.java:432-436` |
| P2-8 | 看板新建卡片用 `prompt()` 且乐观插入 id='new' 不回填真实 ID | `BoardView.tsx:175,182` |
| P2-9 | labels 仅展示无管理 UI | `BoardColumn.tsx:100` |
| P2-10 | 计费无真实支付平台实现（无微信支付/支付宝/Stripe 签名与回调验签），HttpPaymentGateway 为通用 HTTP 适配且默认关闭 | `HttpPaymentGateway.java:74-76`；`application.yml:279` |
| P2-11 | 飞书签名比较非常量时间（`equals`），与钉钉 `MessageDigest.isEqual` 标准不一致 | `FeishuAppService.java:173` |
| P2-12 | 前端测试 **5 个 unhandled errors**（ErDiagram.test.tsx 等），虽 0 failed 但需在门禁中显式处理 | 实测 `npm run test:run` 输出 `Errors 5 errors` |

### ⚪ P3：生态代差（维护模式下不追赶）

- RocketChat 集成 0 代码实现（Java/Python/前端均无）
- IM 机器人框架、应用市场第三方上架/审核、Slack 级 App Directory
- 模板市场 / 社区生态、原生移动 App、Slack 联邦式跨组织互联

### 🔒 对外商业化 / 多租户前必补（前瞻性强制项）

| # | 必补项 | 现状 |
|---|---|---|
| 🔒-1 | 密钥托管（Vault/KMS + Sealed Secrets） | P1-1/P1-3 |
| 🔒-2 | PITR + 异地备份 | P1-8 |
| 🔒-3 | 真实支付网关 + 计费默认策略 | P2-10 |
| 🔒-4 | 灰度发布与回滚 | P1-13 |
| 🔒-5 | 租户配额计量闭环（配额过滤器已有，计量/账单联动未闭环） | `TenantQuotaFilter`、`BillingOverdueJob.java:18` |
| 🔒-6 | 合规（等保/数据导出与删除/隐私） | 需专项 |
| 🔒-7 | 渗透测试（重点：飞书签名比较、API Key 限流、行级 ACL 下推） | P2-11/P1-9/P2-7 |
| 🔒-8 | 移动端关键页面响应式 | P1-12 |

---

## 5. 分阶段补齐路线图

> 📌 **执行口径已收敛（2026-10-30）**：本文 §5 仅为能力差距的分层参考，**实际执行顺序以
> [`docs/PRODUCTIZATION_ROADMAP.md`](./PRODUCTIZATION_ROADMAP.md) v2 为唯一权威**（其 Wave 0.5 = 本文 Wave A，
> Wave 1–3 = 交付化工作，Wave 4 = 持续迭代）。本文 Wave C 的「能力扩容」项已降级为客户承诺驱动的白名单候选，
> 默认不做。

> 与 `docs/PRODUCTIZATION_ROADMAP.md`（展示 + 私有化 + 企业支持）对齐；本批次不改代码，以下为后续批次输入。

| 阶段 | 目标 | 内容 | 工作量 | 退出条件 |
|---|---|---|---|---|
| **Wave A：打通已建能力** | 200 人内部自用达标 | P0-1 ~ P0-6 + P2-12（前端 unhandled errors 收口） | 8–12 人日 | 块编辑/协同/Pin/焚毁/Checklist/日历全部端到端实测通过；补前后端端点契约测试防再次断链 |
| **Wave B：企业落地加固** | 私有化交付可信 | P1-1 ~ P1-7、P1-9 ~ P1-11（凭证托管、配置三处补齐、市场路由、混合检索接前端、webhook 触发、工作流重试、@提及接真、API Key 限流、钉钉同步分页与审批闭环） | 20–30 人日 | 凭证不落内存、配置三处一致、工作流可 webhook 触发且失败可重试 |
| **Wave C：对标深度补齐** | 体验对齐标杆 | P2-1 ~ P2-9（user/button 字段、甘特后端类型、筛选 op、公式函数、Wiki diff/分享/导出、批量插入、看板交互） | 20–30 人日 | 能力矩阵对应行由 🟡/❌ 转 ✅ |
| **Wave D：对外商业化** | 多客户可运营 | 🔒-1 ~ 🔒-8 | 8–12 周 | 密钥托管、PITR、灰度、支付、合规全部落地 |

**执行原则**：
1. Wave A 必须附「前后端端点契约测试」——本批次确认的 P0-3/P0-4/P0-5 全是「后端端点存在、前端路径写错」型断链，单测 mock 无法发现。
2. 每批交付沿用现行红线：禁弱化断言、禁恒真门禁、数字实测标注、不主动 commit。

---

## 6. 证据索引（核查方法，可复现）

```bash
# 断链类（后端端点 vs 前端路径）
grep -rn "pin\b" frontend/src/features/im/api.ts
grep -n "@PostMapping(\"/pins\")" backend-java/src/main/java/com/nocobase/im/ImMessageController.java
grep -rn "burn/read" backend-java/src/main/java --include=*.java        # 0 命中 = 端点缺失
grep -rn "projects/boards" frontend/src/features/project/api.ts
# 鉴权类
grep -n "fetch(" frontend/src/components/wiki/NotionStyleEditor.tsx      # 裸 fetch 无 Authorization
# 死代码类
grep -rn "MentionParser" backend-java/src/main/java --include=*.java     # 仅自身
grep -rn "IntegrationsPage" frontend/src/router.tsx                      # 0 命中 = 无路由
# 配置三处核查
grep -rn "feishu\.\|wecom\.\|wechat\.\|slack\." backend-java/src/main/resources/application.yml docker-compose.yml k8s/*.yaml
```

判定口径重申：**①存在文件 ≠ ②已接线 ≠ ③生产可用**。本报告所有 ✅/🟡/❌ 均基于调用链与配置实证，不接受「文件存在即完成」。

---

## 7. 给决策者的三句话

1. **别再按旧报告返工**：09-29 / 10-26 两份对标报告里 16 条「差距」已被代码推翻，继续按旧清单做会重复建设。以本文为唯一权威。
2. **先修 6 项 P0 打通已建能力（约 8–12 人日）**，比新增任何功能都更能提升企业可用性——现在的主要矛盾是「建好了但接不通」。
3. **定位不变：自部署 + 一体化 + 钉钉/企微/飞书/微信嵌入**；生态深度（机器人/应用市场/模板市场）属长期代差，维护模式下用「开放 API + Webhook + 插件 SPI」替代，不做正面追赶。
