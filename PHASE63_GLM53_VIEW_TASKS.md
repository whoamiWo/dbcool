# PHASE63 任务需求单：数据视图深度（交 Kilo Code + GLM-5.3 执行，CodeBuddy 审计）

> 交付方式沿用 PHASE58–62：CodeBuddy 出任务书与提示词 → Kilo Code + GLM-5.3 执行 → CodeBuddy 按审计口径复验（复跑门禁 + 追调用链 + 实测）。

---

## 一、现状审计（CodeBuddy 实测，含行号证据）

| 项 | 现状 | 证据 |
|---|---|---|
| **字段类型** | 现有 **13 种** | `frontend/src/types/collection.ts:3-16`：`text`/`number`/`boolean`/`date`/`datetime`/`select`/`multiSelect`/`attachment`/`belongsTo`/`hasMany`/`formula`/`rollup`/`lookup` |
| **视图 group by** | 仅**看板**有（分组=列）；表格/画廊**无分组** | `groupBy` 仅命中 `pages/KanbanView.tsx`、`pages/ViewDesigner.tsx`、`types/view.ts` |
| **后端分组能力** | **已具备**，只是没给视图用 | `meta/DynamicTableManager.java:210`（BI 聚合）、`:242` `aggregate(collectionName, groupByFields, ...)` —— **SQL 下推 GROUP BY** |
| **日历翻月** | **无翻月**，月份标题写死当前月 | `pages/CalendarView.tsx:41` `const monthName = new Date().toLocaleDateString('zh-CN', {...})`；无 `nextMonth`/`prevMonth`/`onNavigate` 等任何状态 |
| **虚拟滚动** | **零** —— 无依赖、无实现 | 全仓 grep `react-window` / `react-virtuoso` / `@tanstack/react-virtual` / `virtual` 均无命中 |

相关视图文件：`pages/` 下 `TableView.tsx`、`KanbanView.tsx`、`GalleryView.tsx`、`CalendarView.tsx`、`DetailView.tsx`、`ViewDesigner.tsx`、`ViewsList.tsx`。

---

## 二、本批任务（T1–T4）

### T1（P1）字段类型扩展

**目标清单（12 种）**，分两类：

**可编辑类型（7 种）**

| 类型 | 说明 |
|---|---|
| `email` | 邮箱，格式校验，可点击 `mailto:` |
| `url` | 链接，格式校验，可点击新窗口打开 |
| `phone` | 手机号，格式校验（宽松，避免误伤分机号） |
| `currency` | 金额，带币种符号与小数位配置，右对齐 |
| `percent` | 百分比，存储小数、显示 `%` |
| `rating` | 评分，整数星级（可配最大值，默认 5） |
| `duration` | 时长，以分钟存储、按 `Hh Mm` 显示 |

**自动类型（5 种，只读、由系统维护）**

| 类型 | 说明 |
|---|---|
| `createdTime` | 记录创建时间 |
| `lastModifiedTime` | 最后修改时间 |
| `createdBy` | 创建人（关联用户） |
| `lastModifiedBy` | 最后修改人 |
| `autonumber` | 自增编号 |

**要求**：

1. **前后端类型定义必须同步**：`frontend/src/types/collection.ts` 的 `FieldType` 与后端字段校验逻辑同时扩展，不得只改前端。
2. 每种类型需具备：**单元格渲染** + **编辑控件**（可编辑类型）+ **输入校验**（非法值有明确提示）+ **筛选/排序可用**。
3. 自动类型：不可手工编辑，编辑界面置灰并说明原因；`createdTime`/`createdBy` 在插入时写入，`lastModifiedTime`/`lastModifiedBy` 在更新时维护；`autonumber` 按集合自增（并发下不得重复）。
4. 导入导出（CSV）需兼容新类型（至少不报错、值可往返）。
5. 不得破坏既有 13 种类型的行为。

**验收**：

- 每种可编辑类型：1 个正常渲染/编辑用例 + 1 个**非法值校验**用例
- 每种自动类型：1 个用例（创建/更新后值正确且**手工修改被拒绝**）
- `autonumber` 并发插入不重复的用例
- 前端测试数必须**增长**（不得只加类型不加测试）

### T2（P1）视图 group by（表格 / 画廊）

**要求**：

1. **复用后端已有能力**：`DynamicTableManager.aggregate(collectionName, groupByFields, ...)`（`:242`）已支持 SQL 下推 GROUP BY——**不要另写一套聚合实现**。若该方法的入参/出参不足以支撑视图分组，则在其上扩展，而非新建并行逻辑。
2. 表格视图（`TableView.tsx`）与画廊视图（`GalleryView.tsx`）支持**按字段分组**：
   - 分组头显示该组 `count`
   - 数值字段（`number`/`currency`/`percent`/`rating`/`duration`）支持 `sum` / `avg` / `min` / `max` 聚合显示
   - 支持分组**折叠 / 展开**
   - 组内支持排序
3. 分组字段的可选范围：允许 `select` / `multiSelect` / `boolean` / `date` / `datetime` / `text` / `number` 等；
   **不可分组**的字段类型（`formula` / `rollup` / `attachment` / `hasMany` 等）必须在 UI 上**明确禁用并说明**，不得静默出错。
4. 分组配置需随视图保存（复用既有 `view.config` 机制）。

**验收（必须给出实测输出）**：

- 表格按 `select` 字段分组 → 分组数、每组 count 与数据库 `GROUP BY` 结果**一致**
- 数值字段聚合（sum）与数据库结果一致
- 折叠/展开后数据不丢失、不错位
- 反向用例：选不可分组字段 → 明确提示（不 500、不白屏）；空结果分组不渲染；分组字段被删除后视图不崩

### T3（P1）日历翻月

