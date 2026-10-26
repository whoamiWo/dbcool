# 能力对标矩阵（逐域三层判定）

> 评估基线：`f752535`（v2.1.0，2026-10-26）
> 比对对象：NocoDB / NocoBase / Slack / RocketChat / Mattermost / Trello / Airtable / Notion
> 目标场景：面向中国企业（200–500 人），可嵌入钉钉/企微/飞书/微信，SaaS 化部署
> 判定口径：**① 存在文件 → ② 已接线（真实调用链触发）→ ③ 生产可用（无占位、配置可注入、有测试）**
> 复核说明：本矩阵对三份历史报告的结论做了实证复核，**推翻项已在表中显式标注**（见 §12）

---

## 一、总览矩阵

| # | 能力域 | 对标产品 | ①文件 | ②接线 | ③生产 | 综合 | 关键证据 |
|---|---|---|---|---|---|---|---|
| 1 | 数据模型 | Airtable / NocoDB | ✅ | ✅ | ✅ | **已达标** | `meta/CollectionService`、`FieldDef`、`FormulaEngine` |
| 2 | 表单设计器 | Airtable / NocoBase | ✅ | ✅ | ✅ | **已达标** | `FormDesigner`、`FormRuntime`、公开表单 `/forms/public/:token` |
| 3 | 视图引擎 | Trello / Airtable | ✅ | ✅ | 🟡 | **部分** | 表格/看板/日历/画廊/时间线均路由注册；**甘特图缺失** |
| 4 | 权限体系 | NocoBase | ✅ | ✅ | 🟡 | **部分** | 记录级/操作级/角色继承已实现；**字段级权限缺失** |
| 5 | 工作流 | NocoBase / Zapier | ✅ | ✅ | ✅ | **已达标** | 8 节点 + `NestedTriggerGuard` 死循环防护 |
| 6 | IM 协作 | Slack / RocketChat | ✅ | ✅ | 🟡 | **部分** | 频道/私信/表情/已读/在线/Huddle 齐全；**无 @全体、消息引用** |
| 7 | Wiki / 知识库 | Notion | ✅ | ✅ | ✅ | **已达标** | 版本快照/对比/反向链接/双向链接/PHASE130 锚文本 |
| 8 | 实时协同 CRDT | Notion | ✅ | 🟡 | 🟡 | **部分** | docker-compose 已配 `CRDT_SERVICE_URL`；**k8s 缺失**（见 §9） |
| 9 | 平台嵌入 | 钉钉/企微/飞书/微信 | ✅ | 🟡 | 🟡 | **部分** | 钉钉/企微/飞书/Slack/Mattermost/微信均有实现；**JS-SDK 签名缺失** |
| 10 | 外部数据源 | NocoDB / Superbase | ❌ | ❌ | ❌ | **缺失** | 仅有 `ExternalMessageLog`（集成日志），**非外部数据源** |
| 11 | AI 智能体 | Notion AI / Copilot | ✅ | 🟡 | 🟡 | **部分** | LLM 调用/RAG/对话历史已接；**无 PromptTemplate、模型硬编码** |
| 12 | 多租户 / 计费 | SaaS 对标 | ✅ | 🟡 | ❌ | **部分** | Schema 级隔离 + 配额已实现；**无计费/套餐/支付/发票** |

**综合判定**：核心业务闭环（数据/表单/视图/权限/工作流/IM/Wiki）**成熟可用**；缺口集中在
① 外部数据源对接 ② 计费商业化 ③ 微信生态嵌入 ④ 可视化建模（BI/甘特）。

---

## 二、数据模型（对标 Airtable / NocoDB）

