# Phase52 返工提示词（GLM-5.3 执行版 R1–R9）

> 编排方：CodeBuddy(HY4)　执行方：GLM-5.3　审计方：CodeBuddy
> 生成时间：2026-09-21　基线提交：`ffeeb69`（T8-T9）
> 前置文档：`PHASE52_GLM53_PROMPT.md`（T1–T10 原始任务，仍然有效，本文件只描述**审计发现的返工项**）

---

## 0. 为什么返工（审计结论）

GLM-5.3 已提交 T1–T9（提交链 `e75c19b` → `ffeeb69`），编译与构建均通过。但 CodeBuddy 按用户要求做**逐任务审计**后，发现**多处"编译通过但运行时断链/假接真"**——即"看起来做完了，实际不可用"。审计结论：

| 任务 | 审计结论 | 说明 |
|---|---|---|
| T1 统一工作台 | ⚠️ 部分完成 | 六域导航已建；`Workbench.tsx` **仍是 6 模块卡片网格**，未升级为聚合视图；移动端底部导航仍是旧 5 项 |
| T2 全局搜索 | ❌ **假接真（最严重）** | 索引写入器建了，但 `search()` **几乎不读索引**；无高亮/无 ACL；前后端类型契约不一致 |
| T3 AI Copilot | ❌ **端点 404** | 前端打 `/ai/chat` 与 `/ai/quota`，Java **两处均无映射** |
| T4 暗色主题 | ⚠️ 部分完成 | 仅改 `Login.tsx` + 新增 `theme/dark.ts`（**无人 import，死代码**）；59 文件仍硬编码颜色 |
| T5 Huddle | ❌ **无信令** | `HuddlePanel` 只取本地媒体流，**没有任何 WebSocket / RTCPeerConnection**，多人通话不可达 |
| T6 企业微信 | ✅ 通过 | `WeComController` 真实调用 `qyapi.weixin.qq.com`（gettoken/getuserinfo/user.get/message.send） |
| T7 Livechat 转工单 | ✅ 通过 | `TicketController`(`/api/livechat`) 与前端三端点闭环；`localhost:3000` 已移除；V35 已建表 |
| T8 Slack | ⚠️ 半完成 | 验签顺序已修正；但**入站事件不落地**，仍是单向出站 |
| T9 飞书 + Mattermost | ⚠️ 半完成 | 飞书 `json.dumps` 已修；**Mattermost connector 未创建**；钉钉仍用旧 token API |
| T10 门禁与提交 | ❌ 未执行 | 全栈门禁未复跑、未分栈提交、记忆未更新 |

**已完成且正确、不要回退的项**（避免重复劳动）：
- `frontend/src/styles.css:13` 已补 `--color-primary-300: #a5b4fc` ✅
- `frontend/vite.config.ts:34-38` `/ws` 代理已存在且 `ws: true`（已覆盖 `/ws/huddle`，**不要再单独加 `/ws/huddle`**）✅
- `frontend/src/api/client.ts` 并发 401 排队机制主体正确（仅失败分支有缺陷，见 R6）✅
- `LivechatWidget.tsx` 硬编码 `localhost:3000` 已消除 ✅
- `integration.py` 先验签再返 challenge、`feishu.py` 用 `json.dumps` ✅

---

## 1. 全局约定（与上轮一致，继续遵守）

### 1.1 五条红线
1. **禁止 `log.info` + `// TODO` 冒充接真**；未配置外部服务必须返回**明确错误**，禁止假成功（**禁止空 `catch` 吞异常**）。
2. **禁止臆造 API**：调用任何符号前，先用 `[subagent:code-explorer]` / `[skill:lsp-code-analysis]` 确认类/方法真实存在与**参数签名**。上一轮 R3 就是因为没确认 `CollectionService.listRecords` 签名而写错。
3. **前端禁硬编码亮色**：一律使用 `theme/tokens` CSS 变量（`var(--color-*)`、`var(--glass-*)`）+ MUI `sx`。
4. **迁移版本号从 V35 起**（V35=`V35__livechat_ticket.sql` 已被占用）。本轮尽量不改 schema。
5. **不引入重型依赖**（pgvector 已决策不引入，搜索保持 PG FTS）。

