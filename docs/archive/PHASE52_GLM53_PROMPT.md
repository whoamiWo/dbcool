# Phase 52 企业综合平台整合收口 — GLM-5.3 执行提示词

> 生成日期: 2026-09-21
> 编排者: CodeBuddy (HY4) · 执行者: GLM-5.3
> 依据: 2026-09-21 全栈只读审计（项目已具备 8 个产品内核 80%，但存在"集成断层"）
> 关联: PHASE50_GLM53_PROMPT.md, PHASE51_GLM53_PROMPT.md, ARCHITECTURE.md, MEMORY.md
> 门禁基线: 后端 `mvn test` 1057 PASS · 前端 `vitest` 233 PASS + `tsc --noEmit` 0 errors + `vite build` · Python `pytest`
> 本轮重心: **整合收口优先**（把 8 个零件装成 1 台机器），嵌入层全平台（企微/微信/Slack/飞书/Mattermost），AI 暂不引 pgvector（保持 FTS）

---

## ⚠️ 首要认知：不是从零开发，是"收口"

项目**已具备八大产品内核的 80%**（Phase49/50/51 已交付并可编译启动，后端 1057 PASS）：

| 对标产品 | 现有内核 | 状态 |
|---|---|---|
| NocoDB / NocoBase | `meta` 动态建模引擎（Collection/DDL/关系/ER图） | ✅ 成熟 |
| Airtable | 公式引擎(Aviator) + Rollup + Lookup + 5 视图 | ⚠️ 引擎有，接线窄 |
| Notion | `wiki` 30 端点（Block/反向链接/模板/版本/FTS） | ✅ 成熟 |
| Slack | `im` 26 端点（频道/线程/pin/reaction/阅后即焚/Slash） | ✅ 成熟 |
| Trello | `project` 看板（列/卡片/清单/标签/dnd-kit 拖拽） | ✅ 成熟 |
| Mattermost | `workflow` 8 节点 + 审批 + `playbook` 剧本 | ✅ 成熟 |
| Rocket.Chat | Huddle 信令 + Livechat（**占位**） | ⚠️ 骨架 |
| 钉钉嵌入 | SSO/通讯录/OA审批/消息 **全真实** | ✅ 成熟范式 |

**本轮任务是把分散在 30 个包里的能力"装成一个平台"**，不是重复造轮子。优先打通断层，其次补嵌入层。

---

## 零、全局约定（每个任务都必须遵守）

### 0.1 项目结构
```
/home/who/multistack-project/
├── backend-java/          Spring Boot 3.3.5 / JDK 21 / Maven（294 主文件，30 包，51 表，迁移 V1–V34）
├── backend-python/        FastAPI 0.115 / Py3.12
└── frontend/              React 18 + TS + Vite 5 + MUI v9 + @emotion
```

### 0.2 质量门禁（每任务完成后必跑，缺一不可）
```bash
# 后端（基线 1057，只允许增加）
cd backend-java && mvn test
# 前端（基线 233 PASS）
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
# Python
cd backend-python && pytest
```

### 0.3 提交规范（分栈提交，每任务一次）
`[java]` / `[python]` / `[js]` / `[docs]`

### 0.4 红线（违反即返工）
1. **禁止 `log.info` + `// TODO` 冒充接真**——本轮大量修复正是要消除这类占位。
2. **禁止臆造 API**——所有调用的符号必须先用 `[subagent:code-explorer]` 或 `[skill:lsp-code-analysis]` 在代码中确认真实存在与签名（见 §附录真实契约）。
3. **禁止硬编码亮色**——前端一律用 `theme/tokens.ts` CSS 变量 + MUI `sx` + `.glass-*` 工具类。
4. **迁移版本号从 V35 起**（V34 已被看板 4 表占用）。本轮**收口优先，尽量不改 schema**——能用现有表承载就不要新建迁移。
5. **不擅自引重型依赖**——pgvector 已决策**不引入**（本轮保持 FTS）。

### 0.5 完成后更新记忆
追加到 `/home/who/multistack-project/.codebuddy/memory/2026-09-21.md`（用 `replace_in_file` 追加，勿覆盖）。

### 0.6 执行顺序（严格按依赖）
```
【P0 收口 — 本轮重心】
T1 统一工作台与导航
  ↓
T2 全局搜索接真（最大断层：索引表是空的）
  ↓
T3 AI Copilot 统一入口
  ↓
T4 暗色主题全覆盖  ┐
T5 Huddle 真实音视频 ┴ 可并行
  ↓
【P1 嵌入层】
T6 企业微信 → T7 微信客服/Livechat → T8 Slack 双向 → T9 飞书+Mattermost
  ↓
【P2 门禁】
T10 全栈门禁 + 分栈提交 + 记忆更新
```