| 能力 | 状态 | 证据 |
|---|---|---|
| Collection 增删改 + Schema 导入 | ✅ | `meta/CollectionService.java`、`JsonSchemaImporter` |
| 字段类型（13 种） | ✅ | `FieldDef.isValidType`：text/number/date/datetime/boolean/email/url/phone/currency/percent/belongsTo/hasMany/formula/rollup/lookup/attachment |
| 公式引擎 | ✅ | `meta/formula/FormulaEngine.java` + `FormulaFunctions.java`（真计算，非占位） |
| 汇总 / 查找 | ✅ | `meta/rollup/RollupEngine.java` |
| 关联展开 | ✅ | `RelationResolver.expandRelations` |
| 筛选 / 排序 / 分组 | ✅ | `CollectionController#listRecords` `parseFilters`，op 白名单 + 字段名正则防注入 |
| CSV 导入导出 | ✅ | `GET /export`、`POST /import`（逐行失败收集） |
| Excel/PDF 导出 | ✅ | `GET /export-file?format=xlsx\|pdf`（PHASE10，Python 渲染） |
| 批量 API | 🟡 | 后端 `batch-insert/update/delete` 已实现；**前端 0 调用** |

**差距**

| 缺口 | 对标 | 说明 |
|---|---|---|
| 字段类型偏少 | Airtable | 缺 `richText`、`rating`、`duration`、`user/collaborator`、`button`、`createdTime` |
| 公式函数库 | Airtable | 约 30 个 vs Airtable 80+，缺 `ARRAY_JOIN`/`UNIQUE`/`FILTER` 数组函数 |
| 批量编辑（前端） | Airtable | 后端端点齐备，前端无入口 → **高性价比补齐项** |
| 冻结列 / 条件格式 | Airtable | `TableView` 缺 `frozen` 配置与单元格样式规则 |

---

## 三、表单设计器（对标 Airtable / NocoBase）

| 能力 | 状态 | 证据 |
|---|---|---|
| 拖拽布局 | ✅ | `FormDesigner.tsx`（`layout_json`） |
| 字段属性面板 | ✅ | 标签/占位符/默认值/帮助文本 |
| 显隐规则 | ✅ | `FieldRulesEditor.tsx` + `FormRuntime.tsx` visibility 规则 |
| 校验（必填/长度/范围/正则） | ✅ | `FieldRulesEditor.tsx` |
| 表单运行时 | ✅ | `FormRuntime.tsx`，支持工作流触发 + 提交后跳转 |
| 公开表单（免登录） | ✅ | `/forms/public/:token`（PHASE10），`FormPublicFill.tsx` |
| 表单分享 | ✅ | `FormShareDialog.tsx`（PHASE128），生成/复制/撤销 token |

**差距**

| 缺口 | 对标 | 说明 |
|---|---|---|
| 跨字段校验 | Airtable | 仅支持单字段校验 |
| 异步远程校验 | Airtable | 无 API 远程校验 |
| 表单版本历史 | Notion | 布局无版本管理 |

---

## 四、视图引擎（对标 Trello / Airtable）

**已注册路由**（`frontend/src/router.tsx:216-221` 实证）：

| 视图 | 路由 | 文件 |
|---|---|---|
| 表格 | `/views/:id/run` | `pages/TableView.tsx` |
| 看板 | `/views/:id/kanban` | `pages/KanbanView.tsx` |
| 画廊 | `/views/:id/gallery` | `pages/GalleryView.tsx` |
| 日历 | `/views/:id/calendar` | `pages/CalendarView.tsx` |
| 详情 | `/views/:id/detail/:recordId` | `pages/DetailView.tsx` |
| 时间线 | `/views/:id/timeline` | `features/collection/TimelineView` |

> ⚠️ **推翻历史结论**：旧报告称"Gallery 待验证/未接线"，实证 `GalleryView.tsx` 存在且已注册路由；
> 且存在 `TimelineView`（`features/collection/`），历史矩阵未计入。

**已实现能力**：虚拟滚动、分组聚合（sum/avg/min/max/count）、排序、筛选、列显隐、列宽调整、后端分页。

**差距**