**现状**：`CalendarView.tsx:41` 月份标题写死 `new Date()`，无法切换月份。

**要求**：

1. 增加 `currentMonth` 状态 + 上一月/下一月/"回到今天" 操作，月份标题跟随状态。
2. **必须真加载对应月份的数据**：按月查询（后端按月过滤，或前端在已加载数据中过滤并明确说明取舍）。
   - **不接受"只改显示、数据仍是全量/当前月"** —— 翻月后若数据没变即视为未完成。
3. 边界处理：跨年（12 月 → 次年 1 月）、无数据的月份、今日高亮、日期字段为空的记录不进入日历。
4. 不得破坏既有日历渲染（星期表头、事件排布）。

**验收**：

- 翻到上个月 / 下个月，标题与事件**同步变化**（贴前后对比输出）
- 跨年翻月正确
- 空月份显示空态而非报错
- 反向：所有记录日期字段为空 → 不崩、显示空态

### T4（P2）表格虚拟滚动

**现状**：前端零虚拟滚动实现/依赖。

**要求**：

1. 表格视图（`TableView.tsx`）在**大数据量**（≥ 1000 行）下启用虚拟滚动，只渲染可视区域行。
   - 可引入成熟库（如 `@tanstack/react-virtual`）或自研窗口实现，二选一并说明理由。
2. **不得破坏既有交互**（这是本任务最大风险）：行内编辑、行选中、键盘导航、列宽拖拽、横向滚动、表头吸顶。
3. 阈值可配（如超过 N 行才启用），小数据量走原路径。
4. 若引入新依赖，需确认与 React 19 / MUI v9 兼容，且 `tsc --noEmit` 保持 0 错误。

**验收**：

- 造 ≥ 5000 行数据，对比启用前后：**首屏渲染时间**与**滚动帧率**（贴实测数字）
- 交互回归：虚拟化开启后，行内编辑、选中、键盘导航、列宽拖拽**均可用**（逐项用例）
- 反向：小数据量（< 阈值）不启用虚拟化，行为与之前完全一致

---

## 三、范围边界（明确排除，防越界）

- **不做** CRDT 字符级协同、IM 能力、集成能力（PHASE58–62 已闭环的部分）
- **不改** 既有 13 种字段类型的行为（只新增）
- **不重构** `DynamicTableManager.aggregate` 的既有 BI 调用方（可扩展，不得破坏）
- **不做** 移动端适配、i18n 新增语种
- **不做** 工程与质量项（压测基线、死代码清理、两套看板合并）

## 四、门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 基线 |
|---|---|
| `mvn -o test` | **≥ 1260** / 0 failures / 0 errors，BUILD SUCCESS |
| `npm run test:run` | **> 281**（T1 要求新增字段类型用例） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | **≥ 64** passed |
| `pytest tests/` | **43 passed, 1 skipped**（本地需 `PYTHONPATH=src`） |

## 五、红线（违反即整批打回）

1. **字段类型必须前后端一致** —— 严禁只改前端类型定义而后端不认（PHASE61 的 `@` 提及协议不一致就是同款问题）。
2. **日历翻月必须真加载数据** —— 严禁只改月份显示、数据不变。
3. **虚拟滚动不得破坏既有交互** —— 行内编辑/选中/键盘/列宽拖拽必须回归验证。
4. **严禁 mock 被测主路径 Service** —— 测 Service/Repository 必须 `@DataJpaTest`/`@SpringBootTest` 真跑。
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值。
6. **严禁**"加了类型不加测试"—— 前端测试数必须增长。
7. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`。
8. **严禁**回滚已闭环提交（PHASE58–62）。
9. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` 并用 `zipfile` 校验 jar。
10. **每项必须给出实测输出**（接口输出 / 页面证据 / 性能对比数字），不接受"配置了应该就好了"。

## 六、本项目教训（血泪，务必遵守）

1. **"接口 200 但业务没发生"** —— 判据是**既看 HTTP 码，也查数据库/日志**（PHASE62 的 Slack 入站就是 200 + 0 命中）。日历翻月同理：翻月后要查数据真变了。
2. **前后端协议必须对齐** —— 字段类型、枚举值、DTO 结构，改一端必须同步另一端（PHASE61 mentions、PHASE62 事件格式都是教训）。
3. **复用已有能力，不要另起炉灶** —— 后端已有 `DynamicTableManager.aggregate`（SQL 下推 GROUP BY），T2 应复用。
4. **容器内 `localhost` 指向容器自身** —— 服务间地址必须 `@Value` 可配 + compose 服务名。
5. **单测通过 ≠ 端到端生效** —— 虚拟滚动的性能与交互、日历翻月的数据变化，都必须实测。
6. **`@DataJpaTest` 必须加 `@ActiveProfiles("test")`**（否则 Flyway 在 H2 上跑 PG 语法迁移直接失败）。
7. **手动 `new Service` 时 `@Value` 不注入** —— 需 `ReflectionTestUtils.setField`。
8. **分页语义**：先过滤再分页。

## 七、交付清单（回报时必须给出，缺项打回）

1. 每项实测输出：
   - T1：新类型渲染/编辑/非法值校验/自动类型拒绝手工修改/`autonumber` 并发不重复
   - T2：分组 count 与数据库 `GROUP BY` 一致、数值聚合一致、折叠展开、不可分组字段提示
   - T3：翻月前后**标题与事件**对比（证明数据真变了）+ 跨年 + 空月份
   - T4：≥5000 行的首屏时间/滚动帧率对比 + 交互回归逐项结果
2. 五项门禁实际输出数字
3. 改动文件清单 + `git log --oneline`（**必须已提交，不得留未提交改动**）
4. 明确说明哪些项未做及原因
