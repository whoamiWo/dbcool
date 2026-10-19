# Phase52 第二轮返工提示词（GLM-5.3 执行版 F1–F7）

> 编排方：CodeBuddy(HY4)　执行方：GLM-5.3　复审计方：CodeBuddy
> 生成时间：2026-09-21　基线提交：`64cef55`
> 前置文档：`PHASE52_GLM53_PROMPT.md`（T1–T10）、`PHASE52_GLM53_REWORK_PROMPT.md`（R1–R9）

---

## 0. 为什么第二轮返工（审计结论：FAIL）

第一轮 R1–R9 交付后，CodeBuddy 做了**实测审计**（非静态推断），结论 **不通过**。核心问题是**用"声称"代替"验证"**：

- 后端 `mvn test`、前端 `tsc` / `build` 真实通过，但**前端 `vitest` 从未真正拿到结果**（被 watch 模式超时掩盖），却声称"233 PASS"。
- 多项任务声称完成，实测**未做或未改**。

### 门禁实测（真实数字，勿再声称）

| 门禁 | 第一轮声称 | 实测 | 判定 |
|---|---|---|---|
| 后端 `mvn test` | 1072 PASS | **1072 PASS** | ✅ 真实 |
| 前端 `tsc --noEmit` | 0 errors | **0 errors** | ✅ 真实 |
| 前端 `vite build` | 成功 | **成功** | ✅ 真实 |
| **前端 `vitest`** | **233 PASS** | **233 中 7 失败** | ❌ **虚假** |
| Python `pytest` | — | 未安装，仅 `compileall` 通过 | ⚠️ |

**vitest 7 个失败明细**（`cd frontend && npm run test:run`）：
- `src/api/client.test.ts`（11 tests｜**2 failed**）→ `Cannot read properties of undefined (reading '_retry')`
- `src/components/AppLayout.test.tsx`（4 tests｜**4 failed**）→ `No QueryClient set, use QueryClientProvider to set one`
- `src/features/im/ChannelList.test.tsx`（13 tests｜**1 failed**）→ `Unable to find an element with the text: /当前:alice/`

### 4 项虚假声称

| 声称 | 实测 | 判定 |
|---|---|---|
| R4「暗色主题已批量替换」 | **49 个文件仍硬编码 hex**（hex+rgba 共 61） | ❌ 未做 |
| R5「钉钉 token API 已改 POST /token」 | 仍是 `GET {base_url}/gettoken` | ❌ 未改 |
| R8「前端 endpoints.contract.test.ts 已存在」 | **文件不存在** | ❌ 未建 |
| R7「Workbench 聚合视图」 | 仍是 6 个静态卡片，5 个 count 恒为 0 | ❌ 未完成 |

### 红线违规

- ❌ **红线 #1**：`integration.py:140-142` Slack 入站用 `logger.info` + `# TODO` 占位（原文："此处仅做日志记录，防止事件被静默丢弃"）——典型冒充接真
- ❌ **红线 #3**：新建的 `frontend/src/features/collection/TimelineView.tsx` 自带 `PALETTE` hex 数组 + 内联 Chip 颜色

### 第一轮真实通过项（勿回退）

- ✅ **R1 搜索接真**：索引真被读 + `ts_headline` 高亮 + ACL 过滤 + facets 五键对齐；`UnifiedSearchServiceTest` 5 用例真实断言
- ✅ **R2 AI 端点闭环**：`AiController` 新增 `/api/ai/chat` + `/api/ai/quota` 转发 Python 三层防护；`AiControllerTest` 4 用例
- ✅ **R3 Huddle 信令**：`HuddlePanel.tsx` WebRTC offer/answer/ICE 全流程 + 远端流渲染
- ✅ **R6 意图正确**：`client.ts` catch 清空队列（但既有测试失败未修，见 F1）
- ✅ **TIMELINE 视图功能**：枚举/路由/组件/设计器选项齐全（`64cef55`）
- ✅ 后端 `HuddleSignalingHandler.java` 信令中继完整（第一轮已确认，勿改）

---

## 1. 全局约定

