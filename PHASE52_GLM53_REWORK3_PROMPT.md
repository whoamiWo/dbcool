# Phase52 第三轮返工提示词（GLM-5.3 执行版 G1–G2）

> 编排方：CodeBuddy(HY4)　执行方：GLM-5.3　复审计方：CodeBuddy
> 生成时间：2026-09-22　基线提交：`e259c8d`
> 前置文档：`PHASE52_GLM53_PROMPT.md`（T1–T10）、`REWORK_PROMPT.md`（R1–R9）、`REWORK2_PROMPT.md`（F1–F7）

---

## 0. 背景：第二轮审计结论（条件通过）

第二轮返工（F1–F7 + T6–T9）交付后，CodeBuddy 做了**实测审计**，结论 **条件通过 10/11 项**。

### 已完成（勿回退）
| 任务 | 实测证据 |
|---|---|
| F1 前端 7 测试失败 | client.test.ts / AppLayout(4) / ChannelList 全部 ✓ |
| F2 暗色主题 | `grep -rl "#[0-9a-fA-F]{3,6}" src --include='*.tsx'` → **0 个文件** |
| F3 Slack 入站接真 | httpx 真实转发 Java + 幂等去重 + 失败返 5xx |
| F4 钉钉 token API | `POST /v1.0/oauth2/accessToken` |
| F5 Workbench 聚合 | 5 个 `useQuery` 接真实接口 |
| T6 企微接真 | `syncContacts()` 调 `/cgi-bin/department/list` + `/cgi-bin/user/simplelist` |
| T7 Livechat 转工单 | `message: '会话结束，自动转工单'` 已补 |
| T8 Slack 双向 | 先验签再返 challenge + 入站落地 |
| T9 飞书+Mattermost | `auth/v3` token + `json.dumps` + Webhook 适配器 |

### 门禁基线（本轮须保持不劣化）
- Java `mvn test`：**1072 PASS / 0 fail**
- 前端 `npm run test:run`：236 用例，**当前 1 失败**（见 G2）
- `tsc --noEmit`：**0 errors**
- `vite build`：**成功**
- Python `compileall`：**通过**

---

## 1. 全局约定

### 1.1 五条红线（重申）
1. **禁止 `log.info` + `// TODO` 冒充接真**；未配置外部服务返回明确错误（禁空 catch 吞异常）。
2. **禁止臆造 API**：调用任何符号前用 `[subagent:code-explorer]` / `[skill:lsp-code-analysis]` 确认真实存在与签名。
3. **前端禁硬编码亮色**：一律 `var(--color-*)` CSS 变量 + MUI `sx`。
4. **迁移版本号从 V35 起**；本轮不改 schema。
5. **不引入重型依赖**（pgvector 已决策不引入）。

### 1.2 门禁命令（每项完成后立即跑）
```bash
# 后端（须 ≥1072，只增不减）
cd backend-java && timeout 300 mvn test 2>&1 | grep -E "Tests run:|BUILD" | tail -3

# 前端测试 —— 必须用 verbose 并精确统计（× 会误匹配测试名中的 "× 按钮"）
cd frontend && timeout 300 npm run test:run -- --reporter=verbose 2>&1 | grep -v "at /home\|node_modules" > /tmp/v.log
grep -c '✓ src' /tmp/v.log          # 通过用例数
grep -cE '^\s*× ' /tmp/v.log        # 真实失败数（关键！须为 0）
cd frontend && npx tsc --noEmit     # 须 0 errors
cd frontend && timeout 300 npm run build

# Python
cd backend-python && python3 -m compileall -q src/nocobase_py && echo PY_COMPILE_OK
```

> ⚠️ **统计陷阱**：`grep -c '×'` 会把 FilterBar 测试名「删除筛选:**×** 按钮触发 onChange」计为失败，必须用 `grep -cE '^\s*× '`（行首匹配）才准确。

### 1.3 提交规范
按栈分提交：`[java]` / `[python]` / `[js]` / `[docs]`。

---

## 2. 返工任务

### G1（P1）F6 前端端点契约测试 —— 防前后端路径漂移

**现状证据**（实测）：
- `frontend/src/api/` 仅有 `client.ts`、`client.test.ts`、`dingtalk.ts`、`integrations.ts`、`search.ts`、`wiki.ts` —— **无 `endpoints.ts`，无 `endpoints.contract.test.ts`**
- `find frontend/src -name "*contract*" -o -name "*endpoints*"` → **0 命中**
- 全仓 `grep -rln "contract" frontend/src/` → **0 命中**
- 唯一存在的契约测试是 Java 侧 `backend-java/src/test/java/com/nocobase/ApiEndpointContractTest.java`（6 个 `@Test`，已 PASS）

