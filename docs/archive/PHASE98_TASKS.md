# PHASE98 任务书：错误态与好用

> 索引定义（`PHASE95_103_INDEX.md:39`）：
> `| **PHASE98** | 错误态与好用 | — | 主列表/看板/告警页补 error 态与空态引导、清除硬编码 admin 身份与假用户兜底、Livechat 假回复、移动端横幅与横向滚动、图标按钮 aria-label |`
>
> 前置批次：PHASE95（租户隔离）✅ / PHASE96（假实现清零）✅ / PHASE97（前后端契约）✅
> 本批适用的红线：**红线 1（禁止恒真断言）、红线 2（禁止静默成功）、红线 3（数字可追溯）、红线 4（每批跑全量门禁）、红线 7（安全类改动需真实 HTTP 证据）**。

---

## §1 现状盘点结论（2026-10-12 全仓重新盘点，附证据行号）

### 1.1 五个主题判定

| # | 主题 | 判定 | 最严重的单点 |
|---|---|---|---|
| 1 | 主列表/看板/告警页 error 态与空态 | **部分实现** | `BoardView.tsx:50-64` catch 后只 `console.error`，静默渲染空看板；`KanbanView.tsx:26-43` 与 `AlertCenter.tsx:265-287` 错误时**永久停在"加载中…"** |
| 2 | 硬编码 admin 身份与假用户兜底 | **部分实现** | `api/client.ts:75-81` token 刷新成功但取不到 user 时，**凭空造一个 `{id:'unknown', tenant_id:'default', roles:[]}` 假用户写进全局 store** |
| 3 | Livechat 假回复 | **假实现** | `LivechatWidget.tsx:95-118` `setTimeout` 1 秒后必然插入一条 `sender:'agent'` 的"客服回复"，后端**根本没有 agent→customer 回推能力** |
| 4 | 移动端横幅与横向滚动 | **部分实现** | `DesktopWarning.tsx` `position:fixed; top:0; zIndex:9999` 且**无法关闭**（`Screen.tsx:9` 不传 `onDismiss`）+ **无任何 padding 补偿**，永久遮挡首屏 |
| 5 | 图标按钮 aria-label | **缺失** | 约 30 个 icon-only 按钮，仅 4 个有显式 `aria-label`（**87% 缺失**）；`GlobalSearchPanel.tsx:158` 是裸 SVG，连 `<button>` 都不是 |

### 1.2 现有基础设施：缺失（必须先建，否则 30 个页面各写一遍）

- `EmptyState` / `ErrorState` / `ErrorBoundary` / `react-error-boundary` 全仓搜索 **0 命中**
- 唯一近似物是 `pages/Home.tsx:56` 的私有内联 `Empty` 组件（不可复用、无 CTA）
- 无 React ErrorBoundary → 任一 render 抛错即整页白屏
- 现有兜底是每页各写一遍 `if (error) return <p>加载失败</p>`，**无重试、无原因、无 traceId**

### 1.3 必须点名的两处"陷阱"（改之前先看，否则红灯）

**陷阱 A — 缺陷已被测试固化成契约**
`frontend/src/pages/AuditLogs.test.tsx:87`：
```tsx
it('加载失败:console.error + 仍显示无数据', async () => {
  const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
```
这条测试**锁死了"错误时静默显示无数据"**。改 error 态必须先改这条（改写为断言错误态可见），不得删除。

**陷阱 B — 响应式红线有"恒真断言掩盖"前科**
`frontend/src/styles.css:605` 注释原文：
```
（PHASE90 收尾：MyTasks 的表格此前直接撑破页面，被一个恒真断言掩盖。）
```
新增响应式/横滚断言**必须做可失败性验证**（故意撑宽页面应红）。

### 1.4 附带发现（不在索引五项内，但同一文件，建议一并处理）

