# PHASE91 任务书：移动端适配收尾（视图四件套 + 高频页 + 明确桌面专用范围）

> 承接 PHASE90（做了 10 个关键页面）。本批把 P1-5 **真正收尾**。

---

## §1 为什么要补（一个明显的不一致）

PHASE90 适配了 **KanbanView + CalendarView**，却漏了 **TableView + GalleryView** ——
这四个是同级的数据视图。用户切到表格视图，就掉回桌面布局了。

**同批做的四选二，是遗漏而非取舍**，必须补上。

## §2 关键技术约束（先读，否则会白干）

### 目标页面大量使用**内联样式**，媒体查询对它无效

| 页面 | 内联 `style={{}}` 数量 |
|---|---|
| `TableView.tsx` | **43 处** |
| `GalleryView.tsx` | **18 处** |

内联样式优先级最高，`@media` 里的普通规则**盖不住它**。

### PHASE90 已经摸索出可行做法 —— 照着做

它在 `styles.css` 里用**属性选择器**直接匹配内联样式：

```css
div[style*="grid-template-columns"] { ... }
[class*="form-wrapper"] { ... }
[class*="message-list"] { ... }
```

配合 `!important` 生效。**沿用这个套路**，不要另起炉灶。

另一种更干净的做法：**给关键容器补 `className`**，然后用普通媒体查询。
两种都接受，原则是 —— **改得动的用 className，改不动的用属性选择器**。

### TableView 里有 `<table>`（2 处）

参照 PHASE90 收尾时给 MyTasks 做的处理：

```css
.table-scroll { overflow-x: auto; max-width: 100%; }
@media (max-width: 768px) { .table-scroll > table { min-width: 560px; } }
```

即：**表格容器自身横向滚动，页面不得横向滚动**。

## §3 任务

### T1（P0）TableView

- 表格包进可滚动容器（或移动端改为卡片列表）
- 工具栏（导出/展开/折叠等按钮）在 375px 下换行或收起，不得溢出

### T2（P0）GalleryView

它用 `gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))'`
—— 这个写法**本身是响应式的**，375px 下应自动单列。

**所以本页的重点是验证而非重写**：
- 断言 375px 下确实是单列
- 若 `minmax(280px, …)` 在 375px（减 padding 后约 343px）仍导致溢出，调整最小值

### T3（P1）高频页面

| 页面 | 说明 |
|---|---|
| `FormsList` | 列表 → 卡片 |
| `Profile` | 表单单列 |
| `WorkflowsList` | 列表 → 卡片 |
| `WorkflowInstances` | 列表/表格按 T1 方式处理 |

### T4（P0）明确"桌面专用"范围 —— 这是工程决策，不是偷懒

以下页面**拖拽/绘图/复杂表格编辑**为主，手机上本就不适用，
明确标注为**桌面专用**并写进文档（而不是默默跳过）：

- 设计器类：`SchemaDesigner`、`SchemaEditor`、`ViewDesigner`、`WorkflowDesigner`、`FormDesigner`、`ErDiagram`、`AclEditor`
- 管理类：`RowAclAdmin`、`NotificationChannels`、`IntegrationsPage`、`RolesList`、`UsersList`

**要求**：前端需在移动端访问这些页面时给出**提示**（例如"该页面建议使用桌面端访问"），
而不是让用户看到一个错乱或崩溃的界面。

### T5（P0）每个页面配 375px 视口测试（沿用 PHASE90 的 spec）

在 `e2e/mobile-responsive.spec.ts` 里补用例，每个页面至少：

1. **页面无横向滚动**：`documentElement.scrollWidth <= clientWidth + 1`
2. **关键元素可见且可点**
3. 桌面专用页面：断言**提示可见**

## §4 上批的两条教训（务必避开）

**1. 严禁恒真断言**（PHASE90 实际翻车点）：

```ts
expect(bodyWidth).toBeGreaterThanOrEqual(clientWidth);   // ← 恒真，等于没断言
```

写断言时自问：**这个断言可能为假吗？** `>=` / `!== undefined` / `length >= 0` 这类不会失败的，等于没写。

**2. mock 的数据形状必须与真实契约一致**

PHASE90 里 `MyTasks` 单测把 `apiClient.get` mock 成直接返回数组，
而真实后端返回 `{ code, data }` 信封 —— 结果测试全绿、**页面线上崩溃**。
本批若要 mock，先确认真实响应体长什么样（`apiClient` **不解包**）。

## §5 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd frontend && npx playwright test` | **> 156**（当前基线，新增用例后必须更高） |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd backend-java && mvn -o test` | **> 1400** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §6 交付清单

1. 每个页面的适配说明（含"改 className"还是"用属性选择器"）
2. 新增的移动端用例名 + 通过结果
3. **桌面专用页面清单 + 移动端提示的实现方式**
4. 各页面 375×812 截图
5. 门禁五项实测数字 + 提交 hash + `git status`（**必须干净且已推送**）

## §7 复用

- 测试文件：`frontend/e2e/mobile-responsive.spec.ts`（已有 10 个用例 × 3 项目 = 30 通过）
- 样式入口：`frontend/src/styles.css`
- 表格滚动容器：`.table-scroll`（已存在）
- 截图：`frontend/e2e/mobile-screenshots.spec.ts`
- 注意 `mobile-chromium` 项目已强制 `browserName: 'chromium'`（本机无 WebKit），别改回去