---

## T1 · 统一工作台与六大能力域导航

**目标**：把 `WorkbenchPage` 从"6 模块卡片网格"升级为**聚合视图**，导航按六大能力域重组，让用户在首页一眼看到"待办/未读/最近文档/我的任务"。

**背景现状（真实代码事实）**
- `frontend/src/pages/WorkbenchPage.tsx` — 现有 6 模块卡片网格 + 搜索 + 快速入口（**纯静态卡片，无真实聚合数据**）
- `frontend/src/components/AppLayout.tsx` — 导航；`AppLayout.tsx:279` 引用了 `--color-primary-300`，但 `styles.css` **只定义了 400/500/600** → 该变量失效（需一并修复）
- `frontend/src/router.tsx` — 路由注册（`/workbench` 已是首页）
- 后端**无**工作台聚合端点——需新建
- 可复用的真实后端能力：
  - 待办：`workflow` 的 `WorkflowTaskEntity`（`ApprovalNodeHandler` 生成）
  - 未读：`im` 未读数（`ImMessageController` 已有未读数端点；前端 `features/im/api.ts` 有 `getUnreadCount`）
  - 最近文档：`wiki` `WikiController` 页面列表
  - 我的任务：`ProjectController` `/api/projects/tasks/mine`

**涉及文件**
- `frontend/src/pages/WorkbenchPage.tsx` [修改] — 聚合视图
- `frontend/src/components/AppLayout.tsx` [修改] — 六大能力域导航 + 修 `--color-primary-300`
- `backend-java/src/main/java/com/nocobase/api/WorkbenchController.java` [新建] — `GET /api/workbench/summary`
- `frontend/src/styles.css` [修改] — 补 `--color-primary-300` 定义（或改引用为已定义的 400）

**实现要点**
1. 新建 `WorkbenchController`：`GET /api/workbench/summary` 返回 `{todos, unread, recentDocs, myTasks}`。
   - **先用 code-explorer 确认** `WorkflowTaskRepository` / `ImMessageController` 未读数 / `WikiPageService` / `ProjectService.listMine` 的真实方法签名，再调用，**不得臆造**。
2. 前端 `WorkbenchPage` 用 `useQuery(['workbench','summary'])` 拉取，四个区块用 `.glass-card` 毛玻璃卡片，hover 微动效（`translateY(-2px)` + 阴影加深）。
3. `AppLayout` 导航重组为六大能力域：**Tables / Docs / Chat / Projects / Automations / AI**（沿用现有 `useMediaQuery(768px)` 响应式 + 固定底部导航）。
4. 修 `--color-primary-300`：在 `styles.css` 补定义（值取 `#a5b4fc`，与 `tokens.ts` primary.300 一致），或将引用改为 `--color-primary-400`。

**验收**
```bash
cd backend-java && mvn test          # ≥1057
cd frontend && npx tsc --noEmit && npx vite build
# 手动：/workbench 首页显示真实待办数/未读数/最近文档/我的任务
```

---

## T2 · 全局搜索接真（**最大断层，优先级最高**）

**目标**：让 `unified_search_index` 表真正被写入并支持四类（wiki/record/im/project）真实搜索 + 权限过滤 + 高亮。

**背景现状（真实代码事实）**
- `backend-java/.../search/UnifiedSearchService.java` 已有 `indexEntity(...)` / `removeEntity(...)` 方法签名，但**全仓库无任何调用方** → 索引表是空的
- `backend-java/.../search/UnifiedSearchController.java` — **仅 1 个端点** `GET /api/search?keyword=&types=&limit=`
- `backend-java/.../search/UnifiedSearchIndexEntity.java` — `content_tsv` 由**触发器维护**（`insertable=false/updatable=false`，V28 已建 `trg_update_search_vector`）
- 表结构（V28）：`unified_search_index` 含 `entity_type/entity_id/tenant_id/title/content/content_tsv/metadata`
- 各模块已有独立搜索：`WikiSearchService`（PG FTS `plainto_tsquery` + `ts_headline` + `ts_rank_cd`）、`ImMessageController` 消息搜索