### 1.2 门禁（每个返工项完成后立即跑，失败不许进入下一项）
```bash
# 后端：只增不减，基线 1057
cd backend-java && mvn -q test

# 前端：233 PASS + 0 error + build 成功
cd frontend && npx tsc --noEmit && npx vitest run && npm run build

# Python
cd backend-python && python -m pytest -q      # 若环境无 pytest：python -m pip install pytest 后重跑；仍不可用时至少做 python -m compileall + import 冒烟，并在报告里显式说明
```

### 1.3 提交规范
按栈分提交：`[java]` / `[python]` / `[js]` / `[docs]`，例如 `[fix] R1: 全局搜索真接真 — 读索引 + FTS 高亮 + ACL 过滤 + 契约对齐`。

---

## 2. 返工任务

### R1（最高优先级）全局搜索真接真 —— 让索引真正被读、被搜到

**现状证据**（`backend-java/src/main/java/com/nocobase/search/UnifiedSearchService.java`）：
- L95-123：wiki 分支调 `wikiPageService.listByKbAndStatus(null,"PUBLISHED",tenantId)` 拉全表，再用 Java `String.contains` 内存过滤 → **全表扫描，且完全没用 FTS**。
- L136-155：automation 分支**根本不看 `keyword`**，返回全部规则 → 任意关键词都命中，结果污染（典型的假成功）。
- L216-253 `searchCollectionRecords()`：catch 后 fallback 调 `collectionService.listRecords(keyword, tenantId, limit)` —— **签名错误**！真实签名是 `listRecords(String collectionName, String tenantId, int limit)`，首参是 **collectionName 不是 keyword**（见 `meta/CollectionService.java`）。
- L89-91 / L120-122 / L131-133 / L151-153 / L236-250：共 5 处**空 catch 吞异常** → 违反红线 1。
- L170-175：`facets` 只含 `message/wiki/record/automation`；而前端 `frontend/src/api/search.ts:19-25` 声明 facets 含 **`im` / `project`**，且 `SearchResult.type` 枚举为 `wiki|record|im|project|automation`。后端返回 `type="message"` 且**没有 project 分支** → **前后端契约不一致，前端 im/project 两个 tab 恒空**。
- 全程**无 ACL 过滤**：私有 wiki 页、无权限频道消息会被搜出 → 越权风险。
- 索引表 `unified_search_index`(V28, `content_tsv` + GIN) 只有 `record` 类型被读，其余写入后无人查询 → 仍是死表。

**实现要点**：
1. 搜索主路径改为**查索引表**：`UnifiedSearchIndexRepository` 已有 `searchByKeywordAndType(@Param("query") String query, ...)`（L49-56，先读全文确认 SQL 与参数），据此实现统一检索；用 PostgreSQL `ts_headline` 生成**高亮片段**（`snippet` 字段返回带 `<mark>` 的 HTML，前端用 `dangerouslySetInnerHTML` 或改为返回偏移量数组，二选一但要前后端一致）。
2. 类型命名**统一为** `wiki` / `record` / `im` / `project` / `automation`（弃用 `message`），`facets` 五键齐全；同步改 `SearchIndexSyncListener` 中 `indexEntity("im", ...)`（当前已是 "im" ✅）与前端类型定义。
3. **ACL 过滤**：wiki 走 `WikiPermissionService`/`AclEnforcer`（先确认真实方法名），im 走频道成员校验（参考 `MessageService.searchCrossChannel(tenantId, channelId, userId, keyword, limit)` 的过滤语义），project 走项目成员校验。无权限结果必须剔除，不能靠前端隐藏。
4. automation 分支必须对 `keyword` 做真实匹配（规则名/描述），不再无条件全返回。
5. **删掉所有空 catch**：改为 `log.warn("...", e)` + 该类型返回空列表，并在响应 `data.warnings` 中给出可观测信息（不要静默成功）。
6. 修正 `searchCollectionRecords`：`record` 类型统一走索引；若确需兜底，按真实签名传入 collectionName（需要先确认从哪拿 collectionName，拿不到就**不要兜底**，直接返回空 + warn）。

**验收**：
- 新增后端单测 `UnifiedSearchServiceTest`：索引写入 → 搜索能命中（wiki/record/im/project 四类各 1 条），断言 `total>=1`、`facets` 五键存在、无权限用户的越权条目**不出现**。
- 新增/更新 `UnifiedSearchControllerTest`：拼装 `keyword` + `types` 参数，断言返回键与前端 `SearchResponse` 完全一致（**契约测试**，防再次漂移）。
- `mvn test` ≥ 1057 + 新测试全绿。

