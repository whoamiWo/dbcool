# 综合企业协作平台 — 上线就绪度与竞品差距评估

> 评估时间：2026-09-29
> 评估基线：`origin/main` = `79be8d3`
> 评估对象：DBCool（Java Spring Boot + Python FastAPI + React + Node CRDT；PostgreSQL/pgvector + Redis + RabbitMQ + MinIO）
> 对标产品：NocoDB、NocoBase、Slack、RocketChat、Mattermost、Trello、Airtable、Notion
> 目标形态：**先企业内部自用（约 200 人、自部署），后续对外**——本文按内部自用标准判定，并逐项标注「对外前必补」
> 方法论：全部结论基于**代码实证**（追溯调用链）与**实测命令**，不引用未验证的推断数字

---

## 一、执行摘要

### 结论：**有条件可上线（Conditional Go）** —— 不能直接上

| 场景 | 结论 | 说明 |
|---|---|---|
| **内部 POC / 演示** | ✅ 可上 | 门禁全绿、核心链路真接真 |
| **内部 200 人自用（目标）** | ⚠️ **有条件可上** | **必须先修 4 项 P0**，否则存在「数据丢失」与「核心入口不可用」风险 |
| **对外商业化 / 多租户** | ❌ 不可 | 缺容量基线、合规、租户级配额与 SLA 体系（见第六章「对外必补」） |

### 一句话判断

> **平台骨架与核心能力已成型且真接真可用**（数据引擎、Wiki、IM、看板、工作流、钉钉/企微/飞书集成均实测接线），
> 但**存在 4 项 P0 阻塞**：① 备份不覆盖 Postgres 主库（且 K8s 下必失败）② 钉钉登录主入口 POST/GET 不匹配（405）
> ③ 块编辑器自动保存打到不存在的端点且静默失败 ④ Huddle 语音内存路由与 K8s 多副本冲突。
> **这 4 项合计约 9–14 人日即可修复**，修完即达内部上线标准。

### 阻塞项计数

| 级别 | 数量 | 含义 |
|---|---|---|
| 🔴 **P0** | **4** | 阻塞内部 200 人上线（数据安全 / 核心入口 / 静默数据丢失 / 功能与部署架构冲突） |
| 🟡 P1 | 13 | 规模化或对外前必须补 |
| 🟢 P2 | 12 | 体验与对标差距，可迭代 |
| ⚪ P3 | 4 | 长期愿景 |
| 🔒 对外必补 | 9 | 商业化 / 多租户前强制项 |

### 本轮（09-29）相对旧报告的重要修正

旧 `LAUNCH_READINESS_REPORT.md`（基线 `a2ec149`）有若干结论已被本轮工作**推翻**，本文以实证为准：

| 旧结论 | 修正后 |
|---|---|
| 「CRDT 未实现」 | **部分实现**：Node CRDT 服务 + 前端 Yjs/STOMP 已接线，但 `crdt.service.url` 无任何配置 → 合并服务永不调用；且前端是**整篇替换**非字符级（详见 3.3） |
| 「向量检索部分就绪 / 默认哈希无语义」 | **已端到端跑通真语义**：sentence-transformers 768 维；实证 `hybrid` 语义召回 total=1 / 纯 FTS total=0 |
| 「多租户为业务层过滤（架构性风险）」 | **比旧认知更强**：已实现 **Schema 级隔离**（`SchemaTenantConnectionProvider` 每次取连接 `SET search_path`、归还复位）+ 业务层双保险 |
| 「Wiki 全员 403」 | **已修复**：V40 迁移为 admin 播种 wiki_page/knowledge_base/wiki_category 策略 |
| 「容量基线缺失（压测待 staging）」 | **仍缺失**：`perf/load-test.js` 只压 2 个只读接口，无写入路径 |

---

## 二、评估基线（全部实测执行）

| 项 | 命令 | 实测结果 |
|---|---|---|
| 后端单测 | `mvn -o test` | **Tests run: 1210, Failures: 0, Errors: 0, Skipped: 0** ✅ BUILD SUCCESS |
| 前端单测 | `npm run test:run` | **35 files / 267 passed** ✅ |
| 类型检查 | `npx tsc --noEmit` | **0 errors**（输出为空）✅ |
| E2E | `npx playwright test` | **64 passed**（chromium + firefox）✅ |
| 容器健康 | `docker compose ps` | postgres / redis / rabbitmq / minio / backend-java / backend-python **全部 healthy** ✅ |
| 端点冒烟 | `curl` | `/api/health` **200**、Python `/api/health` **200** ✅ |

> 门禁数字由本次实际命令执行得出；前端不可用 `npx vitest run`（会进 watch 模式）。

---

## 三、现有能力盘点（8 维度 × 三层判定）

**判定口径**（本项目既往踩坑，严格执行）：
1. **存在文件** — 有 Service/Controller/组件
2. **已接线** — 定义被真实调用链触发（非"定义后从未调用"的坏死代码）
3. **生产可用** — 无硬编码占位、配置可注入、有测试覆盖

### 3.1 总览