**涉及文件**
- `backend-java/.../search/UnifiedSearchService.java` [修改] — 接真：四类搜索 + 权限过滤 + `ts_headline` 高亮
- `backend-java/.../search/UnifiedSearchIndexListener.java` [新建] — 事件驱动索引写入器（监听 `RecordChangeEvent` + wiki/im/project 变更）
- `backend-java/.../search/UnifiedSearchController.java` [修改] — 支持 `types=wiki,record,im,project`
- 各模块 Service [修改] — 在 wiki 页面保存 / im 消息发送 / project 任务变更时调用 `indexEntity`；删除时调用 `removeEntity`
- 前端全局搜索面板 [新建/修改] — 调用 `/api/search`，展示四类结果并高亮

**实现要点**
1. **索引写入器**（核心）：新建 `UnifiedSearchIndexListener`，用 `@TransactionalEventListener(phase = AFTER_COMMIT)` 订阅 `RecordChangeEvent`（已有），并分别为 wiki/im/project 提供写入入口。确保：
   - `content_tsv` **不在应用层写**（由触发器维护），只写 `title/content/metadata`
   - 写入前 `removeEntity` 再 `indexEntity`（upsert 语义）
2. **四类搜索**：
   - `wiki` → 走 `WikiSearchService` FTS（已有，直接复用）
   - `record` → 按 `collectionName` 搜索 Collection 记录（**先确认 `CollectionService.listRecords` 真实签名**，审计提示其为 `listRecords(collectionName, tenantId, limit)`）
   - `im` → 消息搜索（复用 `ImMessageController` 的搜索 Service）
   - `project` → 任务搜索
3. **权限过滤**：搜索结果必须按当前用户 ACL 过滤（`AclEnforcer`），**不得**返回越权数据。
4. **高亮**：`ts_headline` 返回摘要。
5. **前端**：搜索面板用毛玻璃，结果按类型分组，关键词高亮。

**验收**
```bash
cd backend-java && mvn test
# 手动：创建一条 wiki 页面 → 调用 GET /api/search?keyword=xxx&types=wiki → 能搜到且高亮
#      发一条 im 消息 → types=im → 能搜到
```

---

## T3 · AI Copilot 统一入口

**目标**：统一 AI 入口，打通 前端 ↔ Java Agent ↔ Python LLM 网关，消除"Python `/api/ai/chat` 无调用方"的错位。

**背景现状（真实代码事实）**
- `frontend/src/pages/agent/AgentChatPage.tsx` — 调 **Java** `/ai/agent/execute`（`apiClient.post('/ai/agent/execute', {prompt, channelId})`）
- Python `routers/ai.py` 的 `POST /api/ai/chat` —— **前端无任何调用方**（含限流 30次/60s + LRU缓存 256条/300s + 日100次/月2000次配额三层防护，全部闲置）
- `vite.config.ts:19-39` — proxy 已配 `/api/ai` → Python:8000
- Java `ai/AiAssistantService.java` — 转发 Python LLM 网关（**需确认现状**）；`ai/AgentService.java` — ReAct 循环 + 6 工具（CreatePage/CreateTask/QueryDatabase/SearchWiki/SendDing/SummarizeChannel）
- Python 未配 `llm_api_key`/`llm_base_url` 时回退 `simulated` 响应

**涉及文件**
- `frontend/src/pages/agent/AgentChatPage.tsx` [修改] — 重构为 **AI Copilot 统一面板**
- `backend-java/.../ai/AiAssistantService.java` [修改] — 统一编排（对话走 Python 网关，工具走 Java Agent）
- `backend-python/.../routers/ai.py` [确认/微调] — `/api/ai/chat` 保持
- `frontend/src/features/ai/` [新建，可选] — Copilot 组件

**实现要点**
1. **Copilot 面板**：一个统一对话入口，支持：
   - 普通对话 → 走 Python `/api/ai/chat`（经 vite proxy `/api/ai`）
   - 任务型指令（建表/建任务/搜知识库/发钉钉）→ 走 Java `/ai/agent/execute`（Agent 6 工具）
   - 公式生成辅助（复用 `meta/formula/FormulaEngine`）
2. 前端按指令意图分流（`/` 前缀或意图识别），**不要**让两个入口并存造成困惑。
3. 会话历史：当前 `AgentChatPage` 是单轮无历史（`messages` 仅 `useState`），本轮至少保留前端会话列表；**不强行改 schema**。
4. UI：毛玻璃对话气泡（沿用 `theme/tokens`），**去掉** `AgentChatPage.tsx:53` 的硬编码亮色 `'#e3f2fd'`/`'#f5f5f5'`。

