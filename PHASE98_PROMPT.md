# PHASE98 提示词：错误态与好用

<!-- BEGIN PROMPT -->

你正在为 `/home/who/multistack-project`（DBCool，企业级协作平台，目标对外多租户 SaaS）执行 **PHASE98「错误态与好用」**。

**工作目录**：`/home/who/multistack-project`（不要去 `.kilo/` 目录，那是 worktree 副本）
**前置批次**：PHASE95（租户隔离）/ PHASE96（假实现清零）/ PHASE97（前后端契约）均已 ✅ 收口
**技术栈**：前端 React + Vite + **MUI v9** + @tanstack/react-query v5 + vitest + playwright（**无 Tailwind**）；后端 Spring Boot 3.2 + JDK 21

---

## 一、任务目标

索引 `PHASE95_103_INDEX.md:39` 原文定义：

```
| **PHASE98** | 错误态与好用 | — | 主列表/看板/告警页补 error 态与空态引导、清除硬编码 admin 身份与假用户兜底、Livechat 假回复、移动端横幅与横向滚动、图标按钮 aria-label |
```

五个主题，按优先级执行 **R1–R6**（R6 为 P2，可最后做）。

---

## 二、逐项任务

### R1（P0）删除 Livechat 假回复

文件：`frontend/src/features/im/LivechatWidget.tsx`

现状 `:95-118`：客户发消息后，`setTimeout` 1 秒必然插入一条 `sender:'agent'` 的"已收到您的消息，客服将尽快回复。"。后端 `TicketController.java`（`@RequestMapping("/api/livechat")`）只有 `POST /session`、`POST /message`、`POST /close`、`GET /tickets`、`POST /tickets/{id}/notes`，**没有任何 agent→customer 回推能力**——那句话 100% 是前端伪造。

要求：
1. **删掉** `:101-112` 的 `setTimeout` 假回复；
2. 改为真实投递状态（"已送达"）；若后端回推尚未具备，兜底文案**必须标为 `sender:'bot'` + "这是自动回复，人工客服将在工作时间内处理"**，**绝不允许以 `sender:'agent'` 冒充人工**；
3. 两处静默 catch（`:70-72` 建会话失败、`:136-138` 结束会话失败）改为**渲染错误提示 + 重试按钮**；
4. 硬编码的"工作时间：周一至周五 9:00-18:00"（`:226`）是纯装饰文案，与任何坐席排班无关 → 改为中性文案或删除；
5. **一并处理**：该文件全篇使用 Tailwind 原子类（`fixed bottom-4 right-4 z-50`、`w-96 h-[600px]` 等），但项目**未安装 Tailwind**（`frontend/package.json` 77 行内无 `tailwindcss`/`postcss`/`autoprefixer`）→ 改为内联 style 或 MUI `sx`。

验收：新增 `LivechatWidget.test.tsx`，断言**发送后不会自动出现 `sender:'agent'` 的消息**。

---

### R2（P0）删除假用户兜底

文件：`frontend/src/api/client.ts`

现状 `:61-81`：token 刷新成功但 `localStorage` 取不到用户信息时，凭空造一个假用户写进全局 store：
```tsx
useAuthStore.getState().setAuth(access_token, refresh_token, {
  id: 'unknown', username: 'unknown', tenant_id: 'default', roles: []
});
```

后果（三项全中）：
- UI 显示已登录、用户名 `unknown`；
- `tenant_id: 'default'` **不是合法的 `tenant_default`** → 后续带租户的请求指向不存在的租户，可能绕过 PHASE95 刚加固的租户隔离；
- `roles: []` → 权限判断静默为"无权限"，页面大面积空白且无提示。

要求：
1. **删除**该假用户对象；
2. 取不到 user 时**强制登出并跳登录页**（`useAuthStore.getState().clear()` + 重定向），而不是造人；
3. 补测试：断言 refresh 成功但无 user 时**不会**写入 `id==='unknown'` 的假用户，且触发登出。

**R2b（P1）**：`frontend/src/pages/AlertCenter.tsx:186` `const [ackBy, setAckBy] = useState('admin');` —— 告警确认人（审计留痕 `acked_by`）默认写死 `"admin"`，任何未手动修改的 ack 都以 admin 身份记入审计，合规审计（SOC2/等保）直接被打回。
要求：默认值改为从 `useAuthStore().user?.username` 取；取不到时禁用 ack/resolve 并提示登录态失效。

---

### R3（P0）看板与告警页补真 error 态

**先建基础设施**（全仓 `EmptyState`/`ErrorState`/`ErrorBoundary` 搜索 **0 命中**，无此则 30 个页面各写一遍）：
1. `frontend/src/components/ErrorState.tsx`：`message` + `onRetry` + `traceId` 展示
2. `frontend/src/components/EmptyState.tsx`：`title` / `description` / `action` 插槽（**带 CTA 按钮**）
3. 顶层挂 React ErrorBoundary（`router.tsx` 或 `AppLayout`），兜底未知异常而非白屏