| # | 维度 | 对标 | ①文件 | ②接线 | ③生产可用 | 一句话 |
|---|---|---|---|---|---|---|
| 1 | 数据/表格 | Airtable / NocoDB | ✅ | ✅ | 🟡 | 引擎最扎实，字段类型与视图级分组是短板 |
| 2 | 文档/Wiki | Notion | ✅ | 🟡 | 🟡 | 版本/权限/搜索真接真；**块编辑自动保存断裂** |
| 3 | 实时协作 CRDT | Notion | ✅ | 🟡 | ❌ | **非字符级**（整篇替换）；CRDT 服务未配置 |
| 4 | IM | Slack / RocketChat / Mattermost | ✅ | ✅ | 🟡 | 主链路真；**高级搜索是死代码** |
| 5 | 看板/项目 | Trello | ✅ | ✅ | 🟡 | 拖拽真落库；Checklist/Label/dueDate 前端未接 |
| 6 | 工作流/自动化 | 各产品 | ✅ | ✅ | 🟡 | 表达式引擎真被调用；**存在第二套死实现** |
| 7 | 第三方集成 | Slack App Directory | ✅ | 🟡 | 🟡 | 钉钉/企微/飞书真；Slack/Mattermost 半桩；**无 RocketChat** |
| 8 | 运维/多租户 | 企业级 | ✅ | ✅ | 🟡 | Schema 隔离+可观测性完整；**备份不含主库** |

### 3.2 维度 1：数据/表格（对标 Airtable / NocoDB）

**已接线证据**（可引用）：

| 能力 | 证据 |
|---|---|
| 公式字段 | `CollectionService` L417：`record.put(f.name(), FormulaEngine.evaluate(s, record))` ✅ 真计算，非占位 |
| 汇总/查找 | `CollectionService` L440 `RollupEngine.aggregate(...)`、L452 `RollupEngine.lookup(...)` |
| 关联展开 | `CollectionController` L284/347 → `RelationResolver.expandRelations(...)` |
| 筛选 | `CollectionController#listRecords` L277 `parseFilters`；op 白名单 7 个 + 字段名正则防注入 |
| 导入导出 | `TableView#exportCsv` → `GET /export`；`#handleImport` → `POST /import`（含逐行失败收集） |
| Gallery / Calendar / Timeline | 均经 `/views/:id` + `/records` 真接后端 ✅ |
| **Gantt** | `GanttView` → `projectApi.gantt` → `ProjectController L97 @GetMapping("/{projectId}/gantt")` → `ProjectService.listGantt` ✅ **历史"loadData 从未调用"已修复** |

**差距**：

| 项 | 现状 |
|---|---|
| 字段类型仅 **13 种** | `FieldDef.isValidType`：text/number/boolean/date/datetime/select/multiSelect/belongsTo/hasMany/formula/rollup/lookup/attachment。缺 currency、percent、rating、duration、user/collaborator、url、email、phone、button、createdTime/lastModifiedTime 等 |
| **无视图级 group by** | 后端 `CollectionService#aggregate` 支持 `groupByFields`，但**只被 BI 报表调用**；看板分组是前端内存分组 |
| CalendarView 只能看当前月 | `buildCalendar()` 硬编码 `new Date()`，**无翻月** |
| Timeline 区间过滤是应用层 | Java Stream 过滤（注释自认），大集合性能不可接受 |
| 批量 API 前端 **0 调用** | 后端 L582/597/612 有 `batch-insert/update/delete` |
| Gallery/Calendar/Kanban `limit=500` | 无分页、无虚拟滚动 |

### 3.3 维度 2：文档/Wiki + 实时协作（对标 Notion）

**已接线**：版本快照（`WikiPageService` L150 更新前 `createVersion`）、版本对比 UI（`WikiVersionHistory` → `VersionDiff`）、反向链接端点、权限（`WikiPermissionService` 被 5 处真实调用）、FTS 中文分词（jieba）+ pgvector 混合检索（**本轮端到端实证**）。

🔴 **关键缺陷 1 — 块编辑器自动保存打到不存在的端点（静默失败）**

```ts
// NotionStyleEditor.tsx L265
await fetch(`/api/wiki/blocks/batch-upsert`, { method:'POST', ... })
```
后端全仓搜索 `batch-upsert` **0 命中**（后端只有 `POST /pages/{id}/blocks`、`PUT /blocks/{id}`、`PUT /pages/{id}/blocks/reorder`）。
→ 每 2 秒防抖后**必然 404**，且仅 `console.error('保存失败:')` 静默吞掉。**用户以为在块级编辑并自动保存，实际 Block 树不落库。**

🔴 **关键缺陷 2 — 模板列表端点不匹配**：前端 `GET /api/wiki/templates`，后端只有 `/kb/{kbId}/templates` → 404，模板下拉永远为空。

🔴 **关键缺陷 3 — 反向链接渲染会运行时报错**：前端未 unwrap `{code:0,data:[...]}` 信封直接 `setBacklinks(data)`，随后 `backlinks.map()` → `is not a function`。

🔴 **关键缺陷 4 — CRDT 是「整篇替换」而非字符级**

```ts
// CollabEditor.tsx L142-149
ytext.delete(0, ytext.length);   // 全删
ytext.insert(0, value);          // 全插
```
文件头注释自认"整篇替换……字符级合并需引入编辑器绑定，后续增强"。→ **两人同编辑一段 = 后写覆盖先写**，不是 CRDT 合并。

🔴 **关键缺陷 5 — CRDT 服务端合并服务实际未被调用**

`RealtimeService` L50 `@Value("${crdt.service.url:}")`，全仓（含 `application.yml`、`k8s/*.yaml`、`docker-compose.yml`）**无任何赋值** → `crdtWebClient = null` → 永远走"降级：增量透传"。
`k8s/11-crdt-service.yaml` 部署了 CRDT 服务（port 3100），但 `02-backend-java.yaml` **没有任何 env 指向它** → **服务部署了没人调**。