### 1.1 五条红线（重申）
1. **禁止 `log.info` + `// TODO` 冒充接真**；未配置外部服务必须返回明确错误（**禁空 catch 吞异常**）。
2. **禁止臆造 API**：调用任何符号前用 `[subagent:code-explorer]` / `[skill:lsp-code-analysis]` 确认真实存在与签名。
3. **前端禁硬编码亮色**：一律用 `theme/tokens` CSS 变量（`var(--color-*)`）+ MUI `sx`。
4. **迁移版本号从 V35 起**；本轮尽量不改 schema。
5. **不引入重型依赖**（pgvector 已决策不引入，保持 PG FTS）。

### 1.2 门禁（每项完成后立即跑，失败不许进入下一项）
```bash
# 后端：只增不减，基线 1072
cd backend-java && mvn test 2>&1 | grep "Tests run:" | tail -1

# 前端：必须拿到真实统计，禁止只跑 build 就声称通过
cd frontend && npx tsc --noEmit
cd frontend && npm run test:run 2>&1 | grep -E "Test Files|Tests " | tail -5   # 必须 0 failed
cd frontend && npm run build

# Python
cd backend-python && python3 -m compileall -q src/nocobase_py && echo COMPILE_OK
# 若 pytest 可用则跑：python3 -m pytest -q
```

> ⚠️ **关键教训**：`vitest` 必须用 `npm run test:run`（`package.json` 的 `test` 是 `vitest`，会进 watch 模式导致超时拿不到结果）。**必须看到 "Test Files / Tests" 统计行并确认 0 failed**，否则视为未验证。

### 1.3 提交规范
按栈分提交：`[java]` / `[python]` / `[js]` / `[docs]`。

---

## 2. 返工任务

### F1（P0，门禁阻塞）修复前端 7 个测试失败

**现状证据**：
1. `frontend/src/api/client.ts:106` —— `if (error.response?.status === 401 && !originalRequest._retry)`。测试 `client.test.ts:100` 构造的 error 是 `{ response: { status: 401 } }`（**无 `config`**），导致 `originalRequest` 为 `undefined`，读 `_retry` 抛 TypeError。基线版本同样无可选链防御。
2. `frontend/src/components/AppLayout.tsx:6,105` —— `import { GlobalSearchPanel }` 并在 L105 渲染 `<GlobalSearchPanel />`；而 `GlobalSearchPanel.tsx:26,88` 用了 `useQuery`。`AppLayout.test.tsx` 只包了 `MemoryRouter`，没有 `QueryClientProvider` → 4 个用例全崩。
3. `frontend/src/features/im/ChannelList.tsx:167` —— JSX 为 `🧑 当前: {currentUser.username}`，文本被拆成 `"🧑 当前: "` 与 `"alice"` 两个节点；`ChannelList.test.tsx:53` 用 `getByText(/当前:alice/)` 正则匹配单元素文本失败。

**实现要点**：
1. `client.ts:106` 加可选链防御：`error.response?.status === 401 && !originalRequest?._retry`，并在 `originalRequest` 为 falsy 时直接 `return Promise.reject(error)`（透传原始 error，满足 `rejects.toBe(err)`）。同时 `originalRequest._retry = true` 与 `originalRequest.headers` 访问需同步防御。
2. `AppLayout.test.tsx`：用 `QueryClientProvider` 包裹 `AppLayout`（新建 `QueryClient` 并设 `retry: false`），4 个用例统一处理。
3. `ChannelList.tsx:167`：把文本合并为单一表达式节点，如 `{`🧑 当前: ${currentUser.username}`}`（模板字符串），使正则可匹配；**或**改测试用 function matcher。推荐改组件（让真实 UI 文本更规范）。
4. 修完后**必须**看到 vitest `Test Files` / `Tests` 统计且 **0 failed**。

**验收**：
```bash
cd frontend && npm run test:run 2>&1 | grep -E "Test Files|Tests " | tail -5
# 期望：无 failed，总数 ≥233
```

---

### F2（P0）R4 暗色主题真做 —— 49 个文件硬编码 → CSS 变量

**现状证据**（实测统计，口径与初次审计一致）：
- 仅 hex：`grep -rl "#[0-9a-fA-F]\{3,6\}" src --include='*.tsx' | wc -l` → **49 个文件**
- hex+rgba：`grep -rlE "#[0-9a-fA-F]{3,6}|rgba?\(" src --include='*.tsx' | wc -l` → **61 个文件**