| 缺口 | 对标 | 优先级 | 说明 |
|---|---|---|---|
| **甘特图** | Trello/Airtable | 🟡 中 | **实证缺失**（`ls frontend/src/pages \| grep -i gantt` → 0）。后端 `ProjectController` 有 `/{projectId}/gantt`，前端无组件 |
| 看板拖拽 | Trello | 🔴 高 | `KanbanView` 只读展示，**无拖拽移动** |
| 日历周/年视图 | Airtable | 🟡 中 | 仅月视图，无事件拖拽改期 |
| 视图级 groupBy | Airtable | 🟡 中 | 后端 `aggregate` 仅被 BI 调用；看板分组为前端内存分组 |
| `limit=500` 上限 | Airtable | 🟡 中 | Gallery/Calendar/Kanban 无分页、无虚拟滚动 |

---

## 五、权限体系（对标 NocoBase）

| 能力 | 状态 | 证据 |
|---|---|---|
| 角色 CRUD + 树形 | ✅ | `auth/RoleAclController.java`，`GET /admin/roles/tree` |
| 角色继承 + 循环检测 | ✅ | `parent_role_id`、`assertNoCycle`、`findSelfAndAncestors` |
| 策略 CRUD（record/operation） | ✅ | `RoleAclController.java:151-212` |
| 行级 ACL（记录级） | ✅ | `acl/RowAclController.java` + `RowAclService` |
| 操作级权限 | ✅ | ACL 策略 `action` 字段 |
| **字段级权限** | ❌ | **实证缺失**：`grep -rn "fieldLevel\|FieldPermission"` → 0 命中 |

**差距**

| 缺口 | 对标 | 优先级 | 说明 |
|---|---|---|---|
| **字段级权限** | NocoBase | 🔴 高 | 企业敏感数据（薪资/身份证）刚需，需 `FieldPolicy` 细化到字段读写可见 |
| `PermissionEvaluator` 抽象 | NocoBase | 🟡 中 | 无统一评估器接口，权限判定散落各处 |
| 字段级权限测试 | — | 🟡 中 | `RowAclControllerTest` 存在，但缺字段级与继承边界用例 |

---

## 六、工作流（对标 NocoBase / Zapier）

| 能力 | 状态 | 证据 |
|---|---|---|
| 引擎执行 + 节点遍历 | ✅ | `workflow/WorkflowEngine.java` |
| 节点（审批/通知/条件/HTTP/数据更新/钉钉审批） | ✅ | `workflow/handler/` 目录实证 6 个 Handler |
| 触发器（数据创建/更新/手动） | ✅ | `WorkflowTriggerListener.java`、`POST /workflows/{id}/trigger` |
| 死循环防护 | ✅ | `NestedTriggerGuard.java` |
| 工作流版本 / 模板 | ✅ | `WorkflowVersionService`、`WorkflowTemplateController` |

**差距**

| 缺口 | 对标 | 优先级 |
|---|---|---|
| 延时/等待节点 | NocoBase | 🟡 中 |
| 子工作流（嵌套调用） | NocoBase | 🟡 中 |
| 定时/Cron 触发完整性 | Zapier | 🟡 中（待确认） |

> 注：`WorkflowEngine.createApprovalTask` 与 `ApprovalNodeHandler` 为**两套并行实现**（legacy vs handler），
> 均有调用（非坏死代码），但存在冗余，建议后续收敛。

---

## 七、IM 协作（对标 Slack / RocketChat / Mattermost）

| 能力 | 状态 | 证据 |
|---|---|---|
| 频道 / 私信 / DIRECT | ✅ | `im/ChannelService.java:48-106` |
| 消息发送/编辑/删除 | ✅ | `im/MessageService.java:68-161` |
| 线程回复 | ✅ | `im/MessageService.java:64-66`（PHASE10-H3 `ThreadPanel`） |
| 表情回应 | ✅ | `im/ReactionService.java` |
| 已读回执 | ✅ | `im/MessageService.java:216-229` |
| 在线状态（Redis） | ✅ | `im/PresenceService.java:28-34` |
| 高级搜索 / @提及 | ✅ | **已修复**：`MessageSearchService` 经 `ImMessageController:244,280` 调用 |
| Huddle 音视频 | ✅ | `im/HuddleService.java` + `HuddleSignalingController` |