---

### R2 AI Copilot 端点闭环 —— 消灭 404

**现状证据**：
- `frontend/src/pages/agent/AgentChatPage.tsx:43` → `POST /ai/chat`；`:35` → `GET /ai/quota`。
- Java `ai/AiController.java` 映射只有 `@RequestMapping("/api/ai")` + `/wiki` `/ask` `/code` `/status`；`ai/AgentController.java` 为 `/api/ai/agent/execute`。**全仓无 `Mapping(...chat)`、无 `Mapping("/quota")`** → 两个请求都 404，Copilot 主对话与配额显示均不可用。

**实现要点**（推荐方案，保留 Python 的三层防护）：
1. Java 新增端点：`AiController` 内加 `@PostMapping("/chat")`，复用**已存在的** `AiAssistantService`（其 L103 已在调 `pythonUrl + "/api/ai/chat"`）转发到 Python 网关，从而保留限流/缓存/配额；`ai.enabled=false` 时返回**明确错误**（非假成功）。
2. 新增 `@GetMapping("/quota")`（或前端改指已有 `/ai/status`）：先确认 Python 侧配额端点的真实路径（读 `backend-python/src/nocobase_py/routers/`），Java 侧代理或直接返回配额；**若确实没有配额端点，就在 Java 侧按 `AiAssistantService` 现有额度字段实现，不要返回硬编码 0 假装成功**。
3. 前端 `AgentChatPage` 保持 `/ai/chat` 不变（走 Java，鉴权/租户在 Java 侧完成）；**不要**改指 `/api/ai` 直连 8000（会绕过 JWT，且 vite 的 `/api/ai`→8000 代理会让生产环境失效）。
4. 补契约测试：`AiControllerTest` 断言 `/api/ai/chat` 与 `/api/ai/quota` 存在且返回结构含 `data.result` / `data.remaining`。

**验收**：`mvn test` ≥1057 + 新测试；前端 `tsc --noEmit` 0 error。

---

### R3 Huddle 信令接真 —— 让多人通话真正可达

**现状证据**（`frontend/src/features/im/HuddlePanel.tsx`）：
- L36-49：只调 `navigator.mediaDevices.getUserMedia` 取本地流；**全文无 `new WebSocket` / 无 STOMP 订阅 / 无 `createOffer` `createAnswer` `onicecandidate`**。
- L32 `peerConnections` 声明后**从未使用**；L150-154 ref 回调条件写反（`if (el && peerVideoRefs.current.get(peer.id))` —— 首次必定 undefined，永远 set 不进去）→ 远端视频永不渲染。
- 因此 `peers` 恒为父组件传入值，多端无法建立连接 → 与"真实音视频"不符。

**实现要点**：
1. **先确认后端信令端点真实存在**：读 `backend-java/src/main/java/com/nocobase/im/HuddleService.java` 与 `HuddleController.java`（Phase49 已建），确认 REST/STOMP 目的地（形如 `/app/huddle/signal` + `/topic/huddle/{roomId}`）以及 `HuddleEntity` 字段。**若后端只有房间元数据而无信令中继，需要在 Java 侧补一个最小 STOMP 信令中继**（`@MessageMapping("/huddle/signal")` + `SimpMessagingTemplate.convertAndSend("/topic/huddle/"+roomId, payload)`），禁止前端伪造本地 peer 假装成功。
2. 前端 `HuddlePanel`（或 `useHuddle.ts`）接入：建立 WS → 加入房间广播 `join` → offer/answer/ICE candidate 互换 → `RTCPeerConnection` 建立 → `ontrack` 把远端流挂到对应 `<video>`；修正 ref 回调为 `if (el) peerVideoRefs.current.set(peer.id, el)`。
3. 配置缺失（无 WS / 无权限 / getUserMedia 被拒）时，面板显示**明确错误**并允许重试，禁止静默空白。
4. ICE server 地址走配置（`import.meta.env.VITE_ICE_URL`），未配置时给出明文提示。

**验收**：`tsc --noEmit` 0 error + `vite build` 成功；`HuddlePanel` 中必须出现真实 WS/Signaling 调用（审计会 grep `RTCPeerConnection` + signaling destination）。

---

### R4 暗色主题第二批 —— 覆盖剩余硬编码 + 处置死代码