**验收**
```bash
cd frontend && npx tsc --noEmit && npx vite build
# 手动：Copilot 输入普通问题 → 走 Python 网关返回；输入 /create_task → 走 Java Agent 执行
```

---

## T4 · 暗色主题全覆盖（29 个文件）+ token 单一来源

**目标**：消除硬编码亮色，让 Glassmorphism 暗色主题覆盖 100% 页面；合并三份冲突 token 为单一来源。

**背景现状（真实代码事实）**
- **29 个文件硬编码亮色**（审计确认，含行号）：
  - `src/pages/WorkflowInstances.tsx` — 7 处：`background:'white'`(97,182,201,264)、`'#f1f5f9'`(99)、`'#f8fafc'`(205,242)
  - `src/pages/AlertCenter.tsx` — 3 处 `background:'#fff'`(510,608,668)；另 `'#374151'`/`'#6b7280'`/`'#9ca3af'`（Tailwind 灰阶，与主题不符）
  - `src/pages/WorkflowDesigner.tsx`(5)、`CollectionDetail.tsx`(5)、`FormDesigner.tsx`(4)、`MyTasks.tsx`(4)
  - `src/pages/SchemaEditor.tsx`(3)、`AclEditor.tsx`(3)、`NotificationChannels.tsx`(3)、`CollectionsList.tsx`(3)、`FormsList.tsx`(3)、`UsersList.tsx`(3)、`ViewsList.tsx`(3)
  - `src/pages/FormRuntime.tsx`(1)、`Login.tsx`(2)、`Profile.tsx`(2)
  - `src/features/project/GanttView.tsx` — `bgcolor:'#fafafa'`(107)、`'#1976D2'`(116)、`'#4CAF50'`(126)、`borderBottom:'1px solid #f0f0f0'`(91)；`GanttLegend`(194-201) Chip 用 `color:'#fff'`
  - `src/pages/agent/AgentChatPage.tsx:53` — `'#e3f2fd'`/`'#f5f5f5'`
  - `src/components/wiki/NotionStyleEditor.tsx:586,608` — `'#f5f5f5'`
  - `src/pages/wiki/WikiVersionHistory.tsx:194` — `'#f5f5f5'`
- **三份 token 来源冲突**：`theme/tokens.ts` + `styles.css` + `theme/glass.css`（`glass.css` 仅 1 处引用且类名与 `styles.css` 冲突）
- `--color-primary-300` 未定义却被 `AppLayout.tsx:279` 和 `styles.css:373` 引用 → 失效（T1 已要求修，本任务兜底）
- `frontend/src/api/client.ts` — 并发 401 时 `isRefreshing` 分支直接 `Promise.reject('Refresh in progress')`（**并发请求会失败而非排队等待**）；`subscribeTokenRefresh` 被注释掉(TODO)

**涉及文件**
- 上述 29 个 [修改]
- `frontend/src/theme/tokens.ts` [修改] — 定为**唯一 token 来源**
- `frontend/src/styles.css` [修改] — 移除重复定义，改为引用 tokens；补 `--color-primary-300`
- `frontend/src/theme/glass.css` [合并/删除] — 消除与 `styles.css` 类名冲突
- `frontend/src/api/client.ts` [修改] — 并发 401 改为**排队等待**（取消注释 `subscribeTokenRefresh`，`refreshRequest` 复用）

**实现要点**
1. 逐文件替换硬编码色值为 CSS 变量（`var(--color-bg-primary)` / `var(--color-text-primary)` 等）或 MUI `sx` 主题值（`theme.palette.background.paper`）。
2. token 单一来源：`tokens.ts` 为唯一定义，`styles.css` 只做变量声明与工具类，删除 `glass.css` 或合并进 `styles.css`（**二选一，避免类名冲突**）。
3. `client.ts` 并发 401：恢复 `refreshSubscribers` 机制，让并发请求等待同一个 `refreshRequest` 完成后重试，而非 reject。
4. 保持暗色主题视觉：毛玻璃 + 微动效，符合设计令牌（primary `#6366F1`/`#4F46E5`/`#818CF8`，bg `#0F172A`/`#1E293B`/`#334155`，text `#F8FAFC`/`#E2E8F0`/`#94A3B8`）。