🔴 **关键缺陷 6 — 即便配上 url，协同也会静默失效**：合并分支广播 payload 是 `{docId,userId,state,version,ts}`，**无 `update` 字段**；前端 L119 `if (!msg.update) return;` → 远端更新被静默丢弃。且前端只订阅 `/topic/...`，从未订阅后端发送 init 的 `/user/queue/collab-init`。

### 3.4 维度 4：IM（对标 Slack / RocketChat / Mattermost）

| 能力 | 判定 | 证据 |
|---|---|---|
| 频道 / 私信 / 线程 | ✅ | `ImMessageController#thread` → `findByParentIdOrderByCreatedAtAsc` |
| Reaction | ✅ 真 | `ReactionService` + 前端 `toggleReaction`/`groupReactions` |
| 已读回执 | ✅ 真 | `ImMessageReadEntity`、`unread`/`markRead` |
| 附件 / 斜杠命令 / Pin / 在线状态 | ✅ | MinIO、`SlashCommandRegistry`、`PinService`、`PresenceService` |
| **Huddle 语音** | ✅ **真接真** | 原生 WS `/ws/huddle` + JWT 拦截；前端 `RTCPeerConnection`、`getUserMedia`、`createOffer/Answer` |
| 消息搜索 | 🟡 | 接线的是 `MessageService#search`（**LIKE 全表扫**）；**高级搜索 `MessageSearchService` 全后端 0 注入点（死代码）** |
| 富文本 / @提及 | 🟡 | `RichTextParser` 文件头自述"暂不接入生产代码"；@提及只落到 LIKE 匹配 |

🔴 **Huddle 多副本冲突**：`HuddleSignalingHandler` 用 `Map<String, Set<WebSocketSession>>` 内存路由（注释自认"多实例需迁移 Redis pub/sub"），而 `k8s/02-backend-java.yaml` 配 **replicas: 3 + HPA 到 10** → **生产多副本下语音必然时好时坏**。

### 3.5 维度 5：看板（对标 Trello）

✅ **拖拽真落库**（全链路已验证）：
```
BoardColumn(useSortable containerId) → BoardView#handleDragEnd
→ apiClient.post('/api/project-boards/cards/move')
→ ProjectBoardController L137 → CardMoveService#moveCard(@Transactional 重排 sortOrder)
```
差距：Checklist/Label/dueDate **后端有 API、前端无 UI**；两套看板并存（`pages/KanbanView` 只读 vs `features/project/BoardView` 可拖拽）；新建卡片用 `prompt()` 且 id 硬编码 `'new'`。

### 3.6 维度 6：工作流/自动化

✅ **表达式引擎真被调用**：`common/ExpressionEvaluator`（Aviator）→ 注入 `workflow/handler/ConditionNodeHandler` L27 → `evaluator.evaluateBoolean(...)` L68；安全约束（长度上限 1000、不注册自定义函数、异常降级 false）。
✅ 死信真接真：`DeadLetterTaskListener` → `INSERT INTO mq_dead_letter_alert`，失败不 ack。
✅ 重试：`AmqpConfig` `x-max-attempts`，默认 5。

🔴 **存在第二套死实现**：`workflow/ExpressionEvaluator.java` 标注 `@Component("workflowExpressionEvaluator")`，全仓 **0 引用**，与 `common/ExpressionEvaluator` 重复。
🟡 **定时触发内存态**：`WorkflowScheduler` `@Scheduled(fixedDelay=60s)`，触发时间记内存 → **3 副本下每周期触发 3 次**，重启即触发。
🟡 **无 Cron 表达式**（只有 `intervalMinutes`）；无 webhook / 表单提交 / 手动触发。

### 3.7 维度 7：第三方集成

| 连接器 | 判定 | 证据 |
|---|---|---|
| **钉钉** | ✅ 真接真 | `@Value` 注入 4 项配置；真 HTTP `oapi.dingtalk.com`；**真落库建号**（按 unionid 幂等）；审批回调 **HmacSHA256 + 时间窗 + 常量时间比较** |
| **企业微信** | ✅ 真接真 | `@Value` 注入 4 项；5 处真 HTTP（`qyapi.weixin.qq.com`）；注入 Spring `RestTemplate` |
| **飞书** | ✅ 真接真 | `@Value` 注入 4 项；`open.feishu.cn` 4 处真 HTTP；`SHA256(timestamp+nonce+encrypt_key+body)` 签名 |
| **Slack** | 🟡 半桩 | 配置注入+真 HTTP+**签名校验真**。但：入站事件只打日志（消费者不存在）；token 存 `ConcurrentHashMap` 内存（自认"应持久化到 Vault"） |
| **Mattermost** | 🟡 半桩 | 真发消息；但 `verifyWebhookToken` **未配置即 `return true`（放行）**；入站只打日志 |
| **RocketChat** | ❌ **完全不存在** | 全仓搜索仅命中 7 个 **Markdown 文档**（规划/变更日志），**Java/Python/前端代码 0 处实现** |
| **集成市场** | ❌ 前端零接线 | 后端 `IntegrationMarketController` 3 端点已接真 `PluginRegistry`；前端 `integrationApi` 定义后 **0 引用**，`router.tsx` 无路由 |