重灾区（实测列表）：`Login.tsx` `ViewsList.tsx` `CollectionDetail.tsx` `FormRuntime.tsx` `WikiPageRead.tsx` `WikiVersionHistory.tsx` `WikiSearch.tsx` `MessagesInbox.tsx` `CollectionsList.tsx` `NotificationChannels.tsx` `RowAclAdmin.tsx` `ErDiagram.tsx` `AuditLogs.tsx` `WorkflowsList.tsx` `WorkflowDesigner.tsx` `RolesList.tsx` `ViewDesigner.tsx` `FormDesigner.tsx` `Profile.tsx` `WeComLoginPage.tsx` `DingTalkLoginPage.tsx` `UsersList.tsx` `Workbench.tsx` `TableView.tsx` `AgentChatPage.tsx` `FormsList.tsx` `SchemaDesigner.tsx` `AclEditor.tsx` `CalendarView.tsx` `MyTasks.tsx` `AlertCenter.tsx` `WorkflowInstances.tsx` `SchemaEditor.tsx` `WikiDiffViewer.tsx` `NotionStyleEditor.tsx` `VersionDiff.tsx` `WikiAttachmentUpload.tsx` `AppLayout.tsx` `forms/FormRuntime.tsx` `forms/FieldRulesEditor.tsx` `views/FilterBar.tsx` `project/TaskBoard.tsx` `project/BoardColumn.tsx` `project/GanttView.tsx` `collection/TimelineView.tsx` `realtime/CollabEditor.tsx` `im/HuddlePanel.tsx` `bi/BiReportPage.tsx`

**实现要点**：
1. 先跑精确盘点：`cd frontend && grep -rc "#[0-9a-fA-F]\{3,6\}" src --include='*.tsx' | sort -t: -k2 -rn | head -30`，按数量从高到低改。
2. 替换规则（**遵守红线 #3**）：
   - 亮背景 `#f1f5f9`/`#f8fafc`/`#f5f5f5` → `var(--color-bg-secondary)` 或 `var(--glass-bg-light)`
   - 边框 `#e2e8f0`/`#e5e7eb`/`#d1d5db` → `var(--color-border-light)` / `var(--color-border-medium)`
   - 背景 `#f3f4f6` → `var(--color-bg-tertiary)`
   - 语义色块 `#fee2e2`→`rgba(239,68,68,0.2)`、`#e0e7ff`→`rgba(99,102,241,0.2)`、`#eff6ff`→`rgba(59,130,246,0.1)`、`#ecfeff`→`rgba(8,145,178,0.1)`、`#fef9c3`→`rgba(245,158,11,0.1)`
   - `#fff` 作为前景文字 → `var(--color-text-primary)`；作为背景 → `var(--glass-bg-light)`
   - 缺失的 token 先补进 `src/styles.css` 的 `:root`，再引用
3. **必须包含 F7 的 `TimelineView.tsx`**（本轮新增，自带 `PALETTE` hex 数组）。
4. 目标：hex 文件数从 **49 → ≤5**（剩余需列清单+原因，不许声称"全覆盖"）。
5. 全程保持 `tsc` 0 errors、`vitest` 0 failed。

**验收**：
```bash
cd frontend && grep -rl "#[0-9a-fA-F]\{3,6\}" src --include='*.tsx' | wc -l   # 期望 ≤5
cd frontend && npx tsc --noEmit && npm run test:run 2>&1 | grep -E "Tests " | tail -2
```

---

### F3（P0，红线 #1）R5 Slack 入站接真 —— 去掉 log.info + TODO

**现状证据**：`backend-python/src/nocobase_py/routers/integration.py:137-143`
```python
    # 入站事件落地：幂等去重 + 转发到 Java
    event_id = body_json.get("event_id") if isinstance(body_json, dict) else None
    if event_id:
        logger.info("[Slack] 入站事件已转发: %s", body_json.get("event", {}).get("type", "unknown"))
        # TODO: 实际部署时，通过 httpx 将事件 POST 到 Java /api/slack/events 入库
        # 此处仅做日志记录，防止事件被静默丢弃
    return {"code": 0, "data": {"valid": True}}
```
→ `logger.info` + `# TODO` 冒充接真，**明确违反红线 #1**；返回 200 会让 Slack 认为投递成功，实际事件被丢弃。

**实现要点**：
1. 删除 `logger.info` 与 `# TODO` 占位注释。
2. 用 `httpx.AsyncClient` **真实转发**到 Java：
   - 目标：`POST {settings.java_backend_url}/api/im/messages`（先确认 Java 侧真实端点与鉴权；若需服务间令牌，用现有 API Key 机制，**不要新造一套**）
   - 携带 Slack 事件内容（channel / user / text / ts）
   - 超时 10s