**再逐页修**（全部要求改）：

| 页面 | 现状 | 要求 |
|---|---|---|
| `features/project/BoardView.tsx:50-64` | `catch { console.error }` → 静默空看板 | 加 `error` state + `<ErrorState onRetry={loadData} />` |
| `pages/KanbanView.tsx:26-43` | 三个 query 都不取 `error`，404/500 时**永久"加载中…"** | 取 `isError`，三态（loading/error/data） |
| `pages/AlertCenter.tsx:265-287` | 4 个 query 全部无 error 态，webhook 挂掉时卡片永远"加载中…" | 四个 query 全部加 `isError`（**告警页自身无 error 态是最讽刺的一点**） |
| `pages/UsersList.tsx:18,49-50` | 无 `isError` 无空态 → 500 时渲染空表格，与"真的没用户"无法区分 | 补 `isError` + 空态 |
| `pages/RolesList.tsx:17,41-42` | 同上（还多了 `if (isLoading) return ...;;` 双分号） | 同上，顺手修双分号 |
| `pages/wiki/WikiPageList.tsx:117-122` | 任何错误都硬编码成「无权限访问 / 拒绝访问（403）」 | 改为真实 `error.message` / HTTP status |
| `pages/CollectionsList.tsx:18-19`、`FormsList.tsx:22-23` | `if (error) return <p>加载失败</p>`（无重试/无原因） | 改用 `<ErrorState onRetry={refetch} />` |

**空态 CTA（P2）**：现全站空态都是纯文字且让用户"点上方"（不可点击）。至少给以下换成 `<EmptyState>` 且按钮直连新建路由：
`CollectionsList.tsx:41-53`、`FormsList.tsx:58`、`ViewsList.tsx:58`、`WorkflowsList.tsx:35`、`PlaybookList.tsx:115`、`MessagesInbox.tsx:80`、`MyTasks.tsx:68`。

**已有正面样板（对齐即可，不要另起炉灶）**：
`features/project/GanttView.tsx:65-66`、`features/project/TaskBoard.tsx:235-236`、`features/realtime/CollabPage.tsx:39-40`、`pages/IntegrationsPage.tsx:86-87`、`pages/wiki/WikiPageRead.tsx:50-51`。

⚠️ **陷阱 A（改之前必须先处理）**：`frontend/src/pages/AuditLogs.test.tsx:87` 的用例名是「加载失败:console.error + 仍显示无数据」，它**把"错误时静默显示无数据"固化成了契约**。改 error 态会与之冲突，**必须改写该用例而非删除**。

---

### R4（P1）图标按钮 aria-label 补全

现状：约 30 个 icon-only 按钮，代码内显式 `aria-label` 仅 **4 处**（`components/AppLayout.tsx:204`、`components/wiki/AiAssistantPanel.tsx:143`、`pages/wiki/WikiCategoryManagement.tsx:138`、`:141`），**87% 缺失**。

要求：
1. 建 `components/IconButtonA11y.tsx`，**TS 类型层面把 `aria-label` 设为必填 prop**（`aria-label: string`），从源头堵住新增；
2. 批量补以下 **20 条**，全部走 `t()` i18n：

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

3. **次一档 13 条**（被 MUI `<Tooltip title>` 包裹，运行时靠 MUI 注入 `aria-label`，静态不可见）：同样补显式 `aria-label` 并把 `title` 改走 `t()`。位置：`pages/wiki/WikiPageList.tsx:239/244/250/256/262`、`pages/wiki/KnowledgeBaseList.tsx:124/129-134`、`components/wiki/NotionStyleEditor.tsx:592/757/762/767/773/778`；
4. **`GlobalSearchPanel.tsx:158` 专项**：`<CloseIcon onClick>` 是裸 SVG，无 `role`、无 `tabIndex`、无法键盘触发（WCAG 2.1.1 失败）→ 改成 `<IconButton aria-label={t('common.close')} onClick>`；
5. **`WikiPageList.tsx:194-201` 专项**：卡片视图删除按钮是**破坏性操作且无确认弹窗**，补 aria-label 同时加确认；
6. **`NotionStyleEditor.tsx:522-531` 专项**：drag-handle 的 `onMouseDown` 是空实现（"看起来能拖、实际拖不动"的假交互）→ 要么实现拖拽，要么标 `aria-hidden` + `tabIndex={-1}`。

---

### R5（P1）移动端横幅遮挡修复

文件：`frontend/src/components/DesktopWarning.tsx`、`frontend/src/components/Screen.tsx`

三个具体问题：
1. `Screen.tsx:9` 调用 `<DesktopWarning />` **不传 `onDismiss`** → `DesktopWarning.tsx:18` 的 `{onDismiss && (<button>知道了</button>)}` 不渲染 → **移动端该横幅无法关闭**；
2. `position:'fixed', top:0, zIndex:9999` 且**无任何 padding 补偿** → 横幅**永久遮挡页面顶部内容**（在 `SchemaDesigner.tsx:137-138` 会盖住 `<h1>📐 Schema Designer</h1>`）；
3. 外层 flex 无 `flexWrap`，375px 视口下长中文文案换行后横幅高度可达 ~90px。