`frontend/src/features/im/LivechatWidget.tsx` 全篇使用 Tailwind 原子类（`fixed bottom-4 right-4 z-50`、`w-96 h-[600px]`、`flex-1 overflow-y-auto`），但 `frontend/package.json`（77 行）**未见 `tailwindcss` / `postcss` / `autoprefixer`** → 这些布局类名在运行时大概率完全不生效（固定定位、宽高、滚动全部失效）。做 R3 时一并改为内联 style 或 MUI `sx`。

---

## §2 任务

### R1（P0）删除 Livechat 假回复，改为显式状态

**文件**：`frontend/src/features/im/LivechatWidget.tsx`

**现状**（`:95-118`）：
```tsx
await apiClient.post('/livechat/message', { sessionId, message: inputText.trim() });
// 模拟客服回复（实际应由后端推送）
setTimeout(() => {
  setMessages((prev) => [...prev, {
    id: Date.now().toString(),
    text: '已收到您的消息，客服将尽快回复。',
    sender: 'agent',
    timestamp: new Date(),
  }]);
}, 1000);
```
后端 `TicketController.java`（`@RequestMapping("/api/livechat")`）只有 `POST /session`、`POST /message`、`POST /close`、`GET /tickets`、`POST /tickets/{id}/notes`，**没有任何 agent→customer 回推接口**——那句"客服回复" 100% 是前端伪造。客户发 N 条就收到 N 条一模一样的"客服"回复。

**要求**：
1. **删掉** `:101-112` 的 `setTimeout` 假回复。
2. 改为真实投递状态：消息发出后显示"已送达"；若后端回推能力尚未具备，**必须把兜底文案标注为机器人自动应答**（`sender:'bot'` + "这是自动回复，人工客服将在工作时间内处理"），**绝不允许以 `sender:'agent'` 冒充人工**。
3. 两处静默 catch（`:70-72` 建会话失败、`:136-138` 结束会话失败）改为**渲染错误提示 + 重试按钮**。
4. 一并把 Tailwind 类名改为内联 style 或 MUI `sx`（见 §1.4）。
5. 硬编码欢迎语"工作时间：周一至周五 9:00-18:00"（`:226`）是纯装饰文案，与任何坐席排班无关 → 改为中性文案或删除，不得暗示有排班。

**验收**：新增 `LivechatWidget.test.tsx`，断言**发送后不会自动出现 `sender:'agent'` 的消息**（此断言在改回 setTimeout 时必须变红）。

---

### R2（P0）删除假用户兜底

**文件**：`frontend/src/api/client.ts`

**现状**（`:61-81`）：token 刷新成功但 `localStorage` 取不到用户信息时：
```tsx
useAuthStore.getState().setAuth(access_token, refresh_token, {
  id: 'unknown', username: 'unknown', tenant_id: 'default', roles: []
});
```
后果（三项全中）：
- UI 显示已登录、用户名 `unknown`；
- `tenant_id: 'default'` **不是合法的 `tenant_default`**，后续所有带租户的请求指向不存在的租户 → 可能绕过 PHASE95 刚加固的租户隔离；
- `roles: []` → 权限判断静默为"无权限"，页面大面积空白且无提示。

**要求**：
1. **删除**该假用户对象。
2. 取不到 user 时应**强制登出并跳登录页**（`useAuthStore.getState().clear()` + 重定向），而不是造人。
3. 补测试：断言 refresh 成功但无 user 时**不会**写入 `id==='unknown'` 的假用户，且触发登出。此断言在恢复假用户兜底时必须变红。

**同批（R2b，P1）**：`frontend/src/pages/AlertCenter.tsx:186`
```tsx
const [ackBy, setAckBy] = useState('admin');
```
告警确认人（审计留痕字段 `acked_by`）默认写死 `"admin"` → 任何未手动修改的 ack 都以 admin 身份记入审计，合规审计（SOC2/等保）直接被打回。
**要求**：默认值改为从 `useAuthStore().user?.username` 取；取不到时禁用 ack/resolve 并提示登录态失效。