**为什么必须做**：第二轮审计已两次出现「前端声称完成但文件不存在」的虚假声称（R8）。端点路径散落在各 `api/*.ts` 中，前后端路径一旦漂移（历史上 `/ai/chat`、`/ai/quota` 都曾 404），单测 mock 会掩盖断链。

**实现要点**：
1. 新建 `frontend/src/api/endpoints.ts`：把端点路径**集中导出为常量表**（按模块分组）。须覆盖以下实测路径：
   ```
   搜索：  SEARCH = '/search'
   AI：    AI_CHAT = '/ai/chat'、AI_QUOTA = '/ai/quota'、AI_STATUS = '/ai/status'
   Livechat：LIVECHAT_SESSION = '/api/livechat/session'、LIVECHAT_MESSAGE = '/api/livechat/message'、
             LIVECHAT_CLOSE = '/api/livechat/close'、LIVECHAT_TICKETS = '/api/livechat/tickets'
   Wiki：  WIKI_KB = '/api/wiki/kb'、WIKI_PAGES = '/api/wiki/pages'、WIKI_SEARCH = '/api/wiki/search'、
           WIKI_CATEGORIES = '/api/wiki/categories'、WIKI_BACKLINKS = (pageId) => `/api/wiki/pages/${pageId}/backlinks`
   企微：  WECOM_AUTH_URL = '/api/wecom/auth-url'、WECOM_CALLBACK = '/api/wecom/callback'、
           WECOM_SYNC_CONTACTS = '/api/wecom/sync-contacts'、WECOM_MESSAGE_SEND = '/api/wecom/message/send'
   IM：    IM_CHANNELS = '/im/channels'、IM_MESSAGES = '/im/messages'、IM_UNREAD = '/im/messages/unread'
   ```
2. 新建 `frontend/src/api/endpoints.contract.test.ts`：
   - 导出端点常量，断言与后端实际映射一致（快照文件可提交进仓库）
   - 至少覆盖第二轮审计发现的断链点：`/ai/chat`、`/ai/quota`、`/search`、`/api/livechat/*`、`/api/wecom/*`
   - 路径被改动时测试须失败
3. 让 `api/*.ts` 引用这些常量（至少 `search.ts` 与 `integrations.ts`），从源头防漂移。

**验收**：
```bash
cd frontend && grep -c . src/api/endpoints.ts src/api/endpoints.contract.test.ts   # 两个文件都存在
cd frontend && timeout 300 npm run test:run -- --reporter=verbose 2>&1 | grep -E "endpoints.contract"  # 用例通过
cd frontend && npx tsc --noEmit   # 0 errors
# 失败数须保持 0：grep -cE '^\s*× '
```

---

### G2（P1）修复 WikiPageEdit 既有测试失败（jsdom 相对 URL）

**现状证据**（实测）：
```
× src/pages/wiki/WikiPageEdit.test.tsx > WikiPageEditPage > 加载页面数据:回显标题和内容
  → expect(element).toBeInTheDocument() / element could not be found in the document
  stderr: 加载反向链接失败: TypeError: Failed to parse URL from /api/wiki/pages/p1/backlinks
```

**根因定位**：
- 失败点：`src/components/wiki/NotionStyleEditor.tsx:290` —— `await fetch(`/api/wiki/pages/${_page.id}/backlinks`)`
- jsdom 环境的 `fetch` **不支持相对 URL**（无 base 解析），抛 `TypeError: Failed to parse URL`
- **归属**：该组件由 Week45 提交 `fb0965c` 引入，**非 Phase52 返工引入**；本轮未改动此文件
- **性质**：既有遗留，但门禁失败须清零，故列入本轮

