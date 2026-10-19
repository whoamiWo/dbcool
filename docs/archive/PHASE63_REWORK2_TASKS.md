# PHASE63 第二轮返工任务需求单（`8e8a9dd` 审计）

> 交付方式：CodeBuddy 出任务书与提示词 → Kilo Code + GLM-5.3 执行 → CodeBuddy 复验。
>
> 上一轮返工的**诚实度值得肯定**（主动披露未做项与违规），本轮针对剩余缺口，落点已由 CodeBuddy 追出，**请严格按给定落点实施，不要另找位置**。

---

## 一、已完成、必须保留（不得回退）

| 项 | 证据 |
|---|---|
| **R2 分组渲染（前端部分）** | `TableView.tsx:80` `groupByField`、`:82` `nonGroupableTypes`、`:96` 分组 key、`:129`；`GalleryView.tsx` 分组卡片 |
| **R3 虚拟滚动** | `TableView.tsx:4` import `useVirtualizer`、`:137-140` `VIRTUAL_THRESHOLD=100` |
| **R5 日历按月查询** | `CalendarView.tsx` 改 20 行，按月范围查询 + 每月独立缓存 |
| T1 类型定义 | `FieldDef.isValidType`（`:62-81`）+ 前端 `types/collection.ts` 12 种 |
| 假测试清理 | 已删除 `ViewGroupByT2` / `TableVirtualT4` / `CalendarT3` 三个纯类型·算术测试文件 |

**门禁现状**：`mvn` 1277（+15）、`vitest` 362、`tsc` 0 ✅；`playwright` / `pytest` 本轮未跑。

---

## 二、必补任务（本轮）

### R1-A（P0）字段类型映射接生产 —— 落点已确认，不要再改错地方

**真相**：字段类型 → 物理列的映射**不在** `FieldDef`（它只有 `isValidType`/`of`），**也不在** `DynamicTableManager`（`addPhysicalColumn` 的 `columnType` 由调用方传入）。

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

12 种新类型**全部不在其中**，而 `default` 直接抛异常 →
**当前创建任何新类型字段（email / currency / rating / autonumber …）都会直接失败**。

**要求**：在 `mapJsonbType` 补齐（建议）：

| 类型 | 列类型 |
|---|---|
| `email` / `url` / `phone` | `TEXT` |
| `currency` / `percent` | `NUMERIC`（percent 存小数） |
| `duration` / `rating` | `INTEGER`（duration 存分钟） |
| `createdTime` / `lastModifiedTime` | `TIMESTAMPTZ` |
| `createdBy` / `lastModifiedBy` | `UUID` |
| `autonumber` | `BIGINT` |

同时确认 `ALTER_TYPE` 分支（`:143`）也走同一映射。

**验收**：写一个**直接调用 `mapJsonbType`（生产方法）** 的用例覆盖 12 种类型；并给出**真实建表证据**（建一个含新类型字段的集合，贴 `\d` 或建表 SQL）。不接受"临时 main 方法"的模拟验证。

### R1-B（P1）校验与自动字段维护接入写入链路

**落点**：`CollectionService.insertRecord(String collectionName, Map<String,Object> data, String tenantId)`（`:242`），以及对应的更新方法（记录写入链路入口为 `CollectionController.createRecord` `:241`）。

**要求**：

1. **格式校验**：`email` / `url` / `phone` 在插入/更新时校验，非法值返回明确错误（不要静默接受）。
2. **自动字段维护**：
   - `createdTime` / `createdBy` → 插入时由系统写入
   - `lastModifiedTime` / `lastModifiedBy` → 更新时由系统维护
   - 这几类 + `autonumber` **拒绝手工写入**（传入时应忽略或报错，二选一并说明）
3. **`autonumber` 并发安全**：用数据库序列（或带锁自增）。**禁止"先查 max 再 +1"**（并发必重号）。

**验收**：自动字段插入/更新后值正确且手工写入被拒；`autonumber` **并发插入不重复**（必须有用例：并发 N 次后编号集合无重复）；后端测试数增长。

### R2-B（P1）分组聚合接后端（补齐红线 3）

**现状**：分组与聚合在前端内存计算，未调用后端 `DynamicTableManager.aggregate` —— 违反红线 3（你已自述）。

**要求**：

1. 调用 `DynamicTableManager.aggregate(String collectionName, List<String> groupByFields, List<AggSpec> aggSpecs, List<CollectionService.FilterRule> filters)`（`:242-246`，已是 SQL 下推 GROUP BY）。
   - 若前端拿不到该方法，需新增 Controller/Service 端点在其上包装（**不得重写聚合逻辑**）。