🟡 **钉钉组织架构同步是桩**：`DingTalkAppService#syncOrganization` 只 `log.info` 返回"已触发"，而前端 `DingTalkPage` 有"立即同步"按钮 → **点了只打日志**。

### 3.8 维度 8：运维与多租户

✅ **多租户双保险**：
- **Schema 级**：`SchemaTenantConnectionProvider` 每次取连接 `SET search_path TO <schema>, public`，归还前复位（防连接池串租户），schema 名正则白名单防注入
- **业务层**：Repository 普遍带 `tenantId` 过滤；`TenantSubscriptionInterceptor` 保证 STOMP 跨租户隔离

✅ **审计日志真接真**：`AuditService.log` 调用点 **39 处**（Wiki 16、Workflow 5、Collection 3）。
✅ **可观测性完整**：`k8s/06-monitoring.yaml`（ServiceMonitor + 5 条 PrometheusRule）、`09/10-grafana`、`07-logging`（FluentBit+Loki）、`08-tracing`（Jaeger）、三探针 + HPA(3→10) + PDB。

🔴 **备份名义有、实质不覆盖主库**：
`_DB_PATHS = [Path("alerts.db")]` + media + Redis dump → **PostgreSQL 主库（存放 collections/records/wiki/IM/workflow 全部业务数据）不在备份范围**；
且 `_redis_snapshot()` 硬编码 `docker exec nocobase-redis redis-cli --rdb -` → **K8s 环境下必然失败**（无 docker 命令）→ 整个备份任务抛错。

🔴 **无全局 API 限流**：仅 `workflow/TriggerRateLimiter`（Redis ZSET）被调用；**登录、消息发送、文件上传、API Key 均无全局限流**。

🟡 **压测覆盖极薄**：`perf/load-test.js` 只压 2 个接口（`GET /api/collections`、`GET /api/health`），20→50 VU，**无写入路径、无 IM/Wiki/工作流**。

### 3.9 专项：移动端（对标 Slack/Notion Mobile）

| 判定 | 证据 |
|---|---|
| ①文档 ✅ | `MOBILE_ADAPT.md` 存在 |
| ②接线 🟡 | 仅 2 处生效：`AppLayout.tsx` L196 移动端底部导航（真实施）；`styles.css` L404 一个 `@media` 块（**仅覆盖 IM**） |
| ③生产可用 ❌ | **40+ 业务页面均为固定桌面布局**：`TableView` 横向滚动、`ProjectPage` 固定列宽 280-320px、`GanttView` 固定 240px 标题列 |

`MOBILE_ADAPT.md` 自述"已支持响应式布局、现有页面支持 768px+"——**与实际不符**；其列出的 4 项待办仅"底部导航"完成。

### 3.10 🔴 假接线 / 坏死代码清单（按严重度）

| # | 位置 | 问题 |
|---|---|---|
| 1 | `NotionStyleEditor.tsx` L265 | `POST /api/wiki/blocks/batch-upsert` 后端 0 命中 → 块编辑自动保存 404 静默失败 |
| 2 | `RealtimeService.java` L50 | `crdt.service.url` 全仓无配置 → CRDT 服务永不调用；即便配上 payload 无 `update` 也被前端丢弃 |
| 3 | `DingTalkLoginPage.tsx` L27 | **POST** 打到 **@GetMapping** 的 `/api/dingtalk/auth-url` → **405**（主入口坏） |
| 4 | `MessageSearchService.java` | 全后端 0 注入点，PHASE 57 成果完全未被调用 |
| 5 | `backup.py` L37 | 备份不含 Postgres 主库；`docker exec` 硬编码在 K8s 下必失败 |
| 6 | `workflow/ExpressionEvaluator.java` | `@Component("workflowExpressionEvaluator")` 0 引用，与 common 版重复 |
| 7 | `DingTalkAppService#syncOrganization` L178 | 只打日志的桩，前端却有"立即同步"按钮 |
| 8 | `frontend/src/api/integrations.ts` L108 | `integrationApi` 0 引用 → 集成市场无 UI |
| 9 | `NotionStyleEditor.tsx` L319 | 模板端点不匹配 → 模板永远加载不出 |
| 10 | `components/wiki/{WikiAttachmentUpload,WikiDiffViewer}.tsx` | 0 import 的死组件 |
| 11 | `CollectionController` L582/597/612 | `batch-*` 前端 0 调用 |
| 12 | `frontend/src/pages/Untitled-1` | **2.41KB LLM 输出文本误提交进源码树**（源码污染） |
| 13 | `RichTextParser.java` L19 | 自述"暂不接入生产代码" |
| 14 | `MOBILE_ADAPT.md` | 文档称"已支持响应式"，实际仅 2 处生效 |

---

## 四、竞品能力矩阵

图例：✅ 持平/具备 ｜ 🟡 部分/弱 ｜ ❌ 缺失 ｜ ➖ 该产品不适用于此维度