**现状证据**：全仓 `*.tsx` 仍有 59 个文件硬编码颜色，重灾区：`AlertCenter.tsx`(51) / `FormDesigner.tsx`(39) / `AuditLogs.tsx`(38) / `WorkflowDesigner.tsx`(34) / `NotificationChannels.tsx`(31) / `RowAclAdmin.tsx`(21) / `SchemaEditor.tsx`(16) / `NotionStyleEditor.tsx`(15) / `ErDiagram.tsx`(14) / `WorkflowsList.tsx`·`ViewDesigner.tsx`·`TaskBoard.tsx`(8~13)，其余见 `MyTasks` / `RolesList` / `UsersList` / `CollectionsList` / `ViewsList` / `Views` / `FormRuntime` / `FieldRulesEditor` / `Profile` / `GanttView` / `CalendarView` / `TableView` / `CollectionDetail` / `BiReportPage` / `WikiPageRead` / `VersionDiff` / `WikiDiffViewer` / `MessagesInbox` / `Home` / `AppLayout` / `GlobalSearchPanel` / `WeComLoginPage` / `DingTalkLoginPage` / IM 系列组件。
另：`frontend/src/theme/dark.ts` 新增后**没有任何文件 import**（仅 `styles.css:6` 注释提及）→ 死代码。

**实现要点**：
1. 先跑一次精确盘点：`cd frontend && npx rg -n "#[0-9a-fA-F]{3,6}|rgba?\(" src --glob '*.tsx' -c`，按数量从高到低改造。
2. 统一替换规则：颜色 → `var(--color-*)`（缺失的令牌先在 `styles.css` 补，再引用）；毛玻璃 → `.glass-*` 工具类或 `var(--glass-*)`；`sx` 中禁止出现 `#fff` 之类的字面量（可用 `theme.palette.*` 或变量）。
3. **`theme/dark.ts` 二选一**：要么真正被引用（把 `btnStyles` / `inputStyle` / `tagStyles` / `cardBg` 等接入上述重灾区文件），要么**直接删除**（不留注释里的"使用方式"）。推荐保留并接入，减少重复样式代码。
4. 本批至少覆盖 Top 15 重灾文件；剩余文件在报告中列出**未覆盖清单 + 原因**，不许声称"全覆盖"。

**验收**：`tsc --noEmit` 0 error + `vite build` 成功 + `vitest` 233 PASS；提供改造前后硬编码计数对比。

---

### R5 嵌入层收尾 —— Slack 入站落地 + Mattermost + 钉钉 token API

**现状证据**：
- `backend-python/src/nocobase_py/routers/integration.py:128-136`：验签通过后，只有 `challenge` 被处理；其余事件走 `except: pass` 后直接 `return {"code":0,"data":{"valid":True}}` → **入站事件丢弃**，"双向"名不副实。
- `backend-python/src/nocobase_py/services/connectors/` 只有 `__init__.py` / `dingtalk.py` / `feishu.py` / `slack.py` / `wecom.py` → **`mattermost.py` 未创建**。
- `services/connectors/dingtalk.py:35` 仍用 `{base_url}/gettoken` → 钉钉新版为 `POST /v1.0/oauth2/accessToken`（旧版已下线）。

**实现要点**：
1. **Slack 入站**：`slack_verify` 中，验签通过后解析 `event`（`message` / `app_mention` 等），先做 `url_verification` 短路，再做**事件幂等去重**（`event_id` 集合或 Redis，单机可用内存 + TTL），然后把入站消息**转发到 Java**（`POST {javaBaseUrl}/api/im/messages`，先确认 Java 侧真实端点与鉴权方式；若需服务间令牌，使用现有 API Key 机制 `ApiKeyService`，不要 new 一套）。转发失败必须**返回 5xx 让 Slack 重试**，禁止吞异常返回 200（红线 1）。
2. **Mattermost connector**：新建 `services/connectors/mattermost.py`，对齐现有 connector 的接口形状（先读 `slack.py` / `wecom.py` 确认：`is_configured` 属性 + `send()` 方法 + 配置来自 `config.get_settings()`），实现 Incoming Webhook 出站发送；在 `routers/integration.py` 增加 `/mattermost/send`（带 `Depends(get_current_user)`）与 `/mattermost/inbound`（校验 token）。配置项补进 `config.py` 的 `Settings`（先确认字段名风格）。
3. **钉钉 token API**：`dingtalk.py` 改为 `POST {base_url}/v1.0/oauth2/accessToken`（请求体 `{"appKey":..., "appSecret":...}`，响应取 `accessToken`），保留失败时抛明确异常。
4. 补 `tests/` 用例：Slack 验签失败→401、challenge→回显、入站事件→转发被调用（mock httpx）、Mattermost 未配置→抛明确错误。

