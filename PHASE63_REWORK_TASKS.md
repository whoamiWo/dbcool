# PHASE63 返工任务需求单（提交 `6660fef` 审计：T3 真完成，T1 部分，T2/T4 假完成）

> 交付方式：CodeBuddy 出任务书与提示词 → Kilo Code + GLM-5.3 执行 → CodeBuddy 复验。
> 本轮重点：**T2 / T4 必须真接进组件**，T1 补齐后端存储层，测试必须绑定真实组件。

---

## 一、审计结论

### 1.1 已完成、必须保留（不得回退）

| 项 | 证据 |
|---|---|
| **T3 日历翻月 —— 真完成** | `CalendarView.tsx:11` `currentMonth` 状态、`:41` `buildCalendar(allRecords, currentMonth, dateField, titleField)`、`:46` 翻月函数。翻月会重新按月份构建日历 ✅ |
| **T1 类型定义前后端一致** | 前端 `types/collection.ts` 12 种新类型；后端 `meta/FieldDef.java:75-81` `case "email","url","phone"`、`"currency","percent","duration"`、`"rating"`、`"createdTime","lastModifiedTime","createdBy","lastModifiedBy","autonumber"` ✅ |
| T1 后端测试 | `FieldDefT1Test.java`（55 行） |

### 1.2 T1 剩余缺口（类型合法 ≠ 能用）

`FieldDef` 目前只做到"这些类型名合法"，**存储与行为层缺失**：

- ❌ **无物理列映射**：`DynamicTableManager` 中搜不到新类型的列映射（既有映射入口在 `DynamicTableManager:382`「添加物理列」附近）。创建 `email` / `currency` / `rating` 字段时不知道建什么列。
- ❌ **无格式校验**：email / url / phone 无校验逻辑。
- ❌ **无自动字段维护**：`createdTime` / `lastModifiedTime` / `createdBy` / `lastModifiedBy` / `autonumber` **无任何实现代码**（全仓搜索仅命中无关实体的 `createdBy` 字段）。
- ❌ `autonumber` 无并发保护。

### 1.3 T2 假完成（P1）

改动只有 `types/view.ts`（10 行）+ `ViewGroupByT2.test.tsx`：

- `TableView.tsx` / `GalleryView.tsx` **均无 groupBy**（grep 零命中）
- 后端 `DynamicTableManager.aggregate`（`:242`）**未被任何视图调用**（改动清单中无后端聚合文件）
- 测试只 `import { TableConfig, GalleryConfig } from '../../types/view'`（`:1`），断言的是** TypeScript 类型**——编译后即擦除，运行时零意义

### 1.4 T4 假完成（P1）

改动只有 `package.json` + `package-lock.json`（安装 `@tanstack/react-virtual`）+ `TableVirtualT4.test.tsx`：

- `TableView.tsx` **无 `virtual` / `useVirtualizer`**（grep 零命中）
- 测试文件**无任何 import、不渲染组件**（文中 `TableView` 出现 0 次），是纯算术自造：
  ```ts
  const ROW_HEIGHT = 40; const VIEWPORT_HEIGHT = 600;
  test('with 5000 rows, only ~15 rows should be in DOM initially', ...)
  ```
  测的是测试自己写的公式，与真实表格无关

---

## 二、返工任务（R1–R4）

### R1（P1）T1 补齐后端存储与行为层

在 `DynamicTableManager`（列映射入口约在 `:382`「添加物理列」）与字段写入链路中补齐：

1. **物理列映射**：为 12 种新类型定义对应的列类型（建议）：
   - `email` / `url` / `phone` → `TEXT`
   - `currency` / `percent` / `duration` / `rating` → `NUMERIC`/`INTEGER`（`duration` 存分钟、`percent` 存小数、`rating` 存整数）
   - `createdTime` / `lastModifiedTime` → `TIMESTAMPTZ`
   - `createdBy` / `lastModifiedBy` → `UUID`/`TEXT`
   - `autonumber` → `BIGINT`/`INTEGER`
2. **格式校验**：`email` / `url` / `phone` 写入时校验（非法值返回明确错误，不要静默接受）。
3. **自动字段维护**：`createdTime`/`createdBy` 插入时写入；`lastModifiedTime`/`lastModifiedBy` 更新时维护；这几类**拒绝手工写入**（编辑时应忽略/报错）。
4. **`autonumber` 并发不重复**：用数据库序列或带锁的自增，禁止"先查 max 再 +1"（并发必重号）。

**验收**：

- 建表时各新类型能正确建列（贴建表 SQL 或 `\d` 输出）
- email 非法值被拒、合法值通过
- 自动类型：手工写入被拒，插入/更新后值由系统正确填充
- `autonumber` **并发插入不重复**（必须有用例证明，例如并发 N 次后编号集合无重复）
- 后端测试数增长

### R2（P1）T2 真实现（表格 + 画廊分组）

1. **复用后端聚合**：`DynamicTableManager.aggregate(String collectionName, List<String> groupByFields, List<AggSpec> aggSpecs, List<CollectionService.FilterRule> filters)`（`:242-246`）已支持 SQL 下推 GROUP BY —— **直接调用它**，不要另写一套。
   - 如需新增 Controller/Service 端点承接视图分组，在其上包装即可。