| 能力维度 | Airtable | Notion | NocoDB | NocoBase | Slack | RocketChat | Mattermost | Trello | **DBCool（本项目）** |
|---|---|---|---|---|---|---|---|---|---|
| **数据/表格引擎** | ✅ 强 | 🟡 数据库弱 | ✅ | ✅ | ➖ | ➖ | ➖ | ➖ | ✅ 引擎扎实（字段类型少） |
| **字段类型丰富度** | ✅ 20+ | 🟡 | ✅ | ✅ | ➖ | ➖ | ➖ | ➖ | 🟡 **仅 13 种** |
| **视图多样性** | ✅ 强 | ✅ | ✅ | ✅ | ➖ | ➖ | ➖ | ✅ 看板 | 🟡 有但部分残（日历无翻月） |
| **视图级分组 group by** | ✅ | ✅ | ✅ | ✅ | ➖ | ➖ | ➖ | 🟡 | ❌ **仅 BI 报表有** |
| **公式/汇总字段** | ✅ 强 | 🟡 | ✅ | ✅ | ➖ | ➖ | ➖ | ➖ | ✅ **真计算（已接线）** |
| **文档/知识库** | ➖ | ✅ 标杆 | 🟡 | 🟡 | ➖ | ➖ | ➖ | ➖ | ✅ 版本/权限/搜索真，块编辑断 |
| **块级编辑（Notion 式）** | ➖ | ✅ 标杆 | ❌ | 🟡 | ➖ | ➖ | ➖ | ➖ | 🟡 **自动保存 404，实为整篇** |
| **实时协同（字符级）** | 🟡 | ✅ 标杆 | ❌ | ❌ | ➖ | ➖ | ➖ | ➖ | ❌ **整篇替换 + 合并服务未启用** |
| **IM 频道/线程** | ➖ | ➖ | ➖ | ➖ | ✅ 标杆 | ✅ | ✅ | ➖ | ✅ 频道/线程/reaction/已读真 |
| **IM 高级（工作流/机器人/应用市场）** | ➖ | ➖ | ➖ | ➖ | ✅ 极强 | ✅ | 🟡 | ➖ | 🟡 斜杠命令有，机器人/市场无 |
| **语音/Huddle** | ➖ | ➖ | ➖ | ➖ | ✅ | ✅ | 🟡 | ➖ | ✅ 真 WebRTC（多副本冲突） |
| **看板/卡片** | 🟡 | 🟡 | 🟡 | 🟡 | ➖ | ➖ | ➖ | ✅ 标杆 | ✅ 拖拽真落库（Checklist 无 UI） |
| **工作流/自动化** | ✅ 强 | 🟡 | 🟡 | ✅ | ✅ Workflow Builder | 🟡 | 🟡 | ✅ Butler | 🟡 有引擎，**无 Cron、定时内存态** |
| **集成生态 / 应用市场** | ✅ 强 | 🟡 | 🟡 | ✅ 插件 | ✅ App Directory 极强 | ✅ | 🟡 | ✅ Power-Ups | 🟡 后端有，**前端零接线** |
| **权限精细化** | ✅ | ✅ | ✅ | ✅ 强 | 🟡 | 🟡 | 🟡 | 🟡 | ✅ ACL 字段/行/操作级 |
| **多租户隔离** | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | ✅ **Schema 级 + 业务层双保险** |
| **自部署能力** | ❌ 仅云 | ❌ 仅云 | ✅ | ✅ | ❌ 仅云 | ✅ 强 | ✅ | ❌ 仅云 | ✅ **自部署（本项目核心优势）** |
| **钉钉/微信/企微嵌入** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ **钉钉/企微/飞书真接真** |
| **移动端** | ✅ | ✅ | 🟡 | 🟡 | ✅ 强 | ✅ | ✅ | ✅ | ❌ **仅底部导航 + IM 3 条 CSS** |
| **AI 能力** | 🟡 | ✅ Notion AI | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | ➖ | 🟡 ask/大纲/润色 + RAG 混合检索真 |
| **中文场景优化** | ❌ | ❌ | 🟡 | 🟡 | ❌ | 🟡 | 🟡 | ❌ | ✅ **jieba 分词 + 钉钉/企微/飞书** |

### 矩阵解读（三条关键判断）

1. **「不可自部署」是本项目最大的结构性优势**：Airtable / Notion / Slack / Trello **仅提供云端 SaaS**，数据不出企业内网做不到。本项目 + NocoDB / NocoBase / RocketChat / Mattermost 属「可自部署阵营」。对 200 人企业内部、有数据合规要求的场景，**这一条足以否决 Notion/Airtable/Slack**。

2. **「一体化整合度」是第二优势，但单项深度不足**：
   - 单一用途标杆各有深厚积累（Slack 的 App Directory、Notion 的块编辑器与协同、Airtable 的字段与视图、Trello 的看板交互）
   - 本项目是**广度覆盖型**（数据+文档+IM+看板+工作流+集成一体），**单项深度普遍弱于对应标杆**
   - 结论：**定位应打「一体化 + 自部署 + 钉钉嵌入」，避免与单项标杆拼深度**

3. **RocketChat 是名不副实的对标项**：代码里 **0 实现**，仅出现在规划文档中。若确需对标其能力（开源 IM + 自部署 + 联邦），需从零建设；但本项目 IM 主链路（频道/线程/reaction/已读/语音）已基本覆盖其常用子集，优先级不高。

---

## 五、上线结论与 P0 阻塞项

### 结论：内部 200 人自用 —— **有条件可上线（修完 4 项 P0 即可）**

**可上线的依据**：
- 门禁全绿（1210 / 267 / 0 / 64），容器全 healthy
- 核心场景链路真接真：数据增删改查+公式汇总、Wiki 读写+版本+权限+搜索、IM 收发+线程+reaction+已读、看板拖拽落库、工作流条件分支+死信、钉钉/企微/飞书集成
- 多租户 **Schema 级隔离**（强于旧报告认知）、审计日志 39 处、可观测性完整