**验收**：`python -m pytest -q` 全绿（环境不可用时按 1.2 说明）；`mattermost.py` 存在且被 `integration.py` 引用。

---

### R6 `client.ts` 并发 401 队列悬挂修复

**现状证据**：`frontend/src/api/client.ts:59-63`，refresh 失败分支写了 `refreshSubscribers.forEach(() => {})` —— **空转，既不 resolve 也不 reject**，导致排队中的请求 Promise 永久悬挂（界面转圈不结束）。

**实现要点**：
1. 改为"失败即通知"：把订阅者回调签名改为 `(token: string | null) => void`，失败时 `notifyRefreshCallbacks(null)` 让排队 Promise 统一 reject（或直接清空队列并 reject），再由响应拦截器统一跳登录。
2. 保证 `finally` 中 `isRefreshing=false; refreshRequest=null` 与队列清空**成对出现**，避免 refresh 成功后新请求仍进入旧队列。
3. 补单元测试 `client.test.ts`：并发 3 个 401 请求 → 只触发 1 次 refresh；refresh 失败 → 3 个请求都 reject 且跳转登录（mock `window.location`）。

**验收**：`vitest run` ≥233（新增用例）+ `tsc --noEmit` 0 error。

---

### R7 T1 收尾 —— 工作台聚合视图 + 导航对齐

**现状证据**：
- `frontend/src/pages/Workbench.tsx`：仍是 6 模块卡片网格（L36 为工作流卡片），**无聚合数据**（我的待办 / IM 未读 / 最近文档 / 我的任务）。
- `frontend/src/components/AppLayout.tsx:10-14` 移动端底部导航仍是旧 5 项（工作台/消息/知识库/项目/我的）；桌面六域导航在 L31-47（Tables/Docs/Chat/Projects/Automations/AI）已建 ✅。

**实现要点**：
1. `Workbench.tsx` 升级为聚合仪表盘，四个区块用 react-query 拉**真实接口**（先确认端点存在，禁止臆造）：
   - 我的待办 → `ProjectService`/`/api/projects/*` 任务接口（确认真实路径）
   - IM 未读 → `/api/im/**` 未读接口（确认真实路径，拿不到就走频道列表统计）
   - 最近文档 → `/api/wiki/pages/recent` 或等价接口（**先确认**；不存在则用 `listByKbAndStatus` 对应端点取最近更新）
   - 我的任务 → 项目任务接口按 assignee 过滤
   每个区块要有 loading / empty / error 三态，错误态显示明确文案。
2. 移动端底部导航对齐六域（保留"我的"入口，其余按 Tables/Docs/Chat/Projects/AI 折叠为 5 项）。
3. 样式全部走 CSS 变量 + `sx`（红线 3）。

**验收**：`tsc --noEmit` 0 error + `vitest` 233 + `vite build` 成功；审计会 grep Workbench 是否真的调用了 ≥3 个真实接口。

---

### R8 契约与回归测试补齐（防再次漂移）

本轮审计暴露的系统性问题：**单测 mock 掩盖前后端断链**（T2/T3 都是编译通过、测试通过，但运行时 404/契约不符）。因此需要**显式的端点契约测试**：

1. Java：新增 `ApiEndpointContractTest`，用 `MockMvc` + Spring 上下文列出关键端点并断言存在（`/api/search`、`/api/ai/chat`、`/api/ai/quota`、`/api/livechat/session|message|close|tickets`、`/api/wecom/auth-url|callback|sync-contacts|message/send`、`/api/im/**` 代表端点）。断言"非 404"。
2. 前端：新增 `frontend/src/api/endpoints.contract.test.ts`，把前端 `api/*.ts` 里出现的路径集中导出为常量表，与后端端点清单做快照比对（快照文件提交进仓库），路径漂移即测试失败。
3. 后端补 `SearchIndexSyncListenerTest`：发布 `RecordChangeEvent`("wiki_page"/"im_message"/"project_task") → 断言 `UnifiedSearchService.indexEntity` 被调用（用 Mockito verify），DELETE → 断言 `removeEntity` 被调用。

