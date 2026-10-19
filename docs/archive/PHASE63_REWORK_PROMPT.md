# PHASE63 返工 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。详细任务书见仓库根目录 `PHASE63_REWORK_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。这是 **PHASE63 的返工**：你的提交 `6660fef` 经审计——**T3 真完成，T1 部分完成，T2 / T4 假完成**。本轮要把 T2/T4 真接进组件、T1 补齐后端存储层，并整改无效测试。

## 0. 工作目录

- 仓库根：`/home/who/multistack-project`
- 后端：`backend-java`（Spring Boot 3 + JPA + Flyway，已应用到 V46）
- 前端：`frontend`（React 19 + MUI v9 + Vite，已装 `@tanstack/react-virtual`）

## 1. 先说你做对的部分（**必须保留，不得回退**）

- **T3 日历翻月是真完成** ✅：`CalendarView.tsx:11` `currentMonth` 状态、`:41` `buildCalendar(allRecords, currentMonth, dateField, titleField)`、`:46` 翻月函数。翻月会重新按月份构建日历。
- **T1 类型定义前后端一致** ✅：前端 `types/collection.ts` 新增 12 种；后端 `meta/FieldDef.java:75-81` 也有 `case "email","url","phone"` / `"currency","percent","duration"` / `"rating"` / `"createdTime","lastModifiedTime","createdBy","lastModifiedBy","autonumber"`。

## 2. 为什么被打回

### ❌ T2 group by：只改了类型定义，没接实现

改动只有 `types/view.ts`（10 行）+ 测试：

- `TableView.tsx` / `GalleryView.tsx` **都没有 groupBy**（grep 零命中）
- 后端 `DynamicTableManager.aggregate`（`:242`）**没被任何视图调用**（改动清单里根本没有后端聚合文件）
- 测试只有 `import { TableConfig, GalleryConfig } from '../../types/view'`（`:1`），断言的是 **TypeScript 类型** —— 编译后就被擦除，运行时零意义

### ❌ T4 虚拟滚动：只装了依赖，没接组件

改动只有 `package.json` + `package-lock.json`（安装 `@tanstack/react-virtual`）+ 测试：

- `TableView.tsx` **没有 `virtual` / `useVirtualizer`**（grep 零命中）
- 测试文件**无任何 import、不渲染组件**（文中 `TableView` 出现 0 次），是纯算术自造：
  ```ts
  const ROW_HEIGHT = 40; const VIEWPORT_HEIGHT = 600;
  test('with 5000 rows, only ~15 rows should be in DOM initially', ...)
  ```
  测的是你自己写的公式，与真实表格无关 —— **组件没改，测试照样全绿**

### ⚠️ T1 只做到"类型合法"，存储与行为层缺失

- **无物理列映射**：`DynamicTableManager` 中搜不到新类型的列映射（既有映射入口在 `:382`「添加物理列」附近）→ 创建 `email`/`currency`/`rating` 字段时不知道建什么列
- **无格式校验**：email / url / phone 无校验
- **无自动字段维护**：`createdTime`/`lastModifiedTime`/`createdBy`/`lastModifiedBy`/`autonumber` **无任何实现代码**
- **`autonumber` 无并发保护**

## 3. 本轮四项任务

### R1（P1）T1 补齐后端存储与行为层

在 `DynamicTableManager`（列映射入口约 `:382`）与字段写入链路中补齐：

1. **物理列映射**（建议）：`email`/`url`/`phone` → TEXT；`currency`/`percent` → NUMERIC（percent 存小数）；`duration` → INTEGER（存分钟）；`rating` → INTEGER；`createdTime`/`lastModifiedTime` → TIMESTAMPTZ；`createdBy`/`lastModifiedBy` → UUID/TEXT；`autonumber` → BIGINT
2. **格式校验**：email / url / phone 写入时校验，非法值返回明确错误（不要静默接受）
3. **自动字段维护**：`createdTime`/`createdBy` 插入时写入；`lastModifiedTime`/`lastModifiedBy` 更新时维护；这几类**拒绝手工写入**
4. **`autonumber` 并发不重复**：用数据库序列或带锁自增，**禁止"先查 max 再 +1"**（并发必重号）

**验收**：建表时各新类型能正确建列（贴 SQL 或 `\d` 输出）；email 非法值被拒；自动类型手工写入被拒且插入/更新后由系统填充；`autonumber` **并发插入不重复**（必须有用例证明）；后端测试数增长。

### R2（P1）T2 真实现（表格 + 画廊分组）

1. **复用后端聚合**：`DynamicTableManager.aggregate(String collectionName, List<String> groupByFields, List<AggSpec> aggSpecs, List<CollectionService.FilterRule> filters)`（`:242-246`）已支持 SQL 下推 GROUP BY —— **直接调用**，不要另写一套。需要端点就在其上包装。
2. `TableView.tsx`（配置读取 `:71`、列 `:77`）与 `GalleryView.tsx` 实现分组渲染：
   - 分组头显示 `count`
   - 数值字段（`number`/`currency`/`percent`/`rating`/`duration`）支持 `sum`/`avg`/`min`/`max`
   - 支持**折叠 / 展开**、组内排序
   - 分组配置随视图保存（复用 `view.config`）
