# PHASE91 投喂提示词（自包含，整段复制给执行方）

> **✅ PHASE91 已完成（PHASE90 Round 2 补做）**
> - T1-T5 全部实现，E2E 162 passed，TS 0 errors，Java 1400 passed
> - 提交：`6ea297e`

复制下方 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容。

-----BEGIN PROMPT-----

你在 `/home/who/multistack-project`（三栈项目：Java + React + Python，Git 仓库，分支 main）工作。

# 任务：移动端适配收尾（视图四件套 + 高频页 + 明确桌面专用范围）

## 项目环境速查（前人踩过的坑，直接照做）

| 事项 | 正确做法 |
|---|---|
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test`（当前基线 **156 passed**） |
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 一键冒烟（真调后端） | `python3 scripts/smoke.py`（11 条链路，426ms） |
| Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |

---

## §1 为什么要补

PHASE90 做了移动端适配（10 个关键页面），但适配了 **KanbanView + CalendarView**，
却漏了 **TableView + GalleryView** —— 这四个是同级的数据视图。
用户切到表格视图就掉回桌面布局了。**这是遗漏，不是取舍**，必须补上。

## §2 关键技术约束（先读，否则白干）

### 目标页面大量用**内联样式**，媒体查询对它无效

| 页面 | 内联 `style={{}}` 数量 |
|---|---|
| `TableView.tsx` | **43 处** |
| `GalleryView.tsx` | **18 处** |

内联样式优先级最高，`@media` 里的普通规则**盖不住它**。

### PHASE90 已摸索出可行做法 —— 照着做，别另起炉灶

它在 `styles.css` 里用**属性选择器**直接匹配内联样式：

```css
div[style*="grid-template-columns"] { ... }
[class*="form-wrapper"] { ... }
[class*="message-list"] { ... }
```

配合 `!important` 生效。
另一种更干净的做法是**给关键容器补 `className`** 再用普通媒体查询。
两种都接受，原则是：**改得动的用 className，改不动的用属性选择器**。

### TableView 里有 `<table>`（2 处）

参照 PHASE90 收尾时给 MyTasks 做的处理（`.table-scroll` 已存在）：

```css
.table-scroll { overflow-x: auto; max-width: 100%; }
@media (max-width: 768px) { .table-scroll > table { min-width: 560px; } }
```

即：**表格容器自身横向滚动，页面不得横向滚动**。

## §3 任务

### T1（P0）TableView

- 表格包进可滚动容器（或移动端改为卡片列表）
- 工具栏（导出/展开/折叠等按钮）在 375px 下换行或收起，不得溢出

### T2（P0）GalleryView —— 重点是**验证**而非重写

它用 `gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))'`，
这个写法**本身是响应式的**，375px 下应自动单列。

所以本页要做的：
- 断言 375px 下确实单列
- 若 `minmax(280px, …)` 在 375px（减 padding 后约 343px）仍溢出，调整最小值

### T3（P1）高频页面

`FormsList`（列表→卡片）、`Profile`（表单单列）、
`WorkflowsList`（列表→卡片）、`WorkflowInstances`（按 T1 方式处理表格）。

### T4（P0）明确"桌面专用"范围 —— 工程决策，不是偷懒

以下页面以**拖拽/绘图/复杂编辑**为主，手机上本就不适用，
明确标注为**桌面专用**并写进文档（而不是默默跳过）：

- 设计器类：`SchemaDesigner`、`SchemaEditor`、`ViewDesigner`、`WorkflowDesigner`、`FormDesigner`、`ErDiagram`、`AclEditor`
- 管理类：`RowAclAdmin`、`NotificationChannels`、`IntegrationsPage`、`RolesList`、`UsersList`

**要求**：移动端访问这些页面时要给出**提示**
（如"该页面建议使用桌面端访问"），而不是让用户看到错乱或崩溃的界面。

### T5（P0）每个页面配 375px 视口测试

在 `frontend/e2e/mobile-responsive.spec.ts` 里补用例（该文件已有 10 个用例 × 3 项目 = 30 通过）：

1. **页面无横向滚动**：`documentElement.scrollWidth <= clientWidth + 1`
2. **关键元素可见且可点**
3. 桌面专用页面：断言**提示可见**

## §4 上批的两条教训（务必避开）

**1. 严禁恒真断言**（PHASE90 实际翻车点）：

```ts
expect(bodyWidth).toBeGreaterThanOrEqual(clientWidth);   // ← 恒真，等于没断言
```

写断言时自问：**这个断言可能为假吗？**
`>=` / `!== undefined` / `length >= 0` 这类不会失败的，等于没写。

**2. mock 的数据形状必须与真实契约一致**

PHASE90 里 `MyTasks` 单测把 `apiClient.get` mock 成直接返回数组，
而真实后端返回 `{ code, data }` —— 结果测试全绿、**页面线上崩溃**。
本批若要 mock，先确认真实响应体（`apiClient` **不解包**）。

## §5 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd frontend && npx playwright test` | **> 156**（新增用例后必须更高） |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd backend-java && mvn -o test` | **> 1400** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §6 交付清单（缺一项视为未完成）

1. 每个页面的适配说明（含用的是 className 还是属性选择器）
2. 新增移动端用例名 + 通过结果
3. **桌面专用页面清单 + 移动端提示的实现方式**
4. 各页面 375×812 截图
5. 门禁五项**实测数字** + 提交 hash + `git status`（**必须干净且已推送**）

## §7 复用（别重造）

- 测试文件：`frontend/e2e/mobile-responsive.spec.ts`
- 样式入口：`frontend/src/styles.css`
- 表格滚动容器：`.table-scroll`（已存在）
- 截图 spec：`frontend/e2e/mobile-screenshots.spec.ts`
- ⚠️ `mobile-chromium` 项目已强制 `browserName: 'chromium'`（本机无 WebKit），**别改回去**

-----END PROMPT-----
