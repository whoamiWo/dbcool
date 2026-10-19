# PHASE63 第二轮返工 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。详细任务书见仓库根目录 `PHASE63_REWORK2_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。这是 **PHASE63 的第二轮返工**（上一轮提交 `8e8a9dd`）。

**先说明：你上一轮的诚实度值得肯定** —— 主动披露了 R2 未复用 aggregate（违反红线 3）、R1 验证是模拟的、R4 测试可能不绑定组件、playwright/pytest 未跑。这让本轮目标非常明确。本轮落点已由 CodeBuddy 追出，**请严格按给定位置实施，不要另找地方**。

## 0. 工作目录

- 仓库根：`/home/who/multistack-project`
- 后端：`backend-java`（Spring Boot 3 + JPA + Flyway，已应用到 V46）
- 前端：`frontend`（React 19 + MUI v9 + Vite）

## 1. 已完成、必须保留（不得回退）

- R2 分组渲染（前端）：`TableView.tsx:80` `groupByField`、`:82` `nonGroupableTypes`、`:96` 分组 key、`:129`；`GalleryView.tsx` 分组卡片
- R3 虚拟滚动：`TableView.tsx:4` import `useVirtualizer`、`:137-140` `VIRTUAL_THRESHOLD=100`
- R5 日历按月查询：按月范围查询 + 每月独立缓存
- T1 类型定义：`FieldDef.isValidType`（`:62-81`）+ 前端 12 种
- 已删除 3 个假测试文件（纯类型/算术）

门禁现状：mvn 1277、vitest 362、tsc 0 ✅；playwright / pytest 未跑。

## 2. 本轮必补任务

### R1-A（P0）字段类型映射接生产 —— 唯一正确落点已确认

字段类型 → 物理列的映射**不在** `FieldDef`（它只有 `isValidType`/`of`），**也不在** `DynamicTableManager`（`addPhysicalColumn` 的 `columnType` 由调用方传入）。

**唯一正确落点是 `AsyncMigrationService.mapJsonbType(type)`（`:149-158`）**：

```java
private String mapJsonbType(String type) {
    return switch (type) {
        case "text", "select", "multiSelect" -> "TEXT";
        case "number"                        -> "NUMERIC";
        case "boolean"                       -> "BOOLEAN";
        case "date", "datetime"              -> "TIMESTAMPTZ";
        case "attachment"                    -> "TEXT";
        case "belongsTo", "hasMany"          -> "UUID";
        case "formula"                       -> "TEXT";
        default -> throw new IllegalArgumentException("不支持的字段类型: " + type);
    };
}
```

12 种新类型**全不在其中**，而 `default` 直接抛异常 → **现在创建任何新类型字段（email/currency/rating/autonumber…）都会直接失败**。

**要求**：在 `mapJsonbType` 补齐：`email`/`url`/`phone` → TEXT；`currency`/`percent` → NUMERIC（percent 存小数）；`duration`/`rating` → INTEGER（duration 存分钟）；`createdTime`/`lastModifiedTime` → TIMESTAMPTZ；`createdBy`/`lastModifiedBy` → UUID；`autonumber` → BIGINT。并确认 `ALTER_TYPE` 分支（`:143`）走同一映射。

**验收**：写**直接调用 `mapJsonbType`（生产方法）** 的用例覆盖 12 种；并给出**真实建表证据**（建一个含新类型字段的集合，贴 `\d` 或建表 SQL）。**不接受临时 main 方法的模拟验证**。

### R1-B（P1）校验与自动字段维护接入写入链路

**落点**：`CollectionService.insertRecord(String collectionName, Map<String,Object> data, String tenantId)`（`:242`）及对应更新方法（入口 `CollectionController.createRecord` `:241`）。

**要求**：

1. `email`/`url`/`phone` 插入/更新时校验，非法值返回明确错误（不静默接受）
2. `createdTime`/`createdBy` 插入时系统写入；`lastModifiedTime`/`lastModifiedBy` 更新时维护；这几类 + `autonumber` **拒绝手工写入**（忽略或报错，二选一并说明）
3. `autonumber` 并发安全：用数据库序列或带锁自增，**禁止"先查 max 再 +1"**（并发必重号）

**验收**：自动字段值正确且手工写入被拒；`autonumber` **并发插入不重复**（贴并发后编号集合）；后端测试数增长。

### R2-B（P1）分组聚合接后端（补齐红线 3）

**要求**：

