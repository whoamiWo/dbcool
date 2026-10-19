# PHASE71 任务书 — 字段类型接线：把后端已支持的 20+ 种类型交给用户

> 选题依据：`COMPREHENSIVE_PLATFORM_ASSESSMENT.md` §五 P1-11（字段类型扩展，
> 8–12 人日）+ L414 推荐顺序（`P1-11 字段类型 → P1-12 视图分组 → …`）
> 前置核实：P1-12（视图 group by / 日历翻月）**已完成**（`KanbanView:45-65`
> 按 groupBy 分组；`CalendarView:11/33-39` 有 `currentMonth` 且按月查询），本批不做。
> 本批做 **P1-11**，但审计后发现真正的问题**不是"引擎缺失"，而是"后端有、前端没暴露"**。

---

## §0 现状审计（CodeBuddy 实测，含行号证据）

### 0.1 核心发现：后端支持 20+ 种，前端只让用户选 11 种

**后端类型映射**（`backend-java/src/main/java/com/nocobase/meta/AsyncMigrationService.java:151-160`）：

```java
case "text", "select", "multiSelect", "email", "url", "phone"   -> "TEXT";
case "number", "currency", "percent"                            -> "NUMERIC";
case "boolean"                                                  -> "BOOLEAN";
case "date", "datetime", "createdTime", "lastModifiedTime"       -> "TIMESTAMPTZ";
case "attachment"                                               -> "TEXT";
case "belongsTo", "hasMany", "createdBy", "lastModifiedBy"       -> "UUID";
case "formula"                                                  -> "TEXT";
case "duration", "rating"                                       -> "INTEGER";
case "autonumber"                                               -> "BIGINT";
default -> throw new IllegalArgumentException("不支持的字段类型：" + type);
```

派生字段（不落物理列）在 `FieldDef.java:71`：

```java
case "formula", "rollup", "lookup" -> true;   // 派生字段
```

**前端可选清单**（`frontend/src/pages/SchemaDesigner.tsx:8-11`）只有 11 种：

```ts
const FIELD_TYPES: FieldType[] = [
  'text', 'number', 'boolean', 'date', 'datetime',
  'select', 'multiSelect', 'attachment',
  'belongsTo', 'hasMany', 'formula'
];
```

**用户完全选不到**（后端已支持但前端未暴露）：

| 类别 | 类型 |
|---|---|
| 格式化文本 | `email`、`url`、`phone` |
| 数值展示 | `currency`、`percent` |
| 输入控件 | `rating`、`duration` |
| 自动字段 | `autonumber`、`createdTime`、`lastModifiedTime`、`createdBy`、`lastModifiedBy` |
| **派生字段** | **`rollup`、`lookup`** |

### 0.2 更可惜的是：rollup 引擎已经写好且真接线了

- `backend-java/src/main/java/com/nocobase/meta/rollup/RollupEngine.java` 存在
- `backend-java/src/main/java/com/nocobase/meta/CollectionService.java:569`：

```java
if ("rollup".equals(f.type())) { ... }     // 读取时求值派生字段
```

即 **rollup 的后端能力完备且已接线，只是前端没有创建入口 → 用户零可用**。
这与 PHASE62「集成市场后端完整、前端 0 接线」是同一类缺口。

### 0.3 前端渲染基本不按类型分支

`frontend/src/pages/CollectionDetail.tsx:66` 是**唯一**一处按类型处理：

```ts
data[f.name] = f.type === 'number' ? Number(v) : v;
```

→ 暴露新类型时必须同时处理：输入控件、展示格式化、排序/筛选的值语义。
只往 `FIELD_TYPES` 里加字符串而不补渲染/校验，等于给用户一个**看起来能选、
实际用不了**的类型（红线）。

### 0.4 已完成、本批不动

| 项 | 证据 |
|---|---|
| P1-12 看板分组 | `KanbanView.tsx:45-65` 按 `config.groupBy` 分组渲染 ✅ |
| P1-12 日历翻月 | `CalendarView.tsx:11` `currentMonth` state + `:33-39` 按月查询（非硬编码当前月）✅ |
| formula 引擎 | `meta/formula/FormulaEngine.java` 存在，`CollectionService:485` 读取时求值 ✅ |

---

## §1 任务范围

### T1（P0）消除前后端类型清单漂移

**推荐方案 A（首选）**：后端暴露字段类型元数据接口，前端动态渲染，
从根上消除"两边各写一份清单"导致的漂移：

- 新增 `GET /api/meta/field-types`，返回每种类型的：
  `name`、`label`、存储类型、**是否需要额外配置**（配置 schema）、是否只读（派生/自动字段）
- 前端 `SchemaDesigner` 改为从该接口拉取，不再硬编码 `FIELD_TYPES`
- 对需要配置的类型提供配置 UI：
  - `select` / `multiSelect`：选项列表
  - `rollup`：目标关系字段 + 聚合函数（count / sum / avg / min / max）
  - `lookup`：目标关系字段 + 引用字段
  - `currency`：货币符号/精度
  - `rating`：最大值（默认 5）
- 自动字段（`createdTime` / `lastModifiedTime` / `createdBy` / `lastModifiedBy` /
  `autonumber`）：在 UI 中标注"自动填充、不可编辑"