---

### R3（P0）看板与告警页补真 error 态

**先建基础设施**（否则 30 个页面各写一遍）：
1. `frontend/src/components/ErrorState.tsx`：`message` + `onRetry` + `traceId` 展示
2. `frontend/src/components/EmptyState.tsx`：`title` / `description` / `action` 插槽（**带 CTA 按钮**）
3. 顶层挂 React ErrorBoundary（`router.tsx` 或 `AppLayout`），兜底未知异常而非白屏

**再逐页修**（按严重度排序，全部要求改）：

| 页面 | 现状 | 要求 |
|---|---|---|
| `features/project/BoardView.tsx:50-64` | `catch { console.error }` → 静默空看板 | 加 `error` state + `<ErrorState onRetry={loadData} />` |
| `pages/KanbanView.tsx:26-43` | 三个 query 都不取 `error`，404/500 时**永久"加载中…"** | 取 `isError`，三态（loading/error/data） |
| `pages/AlertCenter.tsx:265-287` | 4 个 query 全部无 error 态，webhook 挂掉时卡片永远"加载中…" | 四个 query 全部加 `isError`；**告警页自身无 error 态是最讽刺的一点，必须修** |
| `pages/UsersList.tsx:18,49-50` | 无 `isError` 无空态 → 500 时渲染空表格，**与"真的没用户"无法区分** | 补 `isError` + 空态 |
| `pages/RolesList.tsx:17,41-42` | 同上（还多了 `if (isLoading) return ...;;` 双分号） | 同上，顺手修双分号 |
| `pages/wiki/WikiPageList.tsx:117-122` | 任何错误都硬编码成「无权限访问 / 拒绝访问（403）」 | 改为真实 `error.message` / HTTP status（网络超时显示 403 会让用户去申请权限而不是重试） |
| `pages/CollectionsList.tsx:18-19`、`FormsList.tsx:22-23` | `if (error) return <p>加载失败</p>`（无重试/无原因） | 改用 `<ErrorState onRetry={refetch} />` |

**空态 CTA（P2，一并把最明显的几个补上）**：现全站空态都是纯文字且让用户"点上方"（不可点击）。至少给以下页面换成 `<EmptyState>` 且按钮直连新建路由：
`CollectionsList.tsx:41-53`、`FormsList.tsx:58`、`ViewsList.tsx:58`、`WorkflowsList.tsx:35`、`PlaybookList.tsx:115`、`MessagesInbox.tsx:80`、`MyTasks.tsx:68`。

**已有正面样板（对齐即可，不要另起炉灶）**：
`features/project/GanttView.tsx:65-66`、`features/project/TaskBoard.tsx:235-236`、`features/realtime/CollabPage.tsx:39-40`、`pages/IntegrationsPage.tsx:86-87`、`pages/wiki/WikiPageRead.tsx:50-51`。

**必须先处理陷阱 A**：改 error 态会与 `pages/AuditLogs.test.tsx:87` 冲突（该用例断言"加载失败时仍显示无数据"），**改写该用例而非删除**。

---

### R4（P1）图标按钮 aria-label 补全

**现状**：约 30 个 icon-only 按钮，代码内显式 `aria-label` 仅 **4 处**：
- `components/AppLayout.tsx:204`
- `components/wiki/AiAssistantPanel.tsx:143`
- `pages/wiki/WikiCategoryManagement.tsx:138`、`:141`

**要求**：
1. 建 `components/IconButtonA11y.tsx`，**TS 类型层面把 `aria-label` 设为必填 prop**（`aria-label: string`），从源头堵住新增。
2. 批量补以下 **20 条**（未包 Tooltip 的确定性缺陷），全部走 `t()` i18n：