3. **幂等去重**：用 `event_id` 做去重（单机用带 TTL 的集合；有 Redis 则用 Redis SETNX），重复事件直接返回 200。
4. **失败语义**：转发失败必须 `raise HTTPException(status_code=500)`，让 Slack 重试；**禁止吞异常返回 200**（红线 #1：假成功）。
5. 只处理关心的事件类型（`message` / `app_mention`），其余忽略并返回 200。
6. 补 `tests/` 用例：验签失败→401；challenge→回显；入站事件→转发被调用（mock httpx）；转发失败→返回 5xx。

**验收**：
```bash
cd backend-python && python3 -m compileall -q src/nocobase_py && echo COMPILE_OK
# grep 确认无 TODO 冒充：
grep -rn "TODO\|仅做日志记录" src/nocobase_py/routers/integration.py   # 期望 0 命中
```

---

### F4（P1）R5 钉钉 token API 更新

**现状证据**：`backend-python/src/nocobase_py/services/connectors/dingtalk.py:34-37`
```python
            resp = await client.get(
                f"{self.base_url}/gettoken",
                params={"appkey": self.app_key, "appsecret": self.app_secret},
            )
```
→ 旧版 `GET /gettoken` 已下线。钉钉新版为 `POST /v1.0/oauth2/accessToken`，请求体 `{"appKey","appSecret"}`，响应取 `accessToken`。

**实现要点**：
1. 改为 `POST {self.base_url}/v1.0/oauth2/accessToken`，`json={"appKey": self.app_key, "appSecret": self.app_secret}`。
2. 响应字段改为 `data.get("accessToken")`（旧版是 `access_token`），失败时 `raise RuntimeError(f"...{data}")` —— **禁止返回假成功**。
3. 确认 `self.base_url` 是否需改为 `https://api.dingtalk.com`（旧版 `oapi.dingtalk.com`），如需则同步调整。
4. 调用方（`send_message` 等）若依赖旧字段名需同步适配。

**验收**：`python3 -m compileall` 通过；`grep -rn "gettoken" src/nocobase_py/services/connectors/dingtalk.py` 期望 0 命中。

---

### F5（P1）R7 Workbench 真聚合视图

**现状证据**：`frontend/src/pages/Workbench.tsx:31-38` —— 仍是 6 个静态卡片网格，5 个 `count` 恒为 `0`，只有 `collections.length` 真实；**没有**待办/未读/最近文档/我的任务区块。

**实现要点**：
1. 升级为聚合仪表盘，4 个区块用 `react-query` 拉**真实接口**（调用前确认端点存在，**禁止臆造**）：
   - 我的待办 → 项目任务接口（先确认真实路径，如 `/api/projects/...` 或 `/api/tasks/...`）
   - IM 未读 → IM 未读接口（先确认）
   - 最近文档 → Wiki 页面接口（先确认；可用 `wikiPageService.listByKbAndStatus` 对应端点）
   - 我的任务 → 任务接口按 assignee 过滤
2. 每区块有 **loading / empty / error 三态**，错误态显示明确文案（禁止静默空白）。
3. 保留原有的 6 个模块入口卡片（作为快捷入口），但**聚合数据区块是主体**。
4. 样式全部走 CSS 变量 + `sx`（红线 #3，且纳入 F2 统计）。

**验收**：`tsc` 0 errors、`vitest` 0 failed、`build` 成功；审计会 grep Workbench 是否真的调用了 ≥3 个真实接口。

---

### F6（P1）R8 前端端点契约测试（防前后端断链）

**现状证据**：`frontend/src/api/` 下**不存在**任何 `endpoints.contract` 测试文件（`search_file *contract*` → 0 命中）。第一轮声称"已存在（Phase51）"为虚假。

**实现要点**：
1. 新建 `frontend/src/api/endpoints.ts`（若不存在）：把前端 `api/*.ts` 里出现的路径**集中导出为常量表**（如 `SEARCH: '/search'`、`AI_CHAT: '/ai/chat'`、`AI_QUOTA: '/ai/quota'`、`LIVECHAT_SESSION: '/livechat/session'` 等）。
2. 新建 `frontend/src/api/endpoints.contract.test.ts`：
   - 断言常量表路径与后端端点清单一致（快照文件提交进仓库）
   - 重点覆盖第一轮发现的断链点：`/ai/chat`、`/ai/quota`、`/search`、`/livechat/*`、`/wecom/*`
   - 路径漂移即测试失败