要求：
1. 改为 `position:'sticky'`，或让 `Screen` 在 `isMobile` 时给容器加 `paddingTop` 补偿；
2. 补 `flexWrap:'wrap'`；
3. `Screen.tsx:9` 传入 `onDismiss`，并把"已忽略"持久化到 localStorage；
4. 新增 Playwright 断言：375px 视口访问 `SchemaDesigner`，断言页面首个 `<h1>` **未被横幅遮挡**（`boundingBox().y > 横幅底部`）。

---

### R6（P2）横向滚动回归防护

现状基本可控：`styles.css:604-618` 已有 `.table-scroll { overflow-x:auto; max-width:100% }` 且带 PHASE90 红线注释；`MyTasks.tsx:82`、`BoardView.tsx:138`、`TableView.tsx:323` 等已容器化。

存疑点：`pages/KanbanView.tsx:68-78` 移动端主动 `overflowX:'visible'` 关掉滚动兜底，而 `styles.css:481-484` 又把 `.kanban-view-container { grid-auto-columns: 260px }` 钉死 → 若 CSS 优先级使 260px 生效，260px×N 列在 375px 视口下会撑破页面（**需实机验证**）。

要求：
1. 实测 KanbanView 在 375px 下是否页面级横滚；若是，移动端统一 `overflowX:'auto'` 且 `.kanban-view-container` 在 mobile 下 `grid-auto-columns: 1fr`；
2. 给 `styles.css:604` 红线补一个 Playwright 断言：遍历主要路由 × 375px 视口，断言 `document.documentElement.scrollWidth <= window.innerWidth`；
3. **必须做可失败性验证**（见下 §三.1）——故意撑宽页面，断言必须变红；
4. 补 `styles.css:470-472` 的 table `min-width`，确保 `white-space:nowrap` 时不溢出容器外。

---

## 三、红线（违反即打回）

1. **禁止恒真断言**：R1/R2/R3/R5/R6 新增的每条断言都必须在交付里给出**可失败性验证**——改回旧实现 → 断言变红 → 贴输出 → 改回 → 复跑通过。R6 尤其重要：`styles.css:605` 注释记录了 PHASE90 前科「MyTasks 表格撑破页面，被一个恒真断言掩盖」。
2. **禁止静默成功**：R1 两处 `catch { 静默处理 }`、R3 的 `catch { console.error }` 必须改为**用户可见的失败态**。
3. **数字可追溯**：门禁数字必须贴真实输出，不得写"已验证可用"。
4. **每批跑全量门禁**：十项全跑，逐条报数。
5. **不得删除有效测试**：`pages/AuditLogs.test.tsx:87` 必须**改写**而非删除。
6. **不得为凑数放宽基线**：任何门禁基线的数值不得下调。

---

## 四、门禁十项（全部要跑，逐条报数字）

```bash
cd backend-java && mvn -o test                              # 基线 >1411（当前 1431）
cd frontend && npm run test:run                             # 基线 ≥382
cd frontend && npx tsc --noEmit                             # 0 errors
cd frontend && npx playwright test                          # 基线 ≥135
cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q   # 48 passed / 1 skipped
python3 scripts/backup-e2e-verify.py                        # 30 / 30
python3 scripts/admin-authz-e2e-verify.py                   # 18 / 18
python3 scripts/tenant-isolation-e2e-verify.py              # 16 / 16
python3 scripts/smoke.py                                    # 11 / 11
python3 scripts/no-fake-impl-verify.py                      # PASS
```

注意：登录限流是 5 次/300 秒（`application.yml:187-189`），短时间内跑多个脚本会撞 429 —— 脚本间留出间隔，不要把 429 当成断言失败。

---

## 五、交付要求

1. 逐项 R1–R6 的改动文件列表
2. **每条新增断言的可失败性验证输出**（改回旧实现 → 红 → 恢复 → 绿）
3. 十项门禁实测数字
4. `pages/AuditLogs.test.tsx` 的改写说明
5. 移动端 375px 实测截图或 Playwright 断言输出（R5/R6）
6. 更新 `PHASE95_103_INDEX.md:39` 状态格（写实数字 + 遗留项，**不得只写"已完成"**；红线第 10 条 `:87-91` 与门禁表 `:58`/`:59` 原样保留）
7. commit hash

**提交需用户授权**——不要自行 `git commit` 或 `git push`。

**⚠️ 严禁在未提交的工作区上执行 `git reset` / `git stash`**：PHASE95 曾因误用 `git reset` 清空 41 个后端文件的改动且无 stash 可恢复，导致返工。需要比对基线时，用 `git archive HEAD <path> | tar -x -C /tmp/xxx` 在临时副本里跑。

<!-- END PROMPT -->