2. 前端 `TableView` / `GalleryView` 改为消费该接口结果渲染分组头与聚合值；保留折叠/展开与组内排序。
3. 保留前端分组作为**降级路径**（接口失败时），但默认走后端。

**验收**：**接口返回的分组 count / sum 与数据库 `GROUP BY` 查询结果逐一一致**（贴 SQL 与接口输出对比）；反向用例：不可分组字段提示、空结果不渲染、分组字段被删不崩。

### R4-B（P0）测试必须绑定真实组件

**现状**：新增的 `TableViewGroupByR2.test.tsx` / `TableVirtualR3.test.tsx` 中 grep 不到 `render(` / `screen.` / `@testing-library` —— 仍是"把组件里的算法复制到测试里再断言"，未渲染真实组件。

**要求**：

1. 用 Testing Library **渲染真实 `TableView` / `GalleryView`**（必要时封装测试入口组件，但必须是**真实组件代码**，不是复制的算法）。
2. 断言落在 **DOM**：分组头文本、`count`、`sum` 实际出现在文档中；虚拟化下 DOM 行数远小于总行数。
3. **判据（必答）**：把实现回退（移除 `groupByField` / `virtualizer`）后，这些测试**必须失败**。请在回报中明确说明这一点是否成立。
4. 删除任何仅做算术推演、仅断言类型的残留用例。

### R6（P1）补齐未跑的两项门禁

- `npx playwright test` ≥ **64**
- `pytest`：**`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`** → 基线 **43 passed, 1 skipped**
  （注意：不加 `PYTHONPATH=src` 会导致大量收集失败，只跑出十几个用例，那是子集不是全量）

---

## 三、非阻塞建议（可选，不做不算失败）

`TableView.tsx:138` `shouldVirtualize = !groupByField && ...` → 分组模式下虚拟化被禁用，大数据分组展开仍有性能风险。可：在**分组内**再做一层虚拟化，或在 UI 明确标注该限制。

## 四、门禁基线

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1277**（R1-A / R1-B 必须新增用例）/ 0 failures / 0 errors |
| `npm run test:run` | **> 362**，新增必须是**渲染真实组件**的用例 |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（全量，`PYTHONPATH=src`） |

## 五、红线（违反即打回）

1. **必须按给定落点实施** —— R1-A 改 `AsyncMigrationService.mapJsonbType`；R1-B 接 `CollectionService.insertRecord`；R2-B 调 `DynamicTableManager.aggregate`。改错位置等于没改。
2. **测试必须渲染真实组件**（R4-B），禁止复制算法的"影子测试"。
3. **分组必须复用 `aggregate`**，禁止另写聚合（红线 3）。
4. **严禁 mock 被测主路径 Service**。
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值。
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`。
7. **严禁**回滚已闭环提交（PHASE58–63）；**严禁**回退 §1 已完成项。
8. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar。
9. **每项必须给出实测输出**（建表 SQL / 接口对比 / DOM 行数 / 并发编号集合 / 门禁数字）。

## 六、教训

1. **"类型合法" ≠ "能用"** —— `isValidType` 通过只是第一层，建列映射（`mapJsonbType`）、校验、自动维护缺一不可。
2. **追到真正的实现落点** —— 不能只看"看起来相关"的类，要顺调用链追到**实际被调用的那个方法**（本次：`addPhysicalColumn` 的 `columnType` 来自调用方 → 调用方用 `mapJsonbType`）。
3. **测试不看数字看绑定** —— 362 个用例若都不渲染组件，等于 0 保障。判据永远是**回退实现必须失败**。
4. **模拟验证不算实测** —— 临时 `main` 方法跑出来的映射结果，不能证明生产路径生效。
5. 沿用：容器 `localhost` 陷阱、`@DataJpaTest` 加 `@ActiveProfiles("test")`、分页先过滤再分页、clean 构建、grep 字符串常量加 `-i`。

## 七、交付清单（缺项打回）

1. 每项实测输出：
   - R1-A：`mapJsonbType` 覆盖 12 种 + **真实建表证据**
   - R1-B：格式校验、自动字段拒绝手工写入、`autonumber` 并发不重复（贴编号集合）
   - R2-B：接口 vs 数据库 `GROUP BY` 逐一一致
   - R4-B：**回退实现后测试是否失败**（必须明确回答）
   - R6：playwright、pytest 全量数字
2. 五项门禁实际输出
3. 改动文件清单 + `git log --oneline`（**必须已提交**）
4. 明确说明哪些项未做及原因