**验收**
```bash
cd frontend && npx tsc --noEmit && npx vite build
cd frontend && npx vitest run     # 233 PASS
# 全站 grep 确认无残留硬编码亮色：
grep -rn "background: *['\"]#\(fff\|f8fafc\|f1f5f9\|fafafa\)" src/ || echo "clean"
```

---

## T5 · Huddle 真实音视频

**目标**：把 Huddle 从"占位文字"变成真实 WebRTC 音视频面板（本地/远端视频、静音/挂断），并修 `/ws/huddle` 代理缺失。

**背景现状（真实代码事实）**
- `frontend/src/features/im/ImLayout.tsx:29-56` — `HuddlePanel` 是**占位 UI**，只渲染一行"音视频流由 SFU 处理，信令已连接"，**无音视频渲染**
- `frontend/src/features/im/useHuddle.ts` — 裸 WebSocket `/ws/huddle?token=`；`sendSignal` / `peers` **从未被消费**
- `frontend/vite.config.ts` — proxy 只配了 `/ws` → 8080，**`/ws/huddle` 未配** → 开发环境必然连不上
- 后端：`im/HuddleSignalingHandler.java`（信令中继，已有）、`im/HuddleService.java`（房间元数据）、`config/HuddleWebSocketConfig.java`（`/ws/huddle` 注册）、`ImHuddleController.java`（10 端点 start/join/leave/end/mute/screen-share/participants）
- **媒体不经应用服务器**：Java 只管房间元数据与信令中继，音视频走 WebRTC（P2P 或 SFU）

**涉及文件**
- `frontend/src/features/im/HuddlePanel.tsx` [新建] — WebRTC 音视频渲染 + 静音/挂断控制
- `frontend/src/features/im/useHuddle.ts` [修改] — 消费 `sendSignal`/`peers`（建立 RTCPeerConnection、offer/answer/ICE）
- `frontend/src/features/im/ImLayout.tsx` [修改] — 占位 → 真实 `HuddlePanel`
- `frontend/vite.config.ts` [修改] — 补 `/ws/huddle` proxy（指向 8080，ws:true）
- 后端 `im/` [确认] — 信令 Handler 已够用，**不新增重型依赖**

**实现要点**
1. `vite.config.ts` 补 `/ws/huddle` 代理（与 `/ws` 同指向 `http://localhost:8080`，`ws: true`）。
2. `useHuddle`：在 WebSocket 信令基础上封装 WebRTC —— `join` 后为每个 peer 建 `RTCPeerConnection`，通过 `sendSignal` 交换 offer/answer/ICE candidate；`getUserMedia` 采集本地音视频。
3. `HuddlePanel`：右下角悬浮毛玻璃面板，本地+远端 `<video>`（圆角裁剪、`autoPlay playsInline`），静音/关闭摄像头/挂断按钮（`.glass-button-primary` / `.glass-button`）。
4. 未配置 SFU 时降级为 P2P mesh 并**明确提示**，**禁止**假成功。

**验收**
```bash
cd frontend && npx tsc --noEmit && npx vite build
# 手动：两人加入同一 Huddle → 双方看到对方音视频；静音/挂断生效
```

---

## T6 · 企业微信接真（复制钉钉成功范式）

**目标**：`WeComController`/`WeComAppService` 接真实企业微信 API（SSO 扫码登录 + 通讯录同步 + 应用消息）。

**背景现状（真实代码事实）**
- `backend-java/.../integration/wecom/WeComController.java` — 已有端点骨架，但**通讯录同步仍是 `// TODO: 调用企业微信通讯录 API 同步部门/成员`**
- `backend-java/.../integration/wecom/WeComAppService.java` — 已有 `loginFromWeCom(code)`（按 `wecom_` + 摘要用户名建号，与钉钉 `dt_` 前缀模式对称）；需确认 `getAccessToken` 是否已接真
- **钉钉是成熟范式**（`integration/dingtalk/`）：`DingTalkController`（auth-url/login/sync-org/approval-callback）、`DingTalkAppService`（code 交换）、`DingTalkOrgSyncService`（通讯录）、`DingTalkApprovalService`（OA 审批）、`DingTalkSyncJob`（定时同步）→ **照此结构复刻企微**
- Python 侧 `services/connectors/wecom.py` 也有 `WeComConnector`（gettoken → message/send）
- 统一 `RestTemplate` Bean 已配置（池化，httpclient5）