**不能直接上的依据**：以下 4 项 P0。

---

### 🔴 P0-1　备份不覆盖 PostgreSQL 主库（数据安全）

| 项 | 内容 |
|---|---|
| 现状 | `_DB_PATHS = [Path("alerts.db")]` + media + Redis；**主业务库 Postgres 不在备份范围**；`_redis_snapshot()` 硬编码 `docker exec nocobase-redis ...` |
| 危害 | ① 真正的业务数据（collections/records/wiki/IM/workflow）**无备份，误删不可恢复**；② K8s 环境下 `docker exec` 抛 `FileNotFoundError` → **整个备份任务失败**（演练成功的是 SQLite/文件/Redis，掩盖了主库缺失） |
| 修复 | `pg_dump` 纳入备份；Redis 快照改为容器内执行或 `redis-cli` 直连（去 docker 依赖）；补主库恢复演练并量化 RPO/RTO |
| 工作量 | 3–5 人日 |

### 🔴 P0-2　钉钉登录主入口 405（POST → GET-only）

| 项 | 内容 |
|---|---|
| 现状 | `DingTalkLoginPage.tsx` L27 用 `fetch('/api/dingtalk/auth-url', {method:'POST'})`；后端 `DingTalkController` L69 是 `@GetMapping("/auth-url")` |
| 危害 | **钉钉嵌入是本项目核心定位**（钉钉优先），而 `/auth/dingtalk` 是主登录入口之一 → 用户点登录必然失败（同功能另一条路径 `DingTalkPage` 用 GET 跳转是正确的，说明是回归遗漏） |
| 修复 | 前端改 GET（对齐 `DingTalkPage` 正确写法）；建议补契约测试防再次回归 |
| 工作量 | **0.5 人日**（成本极低，收益极高） |

### 🔴 P0-3　块编辑器自动保存 404 且静默失败（假完成 + 数据丢失）

| 项 | 内容 |
|---|---|
| 现状 | `NotionStyleEditor.tsx` L265 每 2 秒防抖 `POST /api/wiki/blocks/batch-upsert`；后端 **0 命中**；失败仅 `console.error` |
| 危害 | 用户以为在用 Notion 式块编辑并自动保存，**实际 Block 树从不落库**，仅整篇 markdown 生效；且**无任何用户可见报错**——最坏的"假完成" |
| 修复 | 二选一：后端补 `batch-upsert` 端点（推荐，对齐前端语义）或前端改用现有 `POST /pages/{id}/blocks`；失败必须上抛 UI 提示 |
| 工作量 | 2–3 人日 |

### 🔴 P0-4　Huddle 语音内存路由与 K8s 多副本冲突

| 项 | 内容 |
|---|---|
| 现状 | `HuddleSignalingHandler` 用进程内 `Map<String, Set<WebSocketSession>>`（注释自认需迁移 Redis pub/sub）；而 `k8s/02-backend-java.yaml` 配 **replicas: 3 + HPA 到 10** |
| 危害 | 多副本下 WebRTC 信令只在本进程广播 → **同一房间的用户连到不同副本就互相看不见**，语音功能随机失效 |
| 修复 | ① 迁移 `RedisStompBridge` 式 Redis pub/sub（正解）；② 短期规避：Huddle 相关部署单副本或会话粘滞（sticky session） |
| 工作量 | 3–5 人日（Redis 方案）／0.5 人日（单副本规避） |

> **P0 合计：约 9–14 人日（1 人约 2–3 周）**。修完即达内部自用上线标准。

---

## 六、差距清单（P0–P3 + 对外必补）

### 🟡 P1（规模化 / 对外前必须补）

| # | 差距 | 现状 | 工作量 |
|---|---|---|---|
| P1-1 | **CRDT 真字符级协同** | 整篇替换；`crdt.service.url` 未配置；payload 缺 `update` 字段；前端未订阅 `/user/queue` | 10–15 人日 |
| P1-2 | **全局 API 限流** | 仅工作流触发限流；登录/消息/上传/API Key 无防护 | 3–5 人日 |
| P1-3 | **定时工作流去内存态** | 3 副本重复触发 3 次、重启即触发 | 3–5 人日 |
| P1-4 | **Cron 表达式** | 只有固定 `intervalMinutes` | 2–3 人日 |
| P1-5 | **移动端响应式** | 仅底部导航 + IM 3 条 CSS；40+ 页面桌面布局 | 15–20 人日 |
| P1-6 | **IM 搜索索引化** | LIKE 全表扫；`MessageSearchService`（高级搜索）0 接线 | 3–5 人日 |
| P1-7 | **Slack/Mattermost 入站消费者** | 入站事件只打日志；token 内存态；Mattermost 未配置即放行 | 5–8 人日 |
| P1-8 | **压测覆盖** | 只压 2 个只读接口；无写入/IM/Wiki/工作流 | 3–5 人日 |
| P1-9 | **钉钉组织架构同步接真** | 桩（只打日志），前端有按钮 | 3–5 人日 |
| P1-10 | **集成市场 UI** | 后端完整，前端 0 接线 | 5–8 人日 |
| P1-11 | **字段类型扩展** | 13 种 → 补齐常用 10+ 种 | 8–12 人日 |
| P1-12 | **视图级 group by / 日历翻月** | 分组仅 BI 有；日历硬编码当前月 | 5–8 人日 |
| P1-13 | **容量基线与扩容阈值** | 无 QPS/P99/并发拐点数据 | 2–3 人日（依赖 P1-8） |