1. 调用 `DynamicTableManager.aggregate(String collectionName, List<String> groupByFields, List<AggSpec> aggSpecs, List<CollectionService.FilterRule> filters)`（`:242-246`，已是 SQL 下推 GROUP BY）。若前端拿不到，新增 Controller/Service 端点在其上包装，**不得重写聚合逻辑**。
2. `TableView`/`GalleryView` 改为消费该接口结果渲染分组头与聚合值，保留折叠/展开与组内排序。
3. 前端分组保留为**降级路径**（接口失败时），默认走后端。

**验收**：**接口返回的 count / sum 与数据库 `GROUP BY` 逐一一致**（贴 SQL 与接口输出对比）；反向用例：不可分组字段提示、空结果不渲染、分组字段被删不崩。

### R4-B（P0）测试必须绑定真实组件

**现状**：新增测试中 grep 不到 `render(` / `screen.` / `@testing-library` —— 仍是"把组件算法复制到测试里断言"，未渲染真实组件。

**要求**：

1. 用 Testing Library **渲染真实 `TableView` / `GalleryView`**（必须是真实组件代码，不是复制的算法）
2. 断言落在 **DOM**：分组头文本、`count`、`sum` 实际出现；虚拟化下 DOM 行数远小于总行数
3. **判据（必答）**：把实现回退（移除 `groupByField` / `virtualizer`）后这些测试**必须失败** —— 回报中必须明确说明这一点是否成立
4. 删除仅做算术推演、仅断言类型的残留用例

### R6（P1）补齐未跑的两项门禁

- `npx playwright test` ≥ **64**
- `pytest`：`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` → 基线 **43 passed, 1 skipped**（不加 `PYTHONPATH=src` 会只跑出子集，不是全量）

## 3. 非阻塞建议（可选）

`TableView.tsx:138` `shouldVirtualize = !groupByField && ...` → 分组模式下禁用虚拟化，大数据分组展开仍有性能风险。可在分组内再做一层虚拟化，或在 UI 标注限制。

## 4. 门禁基线

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1277** / 0 failures / 0 errors |
| `npm run test:run` | **> 362**，新增必须是渲染真实组件的用例 |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（全量） |

## 5. 红线（违反即打回）

1. **必须按给定落点实施**：R1-A 改 `AsyncMigrationService.mapJsonbType`；R1-B 接 `CollectionService.insertRecord`；R2-B 调 `DynamicTableManager.aggregate`。改错位置等于没改
2. **测试必须渲染真实组件**（禁复制算法的"影子测试"）
3. **分组必须复用 `aggregate`**（禁另写聚合）
4. **严禁 mock 被测主路径 Service**
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
7. **严禁**回滚已闭环提交（PHASE58–63）；**严禁**回退已完成项
8. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar
9. **每项必须给出实测输出**（建表 SQL / 接口对比 / DOM 行数 / 并发编号集合 / 门禁数字）

## 6. 教训

1. **"类型合法" ≠ "能用"** —— `isValidType` 只是第一层，建列映射、校验、自动维护缺一不可
2. **追到真正的实现落点** —— 顺调用链追到**实际被调用的方法**（`addPhysicalColumn` 的 columnType 来自调用方 → 调用方用 `mapJsonbType`）
3. **测试不看数字看绑定** —— 362 个用例若不渲染组件等于 0 保障；判据永远是**回退实现必须失败**
4. **模拟验证不算实测** —— 临时 `main` 方法跑出的映射不能证明生产路径生效
5. 沿用：容器 `localhost` 陷阱、`@DataJpaTest` 加 `@ActiveProfiles("test")`、分页先过滤再分页、clean 构建、grep 字符串常量加 `-i`

## 7. 回报必须给出（缺项打回）

1. 每项实测输出：
   - R1-A：`mapJsonbType` 覆盖 12 种 + **真实建表证据**
   - R1-B：格式校验、自动字段拒绝手工写入、`autonumber` 并发不重复（贴编号集合）
   - R2-B：接口 vs 数据库 `GROUP BY` 逐一一致
   - R4-B：**回退实现后测试是否失败**（必须明确回答）
   - R6：playwright、pytest 全量数字
2. 五项门禁实际输出
3. 改动文件清单 + `git log --oneline`（**必须已提交**）
4. 明确说明哪些项未做及原因

-----END PROMPT-----

## 投喂方式

1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：确认 `mapJsonbType` 含 12 种新类型 + 真实建表、分组走 `aggregate` 且与数据库 GROUP BY 一致、测试渲染真实组件（回退即失败）、补齐 playwright/pytest