| # | 位置 | 控件 |
|---|---|---|
| 1-2 | `features/im/ImLayout.tsx:216-218`、`:247-249` | 打开/关闭频道抽屉 |
| 3-5 | `features/im/HuddlePanel.tsx:371-381`、`:383-393`、`:395-400` | 静音 / 关闭摄像头 / 挂断 |
| 6 | `features/project/BoardColumn.tsx:152-154` | 更多操作 |
| 7-8 | `components/wiki/NotionStyleEditor.tsx:522-531`、`:533-537` | 拖拽手柄 / checkbox |
| 9 | `pages/agent/AgentChatPage.tsx:166-171` | 发送 |
| 10-14 | `pages/wiki/WikiPageList.tsx:194`、`:195`、`:197`、`:199`、`:201` | 编辑/版本/发布/归档/删除（**移动端卡片视图**） |
| 15-16 | `components/GlobalSearchPanel.tsx:139-153`、`:158` | 打开搜索 / 关闭 |
| 17-20 | `features/im/LivechatWidget.tsx:155-166`、`:195-204`、`:205-210`、`:282-289` | 打开/最小化/关闭/发送 |

3. **次一档 13 条**（被 MUI `<Tooltip title>` 包裹，运行时靠 MUI 注入 `aria-label`，静态不可见）：同样补显式 `aria-label` 并把 `title` 改走 `t()`。位置：`pages/wiki/WikiPageList.tsx:239/244/250/256/262`、`pages/wiki/KnowledgeBaseList.tsx:124/129-134`、`components/wiki/NotionStyleEditor.tsx:592/757/762/767/773/778`。
4. **`GlobalSearchPanel.tsx:158` 专项**：`<CloseIcon onClick>` 是裸 SVG，无 `role`、无 `tabIndex`、无法键盘触发（WCAG 2.1.1 失败）→ 改成 `<IconButton aria-label={t('common.close')} onClick>`。
5. **`WikiPageList.tsx:194-201` 专项**：卡片视图的删除按钮是**破坏性操作且无确认弹窗**，补 aria-label 同时加确认。
6. **`NotionStyleEditor.tsx:522-531` 专项**：drag-handle 的 `onMouseDown` 是空实现（"看起来能拖、实际拖不动"的假交互）→ 要么实现拖拽，要么标 `aria-hidden` + `tabIndex={-1}`。

**门禁**：建议引入 `eslint-plugin-jsx-a11y`（当前 `package.json` devDependencies 无此依赖）作为静态门禁；若本批不引入，须在交付里写明理由。

---

### R5（P1）移动端横幅遮挡修复

**文件**：`frontend/src/components/DesktopWarning.tsx`、`frontend/src/components/Screen.tsx`

**三个具体问题**：
1. `Screen.tsx:9` 调用 `<DesktopWarning />` **不传 `onDismiss`** → `DesktopWarning.tsx:18` 的 `{onDismiss && (<button>知道了</button>)}` 不渲染 → **移动端该横幅无法关闭**；
2. `position:'fixed', top:0, zIndex:9999` 且**没有任何页面/布局加 `padding-top` 补偿** → 横幅**永久遮挡页面顶部内容**（在 `SchemaDesigner.tsx:137-138` 会盖住 `<h1>📐 Schema Designer</h1>`）；
3. 外层 flex 无 `flexWrap`，375px 视口下长中文文案换行后横幅高度可达 ~90px，遮挡更严重。

**要求**：
1. 改为 `position:'sticky'`，或让 `Screen` 在 `isMobile` 时给容器加 `paddingTop` 补偿；
2. 补 `flexWrap:'wrap'`；
3. `Screen.tsx:9` 传入 `onDismiss`，并把"已忽略"持久化到 localStorage；
4. 新增 Playwright 断言：375px 视口访问 `SchemaDesigner`，断言页面首个 `<h1>` **未被横幅遮挡**（`boundingBox().y > 横幅底部`）。此断言在移除 padding 补偿时必须变红。