2. `TableView.tsx`（配置读取在 `:71`、列在 `:77`）与 `GalleryView.tsx` 实现分组渲染：
   - 分组头显示该组 `count`
   - 数值字段（`number`/`currency`/`percent`/`rating`/`duration`）支持 `sum`/`avg`/`min`/`max`
   - 支持**折叠 / 展开**、组内排序
   - 分组配置随视图保存（复用 `view.config`）
3. 不可分组字段（`formula`/`rollup`/`attachment`/`hasMany` 等）在 UI **明确禁用并说明**，不得静默出错。

**验收**：分组 `count` / 数值聚合与数据库 `GROUP BY` 结果**一致**（贴 SQL 与接口输出对比）；折叠展开不错位；反向用例：选不可分组字段有明确提示（不 500/不白屏）、空结果分组不渲染、分组字段被删除后视图不崩。

### R3（P1）T4 真接入（`TableView` 用 `useVirtualizer`）

1. 在 `TableView.tsx` 用 `@tanstack/react-virtual` 的 `useVirtualizer` 对**行**做虚拟化（数据来自 `:55` 的 `useQuery(['records', collectionName])`）。
2. **必须保留既有交互**（最大风险）：行内编辑（`:38` 的 `apiClient.put` 更新链路）、行选中、键盘导航、列宽拖拽、横向滚动、表头吸顶。
3. 阈值可配（如超过 N 行才启用），小数据量走原路径。
4. 容器需有确定高度（虚拟化前提），处理好空数据与加载态。

**验收**：

- ≥ 5000 行：对比启用前后**首屏渲染时间**与**滚动帧率**（贴数字）；DOM 中行数应远小于总行数（贴实际 DOM 节点数）
- 交互回归：虚拟化开启后行内编辑/选中/键盘/列宽逐项可用（**必须渲染真实 TableView 的用例**）
- 小数据量不启用、行为不变

### R4（P0）测试整改 —— 禁止"空转测试"

**现状**：`vitest` 涨到 362（+81），但 T2/T4 的测试是纯类型断言与纯算术，**组件没改测试照样全绿**。

**要求**：

1. `ViewGroupByT2.test.tsx` 必须**渲染真实 `TableView` / `GalleryView`**（或至少渲染真实的分组渲染函数），断言分组头、count、聚合值出现在 DOM 中。
2. `TableVirtualT4.test.tsx` 必须**渲染真实 `TableView`**，断言大数据量下 DOM 行数远小于总行数，并验证交互仍可用。
3. **删除或重写**只断言 TypeScript 类型、只做算术推演的用例——这类用例编译后即消失，不构成任何保障。
4. 判据：**把组件实现回退到改造前，这些测试必须失败**；不失败就是没测到。

### R5（P2）T3 收尾说明

`CalendarView.tsx:28` 目前是 `records?limit=500` 全量加载后在前端按月份过滤。二选一：

- 改为**按月查询**（后端加月份参数），并说明；或
- 保留前端过滤，但**明确说明取舍**（超过 500 条时会出现翻月看不到部分数据）。

无论哪种，都必须在代码注释或文档中写清楚，不得让"limit=500"成为隐性数据丢失。

---

## 三、门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1262**（R1 必须新增后端用例）/ 0 failures / 0 errors |
| `npm run test:run` | **> 362**，且新增部分必须是**渲染真实组件**的用例 |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`PYTHONPATH=src`） |

## 四、红线（违反即打回）

1. **类型定义 / 注释 / 依赖 ≠ 实现** —— 本轮特指：T2 必须进 `TableView`/`GalleryView` 并调后端 `aggregate`；T4 必须在 `TableView` 里用 `useVirtualizer`。
2. **测试必须绑定真实组件** —— 禁止纯类型断言、纯算术推演充数（R4）。
3. **分组必须复用 `DynamicTableManager.aggregate`** —— 禁止另写一套聚合。
4. **严禁 mock 被测主路径 Service**。
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值。
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`。
7. **严禁**回滚已闭环提交（PHASE58–63）；**严禁**回退 §1.1 已完成的 T3 与 T1 类型定义。
8. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar。
9. **每项必须给出实测输出**（建表 SQL / 接口对比 / DOM 行数 / 性能数字）。

## 五、教训（务必遵守）

1. **"接了一半"的第四种形态**：只改类型定义、只写注释、只装依赖、只加测试 —— **组件/存储层没动**。判据：追到**实现落点**（哪个组件渲染、哪个 SQL 建列、哪个方法被调用）。
2. **测试数暴涨不等于保障增强** —— 类型断言编译后擦除、算术测试测自己，组件没改照样全绿。判据：**回退实现，测试必须失败**。
3. **复用已有能力** —— 后端 `aggregate` 已是 SQL 下推 GROUP BY，直接调用。
4. 沿用：容器 `localhost` 陷阱、`@DataJpaTest` 加 `@ActiveProfiles("test")`、手动 new Service 时 `@Value` 不注入、分页先过滤再分页、clean 构建。

## 六、交付清单（缺项打回）

1. 每项实测输出：
   - R1：建列 SQL / email 校验 / 自动字段拒绝手工写入 / `autonumber` 并发不重复
   - R2：分组 count 与数据库 `GROUP BY` 一致、聚合一致、折叠展开、不可分组提示
   - R3：≥5000 行 DOM 行数 + 首屏/帧率对比 + 交互逐项回归
   - R4：说明"回退实现后测试是否失败"
   - R5：limit=500 的取舍说明
2. 五项门禁实际输出
3. 改动文件清单 + `git log --oneline`（**必须已提交**）
4. 明确说明哪些项未做及原因