**涉及文件**
- `backend-java/.../integration/wecom/WeComController.java` [修改] — 接真：auth-url / callback（签发 JWT）/ sync-contacts / message/send
- `backend-java/.../integration/wecom/WeComAppService.java` [修改] — 真实 API：`gettoken` / `getuserinfo` / `user/get` / `department/list` / `user/list` / `message/send`
- `backend-java/src/main/resources/application.yml` [修改] — 补 `wecom.corp-id` / `corp-secret` / `agent-id` / `redirect-uri` 配置项
- `backend-java/.../config/SecurityConfig.java` [确认] — 放行企微免密端点（照钉钉做法）

**实现要点**
1. 真实 API 调用（用注入的池化 `RestTemplate`）：
   - `GET https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=&corpsecret=` → access_token
   - `GET .../user/getuserinfo?access_token=&code=` → userid
   - `GET .../user/get?access_token=&userid=` → 姓名/邮箱/头像/手机/unionid
   - `GET .../department/list`、`GET .../user/list` → 通讯录同步
   - `POST .../message/send?access_token=` → 应用消息
2. SSO callback **签发 JWT + refresh token**（照 `DingTalkController` 做法），返回结构与其他登录入口一致（含 `access_token`/`refresh_token`）。
3. 用户名：`wecom_` + unionid 摘要（与钉钉 `dt_` 对称），密码随机（不走密码登录）。
4. 通讯录同步：照 `DingTalkOrgSyncService` + `DingTalkSyncJob` 加定时同步。
5. **删除所有 TODO**，未配置时返回明确错误（不假成功）。

**验收**
```bash
cd backend-java && mvn test
grep -rn "TODO" backend-java/src/main/java/com/nocobase/integration/wecom/ || echo "no TODO"
```

---

## T7 · 微信客服 + Livechat 转工单

**目标**：`LivechatWidget` 去掉硬编码外部 Rocket.Chat，接**本平台**后端，并支持"会话转工单"。

**背景现状（真实代码事实）**
- `frontend/src/features/im/LivechatWidget.tsx:45` — `livechatUrl = 'http://localhost:3000'`（**外部 Rocket.Chat，与本平台无关**）
- 该组件已实现：悬浮入口、会话窗口、消息收发、`/api/v1/livechat/room|messages.history|message|close`、`onTicketCreated` 回调
- 后端审计：**无 Ticket/工单实体**（全仓搜索无 `TicketEntity`）
- 可复用：IM 私聊（`ImChannelController` `POST /direct/{userId}` 建私聊）+ `project` 任务（`ProjectTaskEntity`）作为工单载体
- 通知渠道已有 `WeChatWorkDispatcher`（`notification/`），支持 `WECHAT_WORK` 类型

**涉及文件**
- `frontend/src/features/im/LivechatWidget.tsx` [修改] — 改为调**本平台** API（`/api/im/...`），去掉硬编码 localhost:3000
- `backend-java/.../im/ImChannelController.java` 或新建 `LivechatController` [修改/新建] — 客服会话端点
- 工单承载：**优先复用** `ProjectTaskEntity`（不改 schema）或新建 V35 表（若确需）

**实现要点**
1. Livechat 会话 → 复用 **IM 私聊通道**（客服账号与访客建 direct channel），消息走 `MessageService`。
2. "转工单" → 创建一条 `ProjectTask`（标题=会话摘要，描述=会话记录，状态=TODO，优先级=HIGH），通过 `onTicketCreated` 回传工单 ID。
3. 微信客服对接：用 `notification` 的 `WECHAT_WORK` 渠道或企微客服消息 API 转发。
4. 前端：毛玻璃会话窗（已有），消息气泡沿用 IM 样式；**去掉**外部 URL 硬编码，改走 `apiClient`（`/api` 代理）。
5. 若新建表，迁移从 **V35** 起；否则复用现有实体。

**验收**
```bash
cd backend-java && mvn test
cd frontend && npx tsc --noEmit && npx vite build
# 手动：Livechat 发消息 → 本平台 IM 收到；点"结束会话" → 生成工单
```

---

## T8 · Slack 双向（修复 verify bug + 入站事件）

**目标**：修复 Slack 连接器 verify 必然 401 的 bug，实现**入站事件接收 + 出站发消息**双向。