**实现要点**（二选一，推荐方案 A）：
- **方案 A（推荐）**：在 `frontend/src/test-setup.ts` 补 `fetch` 全局 stub，把相对 URL 补全为绝对 URL 再调用真实 fetch：
  ```ts
  import '@testing-library/jest-dom';
  // jsdom 的 fetch 不支持相对 URL，测试环境统一补全为绝对地址
  const _realFetch = globalThis.fetch;
  globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    if (typeof input === 'string' && input.startsWith('/')) {
      return _realFetch(`http://localhost${input}`, init);
    }
    return _realFetch(input as RequestInfo | URL, init);
  }) as typeof globalThis.fetch;
  ```
  注意：`test-setup.ts` 当前仅有 1 行 `import '@testing-library/jest-dom';`，追加时勿破坏原有导入。
- **方案 B**：在 `NotionStyleEditor.tsx:290` 把相对路径改为带 base 的绝对 URL。但会改动生产代码，**不推荐**（可能影响线上部署的域名适配）。

**验收**：
```bash
cd frontend && timeout 300 npm run test:run -- --reporter=verbose 2>&1 | grep -v "at /home\|node_modules" > /tmp/v2.log
grep -cE '^\s*× ' /tmp/v2.log   # 须为 0
grep -E "WikiPageEdit" /tmp/v2.log | grep -E "✓|×"
cd frontend && npx tsc --noEmit   # 0 errors
```

---

## 3. 执行顺序

```
G1（前端契约测试，独立）→ G2（WikiPageEdit 失败修复，独立）
  ↓
全栈门禁复跑（Java 1072 + 前端 0 失败 + tsc 0 + build 成功 + Python compile OK）
  ↓
分栈提交 → 交回 CodeBuddy 复审计
```

G1 与 G2 互不依赖，可连续做完再统一跑门禁。

---

## 4. 附录 A — 实测端点清单（建立常量表时核对）

| 模块 | 前端调用 | 后端映射 |
|---|---|---|
| 全局搜索 | `/search` | `UnifiedSearchController` `/api/search` |
| AI 对话 | `/ai/chat` | `AiController` `POST /api/ai/chat` |
| AI 配额 | `/ai/quota` | `AiController` `GET /api/ai/quota` |
| Livechat 会话 | `/api/livechat/session` | `TicketController` `POST /api/livechat/session` |
| Livechat 消息 | `/api/livechat/message` | `TicketController` `POST /api/livechat/message` |
| Livechat 关闭 | `/api/livechat/close` | `TicketController` `POST /api/livechat/close` |
| Livechat 工单 | `/api/livechat/tickets` | `TicketController` `GET /api/livechat/tickets` |
| Wiki 知识库 | `/api/wiki/kb` | Wiki kb 端点 |
| Wiki 页面 | `/api/wiki/pages` | Wiki pages 端点 |
| Wiki 反向链接 | `/api/wiki/pages/{id}/backlinks` | Wiki backlinks 端点（jsdom 相对 URL 失败点） |
| 企微授权 | `/api/wecom/auth-url` | `WeComController` `GET /api/wecom/auth-url` |
| 企微回调 | `/api/wecom/callback` | `WeComController` `POST /api/wecom/callback` |
| 企微同步 | `/api/wecom/sync-contacts` | `WeComController` `POST /api/wecom/sync-contacts` |
| 企微发消息 | `/api/wecom/message/send` | `WeComController` `POST /api/wecom/message/send` |
| IM 频道 | `/im/channels` | `ImChannelController` `/api/im/channels` |
| IM 消息 | `/im/messages` | `ImMessageController` `/api/im/messages` |

> `apiClient` 的 `baseURL = '/api'`，故 `/search` 实际请求 `/api/search`；部分模块（如 `integrations.ts`）写了完整 `/api/...` 前缀。建立常量表时须**保持各自原有写法**，勿统一改写（否则会改变实际请求路径）。

## 5. 附录 B — 审计方法论（复验要点）

CodeBuddy 复审计时会用以下实测命令核对，**不接受静态推断结论**：
1. 文件存在性：`ls` / `find` 实际确认（本轮 G1 即因文件不存在而暴露未完成）
2. 硬编码：`grep -rl "#[0-9a-fA-F]{3,6}" src --include='*.tsx' | grep -v ".test.tsx" | wc -l` 须为 0
3. 测试失败数：`grep -cE '^\s*× '`（行首匹配，避免误匹配测试名中的 `×`）
4. 红线#1：`grep -rn "TODO\|仅做日志记录"` 在 Python 路由/连接器须 0 命中
5. 每项完成须附**可复现的命令输出**作证据

### 本轮教训
- 「前端契约测试」类任务验收时，必须确认**文件真实存在**，不能仅凭 Java 侧通过就推断前端也完成
- 测试统计须区分「本轮引入」与「既有遗留」失败（G2 属既有遗留，但门禁失败须清零）