3. 不可分组字段（`formula`/`rollup`/`attachment`/`hasMany` 等）在 UI **明确禁用并说明**，不得静默出错

**验收**：分组 count / 聚合值与数据库 `GROUP BY` **一致**（贴 SQL 与接口输出对比）；折叠展开不错位；反向——选不可分组字段有明确提示（不 500/不白屏）、空结果不渲染、分组字段被删后视图不崩。

### R3（P1）T4 真接入（`TableView` 用 `useVirtualizer`）

1. 在 `TableView.tsx` 用 `@tanstack/react-virtual` 的 `useVirtualizer` 对**行**虚拟化（数据在 `:55` 的 `useQuery(['records', collectionName])`）
2. **必须保留既有交互**：行内编辑（`:38` 的 `apiClient.put` 链路）、行选中、键盘导航、列宽拖拽、横向滚动、表头吸顶
3. 阈值可配（超过 N 行才启用），小数据量走原路径
4. 容器需确定高度，处理好空数据与加载态

**验收**：≥5000 行时 DOM 行数远小于总行数（贴实际 DOM 节点数）+ 首屏时间/滚动帧率对比；交互逐项回归通过；小数据量行为不变。

### R4（P0）测试整改 —— 禁止"空转测试"

1. `ViewGroupByT2.test.tsx` 必须**渲染真实 `TableView`/`GalleryView`**，断言分组头、count、聚合值出现在 DOM 中
2. `TableVirtualT4.test.tsx` 必须**渲染真实 `TableView`**，断言大数据量下 DOM 行数远小于总行数，并验证交互仍可用
3. **删除或重写**只断言 TypeScript 类型、只做算术推演的用例
4. 判据：**把组件实现回退到改造前，这些测试必须失败** —— 不失败就是没测到

### R5（P2）T3 收尾说明

`CalendarView.tsx:28` 是 `records?limit=500` 全量加载后前端按月份过滤。二选一：改按月查询，或保留但**明确说明取舍**（超 500 条会漏数据）。不得让 `limit=500` 成为隐性数据丢失。

## 4. 门禁基线

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1262**（R1 必须新增后端用例）/ 0 failures / 0 errors |
| `npm run test:run` | **> 362**，新增部分必须是**渲染真实组件**的用例 |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`PYTHONPATH=src`） |

## 5. 红线（违反即打回）

1. **类型定义 / 注释 / 依赖 ≠ 实现** —— T2 必须进 `TableView`/`GalleryView` 并调后端 `aggregate`；T4 必须在 `TableView` 里用 `useVirtualizer`
2. **测试必须绑定真实组件** —— 禁止纯类型断言、纯算术推演充数
3. **分组必须复用 `DynamicTableManager.aggregate`** —— 禁止另写一套聚合
4. **严禁 mock 被测主路径 Service**
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
7. **严禁**回滚已闭环提交（PHASE58–63）；**严禁**回退 T3 与 T1 类型定义
8. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar
9. **每项必须给出实测输出**（建表 SQL / 接口对比 / DOM 行数 / 性能数字）

## 6. 教训

1. **"接了一半"的新形态**：只改类型定义、只写注释、只装依赖、只加测试 —— **组件/存储层没动**。判据是追到**实现落点**（哪个组件渲染、哪个 SQL 建列、哪个方法被调用）
2. **测试数暴涨 ≠ 保障增强** —— 类型断言编译后擦除、算术测试测自己，组件没改照样全绿。判据：**回退实现，测试必须失败**
3. **复用已有能力** —— `aggregate` 已是 SQL 下推 GROUP BY，直接调用
4. 沿用：容器 `localhost` 陷阱、`@DataJpaTest` 加 `@ActiveProfiles("test")`、手动 new Service 时 `@Value` 不注入、分页先过滤再分页、clean 构建

## 7. 回报必须给出（缺项打回）

1. 每项实测输出：
   - R1：建列 SQL / email 校验 / 自动字段拒绝手工写入 / `autonumber` 并发不重复
   - R2：分组 count 与数据库 `GROUP BY` 一致、聚合一致、折叠展开、不可分组提示
   - R3：≥5000 行 DOM 行数 + 首屏/帧率对比 + 交互逐项回归
   - R4：说明"回退实现后测试是否失败"
   - R5：limit=500 的取舍说明
2. 五项门禁实际输出
3. 改动文件清单 + `git log --oneline`（**必须已提交**）
4. 明确说明哪些项未做及原因

-----END PROMPT-----

## 投喂方式

1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：复跑门禁 + 检查 `TableView`/`GalleryView` 是否真有 groupBy 与 useVirtualizer + 检查测试是否渲染真实组件（回退实现测试应失败）
