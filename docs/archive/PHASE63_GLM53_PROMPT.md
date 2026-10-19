# PHASE63 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。详细任务书见仓库根目录 `PHASE63_GLM53_VIEW_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。本批（PHASE63）目标是**数据视图深度**：字段类型扩展、视图 group by、日历翻月、虚拟滚动。上一批 PHASE62（集成能力）已闭环。

## 0. 工作目录

- 仓库根：`/home/who/multistack-project`
- 后端：`backend-java`（Spring Boot 3 + JPA + Flyway，已应用到 V46）
- 前端：`frontend`（React 19 + MUI v9 + Vite）
- 单测跑 H2（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q`）

## 1. 现状（CodeBuddy 已实测，先理解再动手）

| 项 | 现状 | 证据 |
|---|---|---|
| 字段类型 | 现有 **13 种** | `frontend/src/types/collection.ts:3-16`：text/number/boolean/date/datetime/select/multiSelect/attachment/belongsTo/hasMany/formula/rollup/lookup |
| 视图 group by | 仅**看板**有（分组=列）；表格/画廊**无** | `groupBy` 只命中 `pages/KanbanView.tsx`、`pages/ViewDesigner.tsx`、`types/view.ts` |
| **后端分组能力** | **已具备**（只是没给视图用） | `meta/DynamicTableManager.java:210`、`:242` `aggregate(collectionName, groupByFields, ...)` = **SQL 下推 GROUP BY** |
| 日历翻月 | **无**，月份标题写死当前月 | `pages/CalendarView.tsx:41` `monthName = new Date().toLocaleDateString(...)`，无任何翻月状态 |
| 虚拟滚动 | **零** | grep `react-window`/`react-virtuoso`/`@tanstack/react-virtual`/`virtual` 全部无命中 |

视图文件：`pages/` 下 `TableView.tsx`、`KanbanView.tsx`、`GalleryView.tsx`、`CalendarView.tsx`、`DetailView.tsx`、`ViewDesigner.tsx`、`ViewsList.tsx`。

## 2. 四项任务

### T1（P1）字段类型扩展（12 种）

**可编辑类型（7）**：`email`（格式校验 + mailto）、`url`（格式校验 + 新窗口）、`phone`（宽松校验）、`currency`（币种符号 + 小数位 + 右对齐）、`percent`（存小数、显示 %）、`rating`（星级，默认 5）、`duration`（分钟存储、按 `Hh Mm` 显示）

**自动类型（5，只读）**：`createdTime`、`lastModifiedTime`、`createdBy`、`lastModifiedBy`、`autonumber`

**要求**：

1. **前后端类型定义必须同步**：`types/collection.ts` 的 `FieldType` 与后端字段校验同时扩展，**严禁只改前端**。
2. 每种类型需有：单元格**渲染** + **编辑控件**（可编辑类型）+ **输入校验**（非法值明确提示）+ **筛选/排序可用**。
3. 自动类型：不可手工编辑（界面置灰 + 说明）；`createdTime/createdBy` 插入时写入，`lastModifiedTime/lastModifiedBy` 更新时维护；`autonumber` 按集合自增且**并发不重复**。
4. CSV 导入导出兼容新类型（值可往返，不报错）。
5. 不得破坏既有 13 种类型。

**验收**：每种可编辑类型 1 个正常渲染/编辑用例 + 1 个**非法值校验**用例；每种自动类型 1 个用例（值正确 + **手工修改被拒绝**）；`autonumber` 并发不重复用例。**前端测试数必须增长**。

### T2（P1）视图 group by（表格 / 画廊）

1. **复用后端已有能力**：`DynamicTableManager.aggregate(collectionName, groupByFields, ...)`（`:242`）已支持 SQL 下推 GROUP BY —— **不要另写一套聚合**。若入参/出参不够用就扩展它，不要新建并行逻辑。
2. `TableView.tsx` 与 `GalleryView.tsx` 支持按字段分组：
   - 分组头显示 `count`
   - 数值字段（number/currency/percent/rating/duration）支持 `sum`/`avg`/`min`/`max`
   - 支持**折叠/展开**、组内排序
3. 不可分组的字段类型（`formula`/`rollup`/`attachment`/`hasMany` 等）必须在 UI **明确禁用并说明**，不得静默出错。
4. 分组配置随视图保存（复用既有 `view.config`）。

**验收（贴实测输出）**：分组 count 与数据库 `GROUP BY` 结果**一致**；数值聚合一致；折叠/展开不错位；反向——选不可分组字段有明确提示（不 500/不白屏）、空结果分组不渲染、分组字段被删除后视图不崩。

### T3（P1）日历翻月

**要求**：

1. `CalendarView.tsx` 增加 `currentMonth` 状态 + 上一月/下一月/"回到今天"，标题跟随状态。
2. **必须真加载对应月份数据**（按月查询，或前端在已加载数据中过滤并说明取舍）。
   **不接受"只改显示、数据不变"** —— 翻月后数据没变即视为未完成。
3. 边界：跨年（12 月 → 次年 1 月）、无数据月份、今日高亮、日期为空的记录不进日历。
4. 不破坏既有渲染（星期表头、事件排布）。

**验收**：翻月前后**标题与事件**对比输出（证明数据真变）；跨年正确；空月份显示空态；所有记录日期为空时不崩。

### T4（P2）表格虚拟滚动

1. `TableView.tsx` 在 ≥ 1000 行时启用虚拟滚动，只渲染可视区域。可引入成熟库（如 `@tanstack/react-virtual`）或自研窗口，二选一并说明理由。
2. **不得破坏既有交互**（最大风险）：行内编辑、行选中、键盘导航、列宽拖拽、横向滚动、表头吸顶。
3. 阈值可配，小数据量走原路径。
4. 新依赖需兼容 React 19 / MUI v9，`tsc --noEmit` 保持 0。

**验收**：≥5000 行数据，对比启用前后**首屏渲染时间**与**滚动帧率**（贴数字）；虚拟化开启后行内编辑/选中/键盘/列宽逐项回归通过；小数据量不启用、行为不变。

## 3. 范围边界（不要越界）

- **不做** CRDT、IM、集成能力（PHASE58–62 已闭环）
- **不改** 既有 13 种字段类型的行为（只新增）
- **不破坏** `DynamicTableManager.aggregate` 的既有 BI 调用方（可扩展）
- **不做** 移动端适配、i18n 新语种、工程与质量项

## 4. 门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 基线 |
|---|---|
| `mvn -o test` | ≥ **1260** / 0 failures / 0 errors，BUILD SUCCESS |
| `npm run test:run` | **> 281**（T1 必须新增用例） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** passed |
| `pytest tests/` | **43 passed, 1 skipped**（`PYTHONPATH=src`） |

## 5. 红线（违反即整批打回）

1. **字段类型必须前后端一致**（严禁只改前端）
2. **日历翻月必须真加载数据**（严禁只改显示）
3. **虚拟滚动不得破坏既有交互**
4. **严禁 mock 被测主路径 Service**（测 Service/Repository 必须真跑）
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
6. **严禁**"加了类型不加测试"—— 前端测试数必须增长
7. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
8. **严禁**回滚已闭环提交（PHASE58–62）
9. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar
10. **每项必须给出实测输出**（接口输出 / 页面证据 / 性能数字）

## 6. 本项目教训（血泪）

1. **"接口 200 但业务没发生"** —— 判据是**既看 HTTP 码也查数据库/日志**（PHASE62 Slack 入站就是 200 + 0 命中）。日历翻月同理：翻月后要查数据真变了
2. **前后端协议必须对齐**（字段类型、枚举、DTO）—— PHASE61 mentions、PHASE62 事件格式都是教训
3. **复用已有能力，不要另起炉灶** —— 后端已有 `DynamicTableManager.aggregate`，T2 直接复用
4. **容器内 `localhost` 指向容器自身** —— 服务间地址必须 `@Value` 可配 + compose 服务名
5. **单测通过 ≠ 端到端生效** —— 虚拟滚动性能与交互、日历数据变化都要实测
6. **`@DataJpaTest` 必须加 `@ActiveProfiles("test")`**（否则 Flyway 在 H2 跑 PG 迁移直接失败）
7. **手动 `new Service` 时 `@Value` 不注入** —— 需 `ReflectionTestUtils.setField`
8. **分页语义**：先过滤再分页

## 7. 回报必须给出（缺项打回）

1. 每项实测输出：
   - T1：渲染/编辑/非法值校验/自动类型拒绝手工修改/`autonumber` 并发不重复
   - T2：分组 count 与数据库 `GROUP BY` 一致、聚合一致、折叠展开、不可分组提示
   - T3：翻月前后**标题与事件**对比 + 跨年 + 空月份
   - T4：≥5000 行首屏时间/帧率对比 + 交互逐项回归
2. 五项门禁实际输出数字
3. 改动文件清单 + `git log --oneline`（**必须已提交**）
4. 明确说明哪些项未做及原因

-----END PROMPT-----

## 投喂方式

1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：复跑门禁 + 追调用链（确认 group by 真复用 `aggregate`、日历真查数据）+ 实测翻月与滚动性能