### 🟢 P2（体验对标，可迭代）

| # | 差距 | 现状 |
|---|---|---|
| P2-1 | 模板端点不匹配（`/templates` vs `/kb/{kbId}/templates`） | 前端 404，模板为空 |
| P2-2 | 反向链接未 unwrap 信封 → `.map is not a function` 运行时报错 | 同文件写法不一致 |
| P2-3 | `RichTextParser` 未接线（自述） | @提及无富文本渲染管线 |
| P2-4 | 死代码清理：第二套 `ExpressionEvaluator`、2 个死组件、`pages/Untitled-1` 源码污染 | 维护负担 |
| P2-5 | 两套看板并存（`KanbanView` 只读 / `BoardView` 可拖拽） | 产品割裂 |
| P2-6 | Timeline 应用层过滤性能 | 大集合不可接受 |
| P2-7 | Gallery/Calendar/Kanban `limit=500` 无分页/虚拟滚动 | 数据量一大即卡 |
| P2-8 | 批量操作 API 前端未接线 | API 存在、UI 未接 |
| P2-9 | 看板 Checklist / Label / dueDate 前端 UI | 后端有、前端无 |
| P2-10 | 新建卡片用 `prompt()` 且 id 硬编码 `'new'` | 体验差、易冲突 |
| P2-11 | RocketChat 对标能力 | 代码 0 实现（优先级低） |
| P2-12 | `crdt-service` 无 Dockerfile、未入 compose；`CORS *` | 部署与安全项 |

### ⚪ P3（长期愿景）

| # | 差距 |
|---|---|
| P3-1 | 原生移动 App（RN/Flutter） |
| P3-2 | 集成市场生态（第三方应用上架、审核、计费） |
| P3-3 | AI 深度（文档自动生成、智能问答规模化、Copilot 式辅助） |
| P3-4 | 模板市场 / 社区生态 |

### 🔒 对外商业化 / 多租户前必补（前瞻性检查项）

> 内部自用时以下项可暂缓；**一旦对外（多客户 / 多租户 / 商业化）则全部为强制项**。

| # | 必补项 | 说明 |
|---|---|---|
| 🔒-1 | **容量基线与 SLA** | 需完整压测（含写入/IM/Wiki）+ 扩容阈值 + SLA 承诺 |
| 🔒-2 | **租户级配额与计量** | 存储/API 调用/席位配额、用量计量（当前无） |
| 🔒-3 | **密钥托管** | Slack token 等存内存 → 必须迁 Vault/KMS（当前内存态，重启丢失、多副本不一致） |
| 🔒-4 | **合规** | 等保测评、数据出境、隐私合规、用户数据导出/删除（当前无） |
| 🔒-5 | **审计留痕完整性** | 现有 39 处覆盖 Wiki/Workflow/Collection；**IM、权限变更、导出操作未覆盖** |
| 🔒-6 | **渗透测试与安全加固** | 含 Mattermost「未配置即放行」类漏洞的系统性排查 |
| 🔒-7 | **备份异地与 PITR** | 主库备份（P0-1）+ 跨区域存储 + 时间点恢复 |
| 🔒-8 | **多租户隔离持续审计机制** | 虽已 Schema 隔离，新增接口仍需归属校验自动化检查 |
| 🔒-9 | **灰度发布与回滚** | 当前 K8s 有 HPA/PDB，但无蓝绿/金丝雀与数据迁移回滚策略 |

---

## 七、钉钉 / 微信嵌入专章

### 7.1 现状判定

| 子能力 | 判定 | 证据 / 问题 |
|---|---|---|
| 扫码登录（后端） | ✅ 真 | `DingTalkAppService#getAuthUrl` 拼 `login.dingtalk.com/oauth2/auth`；`exchangeCode` 真换 token 并按 unionid **幂等建号**（非占位 UUID） |
| 扫码登录（前端主入口） | 🔴 **坏** | `DingTalkLoginPage` **POST** → 后端 **GET** → **405**；而 `DingTalkPage` 用 `window.location.href`（GET）✅ 正确 → **同一功能两条路径，一条坏** |
| 免登（H5 微应用） | 🟡 半接线 | `useDingTalkAuth` 监听 URL `?code=` 回调；但仅在 `isInDingTalk()` 为真时触发；未引入钉钉 JS-SDK（`dingtalk-jsapi`），需确认是否仅 UA 嗅探 |
| 消息发送 / 群会话 | ✅ 后端真 | `DingTalkMessageService`、`DingTalkGroupService` |
| **审批回调** | ✅ **真接真** | `DingTalkController` L187 → HmacSHA256(appSecret, timestamp) + **时间窗** + **常量时间比较**；缺头/签名不符均 401 |
| 审批发起/查询 | ✅ | `POST /approval`、`GET /approval-status` |
| 组织架构同步 | ❌ **桩** | 只 `log.info` 返回"已触发"；前端"立即同步"按钮点了只打日志 |
| 企业微信 | 🟡 | `WeComLoginPage` 已挂路由；真 HTTP 5 处；**未核对 auth-url method 是否同样存在 POST/GET 错配风险** |
| 微信（个人） | 🟡 | `WeChatPersonalDispatcher` 已实现（通知渠道），非 IM 级嵌入 |

### 7.2 缺口与落地路径