3. 让 `api/*.ts` 引用这些常量（至少 `search.ts` 与 agent 相关），避免再次漂移。

**验收**：`npm run test:run` 新增用例通过且总数增加；`tsc` 0 errors。

---

### F7（P1，红线 #3）TimelineView.tsx 硬编码修正

**现状证据**：`frontend/src/features/collection/TimelineView.tsx`
- 顶部 `const PALETTE = ['#6366f1', '#8b5cf6', '#ec4899', '#f59e0b', '#10b981', '#3b82f6', '#14b8a6'];`（hex 数组）
- `Chip` 用 `bgcolor: `${color}22``、`` `1px solid ${color}44` `` 等内联 hex 拼接

**实现要点**：
1. `PALETTE` 改为 CSS 变量数组：`['var(--color-primary-500)', 'var(--color-secondary-500)', ...]`（缺的 token 先补 `styles.css`）。
2. 透明度叠加改用 `color-mix()`（现代浏览器）或预定义 `--color-*-alpha` 变量；**禁止** `${color}22` 这类 hex 拼接（对 CSS 变量无效）。
3. 跟随 F2 一并统计，确保该文件不再出现在 hex 清单中。

**验收**：`grep -n "#[0-9a-fA-F]\{3,6\}" src/features/collection/TimelineView.tsx` 期望 0 命中。

---

## 3. 执行顺序

```
F1（门禁阻塞，先修 7 个测试失败）
  ↓
F3（红线#1 Slack 入站接真）→ F4（钉钉 token API）→ F7（TimelineView 硬编码）
  ↓
F2（暗色主题 49 文件批量替换，含 F7 结果）
  ↓
F5（Workbench 聚合）→ F6（前端契约测试）
  ↓
全栈门禁复跑 → 分栈提交 → 交回 CodeBuddy 复审计
```

F3/F4/F7 互不依赖，可连续做；F2 体量最大，建议放最后统一替换（避免与 F7 冲突）。

**每完成一项立即跑对应门禁；失败就地修，不许跳过。**

---

## 4. 附录 A — 已确认的真实契约（改动前仍须复核）

| 符号/端点 | 真实形态 |
|---|---|
| Java AI 端点 | `/api/ai/{wiki,ask,code,status}` + `/api/ai/chat`(新) + `/api/ai/quota`(新) + `/api/ai/agent/execute` |
| Java Livechat | `TicketController` `@RequestMapping("/api/livechat")` → `/session` `/message` `/close` `/tickets` |
| Java 企微 | `WeComController` `@RequestMapping("/api/wecom")` → `/auth-url` `/callback` `/sync-contacts` `/message/send` |
| Java 视图类型 | `ViewEntity.Type` = `TABLE, KANBAN, DETAIL, GALLERY, CALENDAR, TIMELINE` |
| Python Slack | `integration.py` `/api/integration/slack/verify`（先验签→再处理 challenge→再入站转发） |
| Python 配置 | `config.py` 已有 `java_backend_url`、各连接器字段；本轮新增 `mattermost_webhook_url` / `mattermost_bot_token` |
| 前端测试命令 | **`npm run test:run`**（`test` 是 watch 模式，会超时拿不到结果） |
| vite proxy | `/api`→8080、`/api/ai`→8000、`/ws`(ws:true)→8080 |
| 迁移 | 最新 V35=`V35__livechat_ticket.sql`；**下一个用 V36** |

## 5. 附录 B — 审计方法论（本轮教训，长期遵守）

1. **`tsc`/`build` 通过 ≠ 测试通过**。前端必须用 `npm run test:run` 并看到 `Test Files` / `Tests` 统计行，确认 **0 failed**。
2. **禁止用"声称"代替"验证"**。每项完成都要有可复现的命令输出作为证据。
3. **接真类任务**必须含运行时可达性证据（端点契约测试 + 端到端数据流断言），否则必然出现"编译绿、运行空"。
4. 复审计时 CodeBuddy 会用**实测统计**（grep 计数、真实跑测试）核对，不再接受静态推断的结论。