> ⚠️ **推翻历史结论**：旧报告称"高级搜索是死代码"，实证 `MessageSearchService.java:26-28` 注释说明已修复并接入控制器。

**差距**

| 缺口 | 对标 | 优先级 |
|---|---|---|
| @全体 / @频道 提及 | Slack | 🟡 中 |
| 消息引用 / 转发 | Mattermost | 🟡 中 |
| 消息撤回（含撤回窗口） | Slack | 🟢 低 |

---

## 八、Wiki / 知识库（对标 Notion）

| 能力 | 状态 | 证据 |
|---|---|---|
| 块编辑器 + CRDT 协同 | ✅ | `NotionStyleEditor.tsx`、`CollabEditor.tsx` |
| 版本快照 / 版本对比 | ✅ | `WikiPageService:245-256`、`VersionDiff.tsx` |
| 页面嵌套 | ✅ | `parent_page_id` 层级 |
| 反向链接端点 | ✅ | `WikiController:740-777` → `WikiPageService.syncBacklinks` |
| **双向链接 + 锚文本 + 上下文** | ✅ | **PHASE130 新增**：`[[slug\|anchor]]` 解析、`anchorText`/`context` 落库（V67 迁移） |
| 混合检索（FTS + pgvector） | ✅ | `WikiEmbeddingService:125-168` |
| 权限 | ✅ | `WikiPermissionService`（被 5 处真实调用） |
| 块编辑保存端点 | ✅ | **已修复**：`WikiController:478-500` `/api/wiki/blocks/batch-upsert` |

> ⚠️ **推翻历史结论（三处）**：
> 1. 旧报告"块编辑自动保存打到不存在端点（404）" → **已修复**（端点存在）
> 2. 旧报告"模板列表端点不匹配（404）" → **已修复**（`WikiController:660-694`）
> 3. 旧报告"反向链接渲染 `is not a function`" → **已修复**（正确解析 + unwrap）

**差距**

| 缺口 | 对标 | 优先级 |
|---|---|---|
| 块级权限控制 | Notion | 🟡 中 |
| 页面分享访问统计 | Notion | 🟢 低 |
| Wiki 模板库（弱） | Notion | 🟢 低 |

---

## 九、实时协同 CRDT（对标 Notion）

| 核查项 | 状态 | 证据 |
|---|---|---|
| `crdt.service.url` **docker-compose** | ✅ 已配置 | `docker-compose.yml:182` `CRDT_SERVICE_URL: http://crdt-service:3100` |
| `crdt.service.url` **k8s** | ❌ **缺失** | `grep -rn "CRDT_SERVICE_URL\|crdt.service.url" k8s/*.yaml` → **0 命中** |
| 字符级 diff | ✅ 已修复 | `CollabEditor.tsx:47-89` LCS 字符级 `applyDiffToText`（旧"整篇替换"已改） |
| 广播 payload 含 `update` | ✅ 已修复 | `RealtimeService.java:124-132` |
| CRDT 服务部署 | ✅ | `k8s/11-crdt-service.yaml` 已部署（port 3100） |

> 🔴 **关键 P0（实证）**：`k8s/11-crdt-service.yaml` 部署了 CRDT 服务，
> 但 `k8s/02-backend-java.yaml` **无任何 env 指向它** → **K8s 环境下 CRDT 服务部署了没人调**，
> 降级为增量透传（非字符级合并）。Docker Compose 环境正常，K8s 环境静默降级。

---

## 十、平台嵌入（对标钉钉/企微/飞书/微信官方应用）