**背景现状（真实代码事实）**
- `backend-python/src/nocobase_py/routers/integration.py:108-131` — `slack_verify` **注入了 `Depends(get_current_user)`**，Slack Events API 不带 Bearer JWT → **依赖先抛 401**，后面的 challenge 返回与验签逻辑**永远执行不到**
- 且**顺序颠倒**：119-127 行**先盲返 challenge 再验签** → 任何人都能拿到 challenge；`except Exception: pass` 吞掉异常
- `services/connectors/slack.py` — `SlackConnector` 已有 HMAC-SHA256 验签（`v0:{ts}:{body}`，5 分钟时间窗，`hmac.compare_digest`）与 `send_message`（`chat.postMessage`，支持 `thread_ts`）
- `slack.py:25` — `base_url` 是无占位符的 f-string（ruff F541，顺手修）
- Python Settings 已有 `slack_signing_secret` / `slack_bot_token` / `slack_team_id`

**涉及文件**
- `backend-python/src/nocobase_py/routers/integration.py` [修改] — 修 `slack_verify`；加入站事件端点
- `backend-python/src/nocobase_py/services/connectors/slack.py` [修改] — 修 f-string；补入站事件解析
- `backend-python/src/nocobase_py/config.py` [确认] — Slack 配置项已存在

**实现要点**
1. **修 verify**：
   - **去掉** `Depends(get_current_user)`（Slack 回调无 JWT）
   - **先 HMAC 验签**，验签通过后再处理；URL 验证 challenge 用 `PlainTextResponse` 返回 challenge 字符串
   - 验签失败返回 401，**不得**先返 challenge
2. **入站事件**：接收 Slack Events（`message.channels` 等）→ 转为平台消息/通知（可转发到 Java IM 或落 Python 侧任务队列）。
3. **出站**：`send_message`（已有）保留，确保 bot_token 生效。
4. 修 `slack.py:25` f-string（去掉多余 `f` 前缀）。

**验收**
```bash
cd backend-python && pytest
# 手动：curl 模拟 Slack 带签名的 challenge 请求 → 返回 challenge 明文（不再 401）
```

---

## T9 · 飞书 + Mattermost

**目标**：修飞书连接器 bug（JSON 注入 + token API），新建 Mattermost webhook 兼容适配器。

**背景现状（真实代码事实）**
- `services/connectors/feishu.py:55` — **手工拼 JSON**：`content: '{"text": "' + text.replace('"','\\"') + '"}'`，未转义反斜杠/换行/控制字符 → **JSON 注入**（消息含 `\` 或换行即破坏 JSON）
- `services/connectors/dingtalk.py:34-37` — 用**旧版** `/gettoken?appkey=&appsecret=`；钉钉新版为 `POST /v1.0/oauth2/accessToken`（Header 传 appKey/appSecret）→ 大概率失效（**顺手修**）
- `wecom.py` / `dingtalk.py` / `feishu.py` 均 import 了 `hashlib`/`hmac`/`time` **但未使用**（ruff F401，mypy strict 过不了）
- **Mattermost 全仓零命中** —— 需新建连接器
- `routers/integration.py` 已有 `feishu_send` 路由

**涉及文件**
- `backend-python/src/nocobase_py/services/connectors/feishu.py` [修改] — 用 `json.dumps` 构造消息体（修注入）；更新 token 获取
- `backend-python/src/nocobase_py/services/connectors/dingtalk.py` [修改] — 更新为新版 `POST /v1.0/oauth2/accessToken`；删未用 import
- `backend-python/src/nocobase_py/services/connectors/mattermost.py` [新建] — Mattermost webhook 兼容适配器
- `backend-python/src/nocobase_py/routers/integration.py` [修改] — 加 `/mattermost/send` 路由
- `services/connectors/wecom.py` [修改] — 删未用 import

**实现要点**
1. **飞书**：用 `json.dumps({"text": text})` 替代手工拼接；tenant_access_token 获取按官方 `/open-apis/auth/v3/tenant_access_token/internal`。
2. **钉钉**：改 `POST https://api.dingtalk.com/v1.0/oauth2/accessToken`，Header 传 `appKey`/`appSecret`。
3. **Mattermost**：新建 `MattermostConnector` — 兼容 Mattermost Incoming Webhook（POST JSON `{text, channel, username}`），并支持 Outgoing Webhook 回调解析（token 校验）。
4. 清理所有未使用 import（ruff F401 / mypy strict）。

**验收**
```bash
cd backend-python && pytest
cd backend-python && ruff check src/ 2>/dev/null || true
# 手动：飞书发送含换行/反斜杠的消息 → JSON 不破坏
```

---

## T10 · 全栈门禁 + 分栈提交 + 记忆更新

**目标**：三栈门禁全绿后分栈提交，并更新记忆文件。