**方案 B（次选）**：前端硬编码对齐后端映射 —— **必须同时加契约测试**
（断言前端枚举 ⊆ 后端 `AsyncMigrationService` 支持的类型），否则漂移必然复发。

### T2（P0）每种类型端到端实测（红线：必须贴输出）

对**每一种**暴露出来的类型，实测完整链路：

1. 用该类型建集合
2. 写入一条记录（自动字段验证**不写入也自动填充**）
3. 读回，验证值与格式正确
4. 派生字段（`rollup` / `lookup`）：构造关联数据，验证聚合结果正确
   （如：A 表 3 条关联记录的金额之和 = rollup 字段值）

至少覆盖：`email`、`url`、`phone`、`currency`、`percent`、`rating`、`duration`、
`autonumber`、`createdTime`、`lastModifiedTime`、`createdBy`、`lastModifiedBy`、
`rollup`、`lookup`。

### T3（P1）渲染与交互补齐

- `rating`：星级控件（输入 + 展示）
- `currency` / `percent`：展示格式化（符号、小数位）
- `url`：渲染为可点击链接
- `email` / `phone`：输入校验（前端提示 + 后端校验）
- 派生 / 自动字段：在表单与表格中**只读**（避免用户手改后被覆盖产生困惑）
- 排序与筛选对数值类（`currency`/`percent`/`rating`/`duration`）按数值语义处理

### T4（P0）测试与交付

- 后端：类型映射单测（每种类型 → 预期列类型）+ 非法类型抛错
- 前端：`SchemaDesigner` 渲染各类型的配置 UI + 展示格式化
- 契约测试（方案 B 必做，方案 A 建议做）：前端可选类型 ⊆ 后端支持类型
- 端到端：T2 的实测（建议 Playwright 或 API 脚本，须贴输出）

---

## §2 范围边界（明确不做）

- 不改已完成的 P1-12（看板分组、日历翻月）
- 不重写 `FormulaEngine` / `RollupEngine`（已存在且已接线，只补创建入口与验证）
- 不做移动端响应式（P1-5，15–20 人日）
- 不新增后端**尚未支持**的类型（如签名、条码）；若确需新增，必须同时改
  `AsyncMigrationService` 映射 + 迁移 + 测试，并说明必要性

---

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1312**（基线 1312） |
| `cd frontend && npm run test:run` | **> 373**（基线 373） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ 66（基线 66） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## §4 红线（沿用既有 + 本轮新增）

既有红线继续有效（禁 stub 主路径、禁 skip/弱化断言、禁 `.env` 入库、
禁回滚已闭环提交、每项须实测、禁 mock 被测主路径、禁 Flyway 迁移用
`CONCURRENTLY`、禁手写 JSON 协议、禁 fail-open、严禁"只打日志"冒充完成、
严禁修改已应用迁移、严禁禁用校验绕过问题、严禁整篇替换冒充字符级协同）。

**本轮新增两条**：

1. 🚫 **严禁"只加枚举、不加渲染/校验"冒充字段类型完成**。判据：
   暴露的每种类型都必须走完「建 → 写 → 读 → 筛/排」实测；
   自动字段必须验证"不写入也自动填充"；派生字段必须验证聚合值正确。
2. 🚫 **严禁前后端各维护一份类型清单而无约束**。方案 B 必须配契约测试
   （前端枚举 ⊆ 后端映射）；推荐方案 A（单一数据源，前端从后端拉取）。

---

## §5 本项目教训（择要）

1. **"后端有、前端没接线"是本项目反复出现的缺口形态**：
   PHASE62 集成市场（后端完整 + 前端 0 页面）、PHASE70 的 `crdt.service.url`、
   本次 `rollup`（引擎 + 接线都在，仅缺创建入口）。判据：**追到用户能否操作**，
   不能只看后端有没有实现。
2. **清单类配置必须有单一数据源**：前端硬编码枚举与后端 switch 各自演进，
   漂移是迟早的事（本次漂移已导致 14 种类型用户不可见）。
3. **实现达标 ≠ 交付达标**（PHASE61/62/69/70 连续出现）：本批 T2 的端到端实测是硬要求。
4. **改根因，不改校验**（PHASE69）。
5. **判断"配置配了没"要查三处**（PHASE70 教训）：`application.yml` /
   `docker-compose.yml` environment / `.env` —— 只看一处会误判。

---

## §6 交付清单（回报必须包含）

1. **T1 代码证据**：类型清单来源（方案 A 的接口定义，或方案 B 的枚举 + 契约测试）
2. **T2 端到端实测输出**：每种类型「建 → 写 → 读 → 筛/排」的原始输出；
   自动字段自动填充的验证；`rollup` / `lookup` 聚合值正确的验证
3. **T3 渲染证据**：rating / currency / url 等的界面或组件测试输出
4. **全量门禁数字**：mvn / vitest / tsc / playwright / pytest 五项实测输出
5. **提交记录**：`git log --oneline` + `git status` 干净
6. **未做项说明**：哪些没做、为什么

---

## §7 一句话总结

**后端已经会做 20 多种字段，其中 rollup 汇总引擎连接线都接好了，
但前端只给了用户 11 个选项 —— 剩下的 14 种（含汇总、星级、货币、自动时间戳）
用户根本选不到。** 本批把它们接出来，并确保每一种都真的"建得了、写得进、
读得对、排得动"。