> ⚠️ **推翻历史结论**：旧报告称"微信完全未实现 / Slack-RocketChat 缺"，实证三者**均有实现**。

**集成包实际清单**（`backend-java/src/main/java/com/nocobase/integration/` 实证）：
`dingtalk/` `wecom/` `feishu/` `slack/` `mattermost/` `market/` `common/` `credential/`

| 平台 | OAuth 免登 | 组织同步 | 机器人 webhook | 卡片消息 | JS-SDK 签名 | 订阅消息 |
|---|---|---|---|---|---|---|
| **钉钉** | ✅ `DingTalkAppService:111-121` | ✅ `DingTalkOrgSyncService:70-137` | ✅ `DingTalkDispatcher:20-101` | 🟡 ActionCard/Markdown | ❌ | ❌ |
| **企微** | ✅ `WeComController:54-74` | ✅ `WeComAppService:229-297` | ✅ `WeComDispatcher:23-85` | ❌ | ❌ | ❌ |
| **飞书** | ✅ `FeishuAppService:87-97` | ✅ | ✅ `FeishuPersonalDispatcher:30-94` | ✅ interactive | ❌ | ❌ |
| **Slack** | ✅ `SlackAppService`/`SlackController` | — | ✅ | — | — | — |
| **Mattermost** | ✅ `MattermostAppService`/`Controller` | — | ✅ | — | — | — |
| **微信** | 🟡 `WeChatPersonalDispatcher`/`WeChatWorkDispatcher`（在 `notification/`） | — | ✅ | ❌ | ❌ | ❌ |

另：集成市场 `market/IntegrationMarketService` + `TenantIntegrationInstallEntity` **已实现**（支持租户安装集成）。

**差距**

| 缺口 | 对标 | 优先级 | 说明 |
|---|---|---|---|
| **JS-SDK 签名服务** | 钉钉/企微官方 | 🔴 高 | 前端嵌入必需（timestamp/nonce/corpId 签名），当前**全平台缺失** |
| **微信 JS-SDK 嵌入** | 企业微信 | 🔴 高 | 国内刚需；现有仅为 `notification/` 下的消息推送，**非前端 JS-SDK** |
| 订阅消息 / 模板消息 | 微信/钉钉 | 🟡 中 | 无统一 Dispatcher |
| 飞书卡片消息 Dispatcher | 飞书官方 | 🟡 中 | 缺独立 `FeishuCardMessageDispatcher` |
| 钉钉 `app.message.send` | 钉钉官方 | 🟡 中 | 仅 webhook，未接官方消息发送 API |

---

## 十一、外部数据源 / AI / 多租户 / 计费

| 域 | 状态 | 实证 |
|---|---|---|
| **外部数据源** | ❌ **缺失** | `find ... -ipath "*external*"` 仅命中 `ExternalMessageLog*`（集成日志），**非数据源**。无 MySQL/PG 连接池、无元数据同步 |
| **AI 智能体** | 🟡 部分 | `ai/AgentService`、`AiAssistantService`（调 Python `/api/ai/chat`）、RAG/pgvector 已接；**无 `PromptTemplateRepository`、模型硬编码 `gpt-4o`** |
| **多租户** | 🟡 部分 | `tenant/` 8 个类：`TenantContext`（ThreadLocal）、`SchemaTenantConnectionProvider`（Schema 级隔离）、`TenantQuotaService`（API/存储/席位配额）；**缺 `TenantMigrationService`、租户切换前端** |
| **计费** | ❌ **缺失** | `find ... -ipath "*billing*\|*payment*\|*invoice*"` → **0 命中**。无套餐/周期/支付网关/发票 |
| **移动端** | ❌ 缺失 | 无 React Native/Flutter；仅响应式 Web + PWA |

---

## 十二、与历史报告的结论冲突（推翻项汇总）