| 优先级 | 缺口 | 落地建议 |
|---|---|---|
| **P0** | 钉钉登录 405 | 前端改 GET；补端点契约测试（成本 0.5 人日） |
| **P1** | 组织架构同步是桩 | 接真 `DingTalkOrgSyncService`（部门/用户落库、增量同步、失败重试） |
| **P1** | 免登依赖 UA 嗅探、无 JS-SDK | 引入 `dingtalk-jsapi`，用 `dd.runtime.permission.requestAuthCode` 取 code，替代 UA 判断 |
| **P1** | 消息卡片 / 交互式卡片 | 现有只能发文本/群消息；补齐 ActionCard、OA 消息、可交互审批卡片 |
| **P2** | 微信侧仅通知渠道 | 若要"嵌入微信"，需企业微信 H5 应用（已有基础）或微信小程序（当前无） |
| **P2** | 前端类型与后端返回不一致 | `dingtalkApi.getSyncStatus` TS 类型声明与后端 `/sync-status` 实际字段不匹配（前端类型是猜的） |

---

## 八、分阶段补齐路线图

### 阶段 0：达到内部上线（**2–3 周，1 人**）—— 只修 P0

| 任务 | 工作量 | 退出条件 |
|---|---|---|
| P0-2 钉钉登录 405 | 0.5 人日 | 钉钉扫码登录端到端跑通 + 契约测试 |
| P0-3 块编辑 `batch-upsert` | 2–3 人日 | 块编辑保存落库，失败有 UI 提示 |
| P0-1 备份覆盖 Postgres 主库 | 3–5 人日 | `pg_dump` 入备份 + **主库真实恢复演练成功** |
| P0-4 Huddle 多副本 | 0.5（规避）/ 3–5（Redis 方案） | 多副本下语音可用，或明确单副本部署 |

> **退出标准**：4 项 P0 关闭 + 门禁全绿 + 主库恢复演练通过 → **可上线内部 200 人自用**。

### 阶段 1：规模化准备（**6–8 周**）

P1-2 全局限流 → P1-3 定时工作流去内存态 → P1-8 压测覆盖 → P1-13 容量基线 → P1-1 CRDT 字符级协同 → P1-6 IM 搜索索引 → P1-9 钉钉组织架构接真 → P1-5 移动端基础（关键页面）

### 阶段 2：体验对标（**6–8 周**）

P1-11 字段类型 → P1-12 视图分组/日历翻月 → P1-10 集成市场 UI → P1-7 Slack/Mattermost 入站 → P2 各项（模板/反向链接/死代码清理/看板合并/分页虚拟滚动）

### 阶段 3：对外商业化（**8–12 周，进入前启动**）

🔒-1 容量与 SLA → 🔒-3 密钥托管 → 🔒-5 审计完整性 → 🔒-6 渗透测试 → 🔒-2 租户配额 → 🔒-4 合规 → 🔒-7 异地备份与 PITR → 🔒-8 隔离自动化审计 → 🔒-9 灰度与回滚

**总计**：内部上线 **2–3 周**；规模化就绪 **约 8–11 周**；对外商业化 **再 +8–12 周**。

---

## 九、附录：核查方法与命令

本报告结论由以下方式得出（可复现）：

**门禁实测**
```bash
cd backend-java && mvn -o test                    # 1210 / 0 / 0
cd frontend && npm run test:run                   # 267 passed (35 files)
cd frontend && npx tsc --noEmit                   # 0 errors
cd frontend && npx playwright test                # 64 passed
docker compose ps                                 # 6 容器全 healthy
curl -s localhost:8080/api/health                 # 200
```

**假接线判定手法**（关键）
对每个能力追溯「谁调用它」，而非确认文件存在。例：
```bash
grep -rn "batch-upsert" backend-java/src --include=*.java          # 0 命中 → 端点不存在
grep -rn "crdt.service.url\|CRDT_SERVICE_URL" --include=*.yml --include=*.yaml .   # 仅自身定义 → 无配置
grep -rn "MessageSearchService" backend-java/src --include=*.java   # 仅自身+注释 → 0 注入
grep -rn "workflowExpressionEvaluator" --include=*.java .           # 仅自身 → 0 引用
grep -n "auth-url" -B3 .../DingTalkController.java                  # @GetMapping vs 前端 POST
grep -n "_DB_PATHS\|pg_dump" backend-python/.../backup.py           # 仅 alerts.db → 主库缺失
```

**判定口径**：①存在文件 → ②已接线（调用链触发） → ③生产可用（无占位/可配置/有测试）。三者不等价，本项目多处「有代码但从未调用」。

---

## 十、给决策者的三句话

1. **能上，但别现在直接上**：门禁全绿、核心链路真接真，但 4 项 P0 里有 1 项是「业务主库无备份」、1 项是「钉钉登录主入口 405」——这两个问题在 200 人真实使用中必然被踩到。
2. **2–3 周即可达标**：4 项 P0 合计仅 **9–14 人日**，其中钉钉登录修复只需 **0.5 人日**（改一个 HTTP method）。
3. **差异化定位成立，别和单项标杆拼深度**：打「**自部署 + 一体化 + 钉钉/微信嵌入**」——这是 Notion/Airtable/Slack/Trello **做不到**（不可自部署）、NocoDB/NocoBase/RocketChat **做不全**（单一用途）的组合；单项深度（IM 生态、块编辑、字段类型）用迭代补齐。