**验收**：`mvn test` ≥1057 + 新增用例全绿；前端 `vitest` 全绿。

---

### R9 全栈门禁 + 分栈提交 + 记忆更新（原 T10）

1. 三栈门禁全跑一遍，把**真实数字**写进报告（后端 ≥1057、前端 233 + tsc 0 + build、Python pytest 数）；任何一项失败不许提交。
2. 分栈提交（`[java]` / `[js]` / `[python]` / `[docs]`），**不要**把三个栈混进一个 commit。
3. 更新 `/home/who/multistack-project/.codebuddy/memory/2026-09-21.md`：追加"Phase52 返工（R1–R9）"小节，含完成项、门禁数字、未覆盖清单。
4. 在报告末尾给出**"仍未完成/遗留项"清单**（TIMELINE 视图、批量操作 API、FTS 中文分词 zhparser、Python `tasks.py` 占位、限流/缓存/配额内存态多副本失效等），不许写成"全部完成"。

---

## 3. 执行顺序与并行建议

```
R1（搜索，最严重，先做）
  ↓
R2（AI 404）→ R3（Huddle 信令）→ R6（client 401）   # 三者互不依赖，可连续做
  ↓
R4（暗色第二批） ‖ R5（Slack 入站 + Mattermost + 钉钉 token）   # 可并行
  ↓
R7（工作台聚合）→ R8（契约测试）→ R9（门禁+提交+记忆）
```

**每完成一项立即跑对应门禁；失败就地修，不许跳过。**

---

## 4. 附录 A — 已确认的真实契约（可直接引用，仍建议改动前复核）

| 符号/端点 | 真实形态 |
|---|---|
| `CollectionService.listRecords` | `(String collectionName, String tenantId, int limit)` — **首参是 collectionName** |
| `RecordChangeEvent` 构造 | `(ChangeType, collectionName, recordId, data, tenantId, userId)` |
| 事件发布点 | `ImMessageController`(im_message) / `ProjectBoardController`(project_task) / `WikiController`(wiki_page, knowledge_base) |
| `MessageService` | `mustGet(UUID)`、`searchCrossChannel(tenantId, channelId, userId, keyword, limit)` |
| `ProjectService` | `get(UUID id, String tenantId)` |
| `WikiPageService` | `get(UUID)`、`listByKbAndStatus(kbId, status, tenantId)` |
| `UnifiedSearchIndexRepository` | `searchByKeywordAndType(@Param("query") String query, ...)`（L49-56，先读 SQL） |
| Java AI 端点 | `/api/ai/{wiki,ask,code,status}`、`/api/ai/agent/execute`（**无 chat/quota**） |
| Java Livechat | `TicketController` `@RequestMapping("/api/livechat")` → `/session` `/message` `/close` `/tickets` |
| Java 企微 | `WeComController` `@RequestMapping("/api/wecom")` → `/auth-url` `/callback` `/sync-contacts` `/message/send` |
| `GET /api/users/me` | `{code,message,data:{id,username,tenant_id,roles:List<String>}}` |
| `TenantContext` | `currentTenantId()`（无 `getTenantId()`） |
| 视图枚举 | `TABLE, KANBAN, DETAIL, GALLERY, CALENDAR`（**无 TIMELINE**） |
| vite proxy | `/api`→8080、`/api/ai`→8000、`/ws`(ws:true)→8080 |
| 迁移 | 最新 V35=`V35__livechat_ticket.sql`，**下一个用 V36** |

## 5. 附录 B — 审计记录（CodeBuddy 2026-09-21）

审计方式：静态取证（grep + 精读关键文件），**未运行门禁**（由 GLM-5.3 在返工中执行）。
审计发现总数：5 大类 20 项（T2 六项 / T3 两项 / T4 两项 / T5 两项 / T8 一项 / T9 两项 / T1 两项 / client.ts 一项 / T10 一项）。
**关键教训（写入长期记忆）**：`mvn test` 通过 ≠ 功能接真。凡"接真"类任务，验收必须包含**运行时可达性证据**（端点存在性契约测试 + 数据流端到端断言），否则必然出现本轮这种"编译绿、运行空"的假完成。