---

### R6（P2）横向滚动回归防护

**现状**：基本可控——`styles.css:604-618` 已有 `.table-scroll { overflow-x:auto; max-width:100% }` 且带 PHASE90 红线注释；`MyTasks.tsx:82`、`BoardView.tsx:138`、`TableView.tsx:323` 等已容器化。

**存疑点**：`pages/KanbanView.tsx:68-78` 移动端主动 `overflowX:'visible'` 关掉滚动兜底，而 `styles.css:481-484` 又把 `.kanban-view-container { grid-auto-columns: 260px }` 钉死 → 若 CSS 优先级使 260px 生效，260px×N 列在 375px 视口下会撑破页面。**需实机验证**（盘点标注"疑似，未实测"）。

**要求**：
1. 实测 KanbanView 在 375px 下是否页面级横滚；若是，移动端统一 `overflowX:'auto'` 且 `.kanban-view-container` 在 mobile 下 `grid-auto-columns: 1fr`；
2. 给 `styles.css:604` 那条红线补一个 Playwright 断言：遍历主要路由 × 375px 视口，断言 `document.documentElement.scrollWidth <= window.innerWidth`；
3. **必须做可失败性验证**（陷阱 B：该红线曾有恒真断言掩盖前科）——故意撑宽页面，断言必须变红；
4. 补 `styles.css:470-472` 的 table `min-width`，确保 `white-space:nowrap` 时不溢出容器外。

---

## §3 红线（本批特别适用）

1. **红线 1（禁止恒真断言）**：R1/R2/R3/R5/R6 新增的每条断言都必须在交付里给出**可失败性验证**（改回旧实现 → 断言变红 → 贴输出 → 改回 → 复跑通过）。R6 尤其重要（有 PHASE90 前科）。
2. **红线 2（禁止静默成功）**：R1 的两处 `catch { 静默处理 }`、R3 的 `catch { console.error }` 都必须改为**用户可见的失败态**。
3. **红线 3（数字可追溯）**：门禁数字必须贴真实输出，不得写"已验证可用"。
4. **红线 4（每批跑全量门禁）**：十项全跑，逐条报数。
5. **红线 7（安全类需 HTTP 证据）**：R2（假用户兜底涉及租户隔离绕过）若改动后端，需真实 HTTP 证据；纯前端改动则需在交付里说明为什么不需要。
6. **不得删除有效测试**：`pages/AuditLogs.test.tsx:87` 必须**改写**而非删除。

---

## §4 交付清单

1. 逐项 R1–R6 的改动文件列表
2. **每条新增断言的可失败性验证输出**（改回旧实现 → 红 → 恢复 → 绿）
3. 十项门禁实测数字（mvn test / 前端 test:run / tsc / playwright / pytest / backup / admin-authz / tenant-isolation / smoke / no-fake-impl）
4. `pages/AuditLogs.test.tsx` 的改写说明
5. 移动端 375px 实测截图或 Playwright 断言输出（R5/R6）
6. 索引 `PHASE95_103_INDEX.md:39` 状态格更新（写实数字 + 遗留项）
7. commit hash

---

## §5 不要做什么

- **不要**为凑数把 `aria-label` 写成无意义占位（如 `aria-label="button"`），必须是有语义的可访问名。
- **不要**删除 `pages/AuditLogs.test.tsx:87` 那条测试（要改写）。
- **不要**在 R1 保留任何 `sender:'agent'` 的自动回复（那正是要清除的假实现）。
- **不要**改后端的 `TenantContext.DEFAULT_TENANT`（`tenant_default`）——那是 PHASE95 已裁决的迁移期兼容设计，本批只改前端的 `tenant_id:'default'`。
- **不要**为了跑通而放宽既有门禁基线。
- **不要**引入新依赖（除 R4 的 `eslint-plugin-jsx-a11y`，且需说明理由）。