**验收命令（全跑一遍）**
```bash
# 后端（基线 1057，只允许增加）
cd backend-java && mvn test
# 前端
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
# Python
cd backend-python && pytest
```

**提交规范**（分栈提交）
```bash
git add -A && git commit -m "[java] Phase52: ..."
git commit -m "[python] Phase52: ..."
git commit -m "[js] Phase52: ..."
```

**记忆更新**
追加到 `/home/who/multistack-project/.codebuddy/memory/2026-09-21.md`（`replace_in_file` 追加）：
- 本轮完成的任务与门禁结果
- 关键修复（全局搜索写入器、Slack verify、飞书 JSON 注入等）
- 遗留项（TIMELINE 视图、批量 API、中文分词 zhparser、pgvector 后续评估）

---

## 附录 A · 真实契约（调用前必须确认，禁止臆造）

```java
// search/UnifiedSearchService.java —— 索引写入器（当前无调用方，本轮接真）
public void indexEntity(String entityType, String entityId, String tenantId,
                        String title, String content, Map<String, Object> metadata);
public void removeEntity(String entityType, String entityId, String tenantId);
// 注：content_tsv 由 DB 触发器维护，应用层不得写入

// api/UserController.java —— GET /api/users/me
// 返回 Map{code, message, data:{id, username, tenant_id, roles: List<String>}}

// auth/RefreshTokenService.java —— Redis，单次使用
public String issue(UUID userId);
public UUID consume(String token);   // 消费后删除

// view/ViewEntity.java —— 视图类型枚举（本轮暂不改）
// TABLE, KANBAN, DETAIL, GALLERY, CALENDAR   （无 TIMELINE）

// meta/CollectionService.java —— 审计提示真实签名（用前再确认）
// public List<Map<String, Object>> listRecords(String collectionName, String tenantId, int limit);

// notification/NotificationDispatcher.java —— SendResult 仅有 ok/error
// record SendResult(boolean ok, String detail) { static ok(...) / static error(...) }

// tenant/TenantContext.java —— 无 getTenantId()，用 currentTenantId() / requireTenantId()
```

```python
# Python Settings 已有连接器配置（config.py）
# slack_signing_secret / slack_bot_token / slack_team_id
# wecom_corp_id / wecom_corp_secret / wecom_agent_id
# dingtalk_app_key / dingtalk_app_secret / dingtalk_agent_id
# feishu_app_id / feishu_app_secret
```

## 附录 B · 任务 → 竞品映射

| 任务 | 对标产品 | 整合点 |
|---|---|---|
| T1 统一工作台+导航 | NocoBase / Notion | 统一入口，六域聚合 |
| T2 全局搜索接真 | Slack / Notion | 跨模块统一搜索（最大断层） |
| T3 AI Copilot | Notion AI / Airtable AI | 统一 AI 入口（对话+Agent） |
| T4 暗色主题全覆盖 | 全部 | 视觉统一（Glassmorphism） |
| T5 Huddle 音视频 | Slack Huddle / Rocket.Chat | 真实 WebRTC |
| T6 企业微信 | 钉钉范式 | SSO+通讯录+消息 |
| T7 微信客服/Livechat | Rocket.Chat Livechat | 客服转工单 |
| T8 Slack 双向 | Slack | 入站 Events + 出站消息 |
| T9 飞书+Mattermost | Mattermost | 飞书消息 + Webhook 兼容 |
| T10 门禁提交 | — | 质量红线 |

## 附录 C · 给 GLM-5.3 的执行指令

1. **严格按 T1 → T10 顺序执行**（T4/T5 可并行，T6–T9 可并行）。
2. 每个任务开始前，先用 `[subagent:code-explorer]` 或 `[skill:lsp-code-analysis]` **确认**所有要调用的符号真实存在与签名（红线 #2）。
3. 每任务完成后**立即跑对应门禁**（`mvn test` / `tsc`+`build` / `pytest`），失败不许进入下一任务。
4. **禁止**用 `log.info` + `TODO` 冒充接真（红线 #1）；未配置外部服务时返回明确错误，**禁止假成功**。
5. 前端一律用 `theme/tokens` CSS 变量 + MUI `sx`，**禁止硬编码亮色**（红线 #3）。
6. 迁移版本号从 **V35** 起；本轮尽量不改 schema（红线 #4）。
7. 不引入 pgvector 等重型依赖（红线 #5）。
8. 完成后更新 `/home/who/multistack-project/.codebuddy/memory/2026-09-21.md`。