| 旧结论（出处） | 实证现状 | 判定 |
|---|---|---|
| "Gallery 待验证/未接线"（探索子代理） | `GalleryView.tsx` 存在且 `router.tsx:218` 已注册 | ❌ 旧结论错 |
| "微信完全未实现"（旧对标文档） | `notification/WeChatWorkDispatcher`、`WeChatPersonalDispatcher` 存在（推送层）；JS-SDK 确缺失 | 🟡 部分推翻 |
| "Slack/RocketChat 缺"（旧对标文档） | `integration/slack/`、`integration/mattermost/` 均实现 | ❌ 旧结论错 |
| "高级搜索是死代码"（综合评估） | `MessageSearchService` 已接入 `ImMessageController:244,280` | ✅ 已修复 |
| "块编辑保存 404"（综合评估） | `WikiController:478-500` 端点存在 | ✅ 已修复 |
| "模板列表 404"（综合评估） | `WikiController:660-694` 端点存在 | ✅ 已修复 |
| "反向链接 is not a function"（综合评估） | `WikiPageService.syncBacklinks` 正确落库 | ✅ 已修复 |
| "CRDT 整篇替换"（综合评估） | `CollabEditor.tsx:47-89` 已改为 LCS 字符级 | ✅ 已修复 |
| "CRDT 服务部署了没人调"（综合评估） | **docker-compose 已配**；**k8s 仍未配** → 仅 K8s 环境成立 | 🔴 仍成立（限 K8s） |

---

## 十三、优先级排序（差距项）

### 🔴 P0 — 阻塞企业级上线
| # | 缺口 | 域 | 说明 |
|---|---|---|---|
| 1 | **k8s CRDT_SERVICE_URL 缺失** | 协同 | K8s 部署下协同静默降级为整篇替换，后写覆盖先写 |
| 2 | **字段级权限缺失** | 权限 | 敏感数据（薪资/证件）无法按字段授权 |
| 3 | **看板无拖拽** | 视图 | Trello 核心交互缺失 |
| 4 | **JS-SDK 签名全平台缺失** | 嵌入 | 前端嵌入钉钉/企微的必备能力 |

### 🟡 P1 — 国内企业刚需 / 规模化前必补
| # | 缺口 | 域 |
|---|---|---|
| 5 | 微信 JS-SDK 嵌入（含免登/分享/拍照） | 嵌入 |
| 6 | 外部数据源集成（MySQL/PG 等） | 集成 |
| 7 | 计费与套餐（套餐/周期/支付/发票） | 商业化 |
| 8 | 甘特图视图 | 视图 |
| 9 | 批量编辑（前端入口） | 数据 |
| 10 | 订阅消息 / 卡片消息 Dispatcher | 嵌入 |

### 🟢 P2 — 体验与对标差距
| # | 缺口 | 域 |
|---|---|---|
| 11 | 公式函数库扩充（数组函数） | 数据 |
| 12 | 冻结列 / 条件格式 | 视图 |
| 13 | 日历周/年视图 + 事件拖拽 | 视图 |
| 14 | AI PromptTemplate + 多模型 | AI |
| 15 | 字段类型扩充（rating/duration/user/button） | 数据 |
| 16 | @全体提及 / 消息引用 | IM |

### ⚪ P3 — 长期愿景
| # | 缺口 |
|---|---|
| 17 | 移动端原生 App |
| 18 | 块级权限控制 |
| 19 | 子工作流 / 延时节点 |
| 20 | Wiki 分享访问统计 |

---

## 十四、工作量估算

| 优先级 | 项数 | 估算 |
|---|---|---|
| P0 | 4 | 15–22 人日 |
| P1 | 6 | 40–60 人日 |
| P2 | 6 | 30–45 人日 |
| P3 | 4 | 25–40 人日 |
| **合计** | **20** | **110–167 人日** |

> 说明：P0 修完即达企业级内部上线标准；P0+P1 完成可对标主流产品核心体验。
