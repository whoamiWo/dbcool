# PHASE75 执行任务书（项目经理版）

> 本批**只有两件事**，且范围严格限定。
> 这是我基于前 14 个批次的复盘做的调整：**一次一件事，做透、测透、交透**。
> 每批 4–5 项的结果是每项都停在"看起来做了"，本批刻意压缩。

---

## 任务 1（P1，先做）：租户审计器误报率抽样实测

### 为什么先做这个

PHASE74 报出 **134 处**租户归属校验缺失，但**没人知道其中多少是真越权**：

- 误报率 90% → 审计器只是噪音，要先调规则
- 误报率 10% → 约 120 处真实安全债，优先级应高于一切新功能

不搞清楚这个，后面所有优先级排序都是猜的。抽样 20 处即可推断总体，成本极低。

### 要做什么

1. 从审计器输出的违规清单里**抽样 20 处**
   （建议按包分层：Controller 10 处 + Service 10 处；若清单不足则全取）
2. **逐处人工看代码**，判定二选一：
   - **真越权**：确实访问租户数据，且路径上无任何归属校验 → 其他租户能读到/改到
   - **误报**：实际已有租户隔离（查询条件含 tenant_id、上游已校验、
     或该接口本就不涉及租户数据）
3. **每处必须贴代码证据**：文件路径 + 行号 + 关键代码片段（1–3 行）

### 产出（写进 `docs/tenant-isolation-audit-sample.md`）

- 分类表：20 行，每行 = 文件 / 方法 / 判定 / 证据
- 结论：**真越权 X / 20，误报 Y / 20**，并据此推断 134 处的总体分布
- 建议：
  - 若误报率 > 50% → 给出**规则改进方案**（改哪条、改后预计减少多少）
  - 若误报率 ≤ 50% → 给出**修复优先级**（先修哪几类）

### 验收（可判定，缺一即不合格）

- ✅ 20 处**全部**有代码证据（文件路径 + 行号 + 片段）
- ✅ 结论是**实测统计**，**不得出现"推断""应该是""大概"** 这类词
  （PHASE74 你主动标注了这点，很好 —— 本批就要把它变成实测数字）
- ❌ 若只给一个"误报约 N 处"的汇总而无逐条证据 → 判不合格

---

## 任务 2（P0，主体）：看板卡片 Checklist + dueDate 接线

### 范围严格限定

**只做 Checklist 和 dueDate 两个字段。Label 不做**（已渲染为 Chip，下批再说）。
不要顺手改别的东西 —— 改了反而增加返工风险。

### 现状（已实测，不用再调研）

后端齐全 ✅：

```
backend-java/src/main/java/com/nocobase/project/
├── CardEntity / CardChecklistEntity / CardChecklistItemEntity / CardLabelEntity
├── + 各自 Repository
├── CardMoveService（拖拽落库，已实测）
└── ProjectBoardController
    :161  POST /checklists
    :185  GET  /checklists/by-task/{taskId}
    :203  PUT  /checklists/{checklistId}
```

前端缺 ❌（`frontend/src/features/project/BoardColumn.tsx`）：

```tsx
:27   dueDate?: string;      // 只有类型声明，无 JSX 使用
:28   labels?: string[];
:66   {card.labels?.map(...)}   // 只有 labels 被渲染
```

→ `checklist` 全文无渲染；`dueDate` 无渲染。

### T1 Checklist 接线

- 卡片详情展示 Checklist 及条目
- **勾选必须落库**：调用后端 `PUT /checklists/{checklistId}`
- 展示进度（如 `3/5`）

### T2 dueDate 接线

- 卡片展示 dueDate：**有值才渲染**，无值不显示占位
- **逾期高亮**（dueDate < 今天）
- 日期格式化统一（不要直接用 `toLocaleString`，跨环境不一致）

### T3 端到端实测（必须贴原始输出）

容器内跑通六步：

1. 建项目 / 列表 / 卡片
2. 给卡片加 Checklist 条目
3. 勾选一条 → **刷新页面后仍为已勾选**
4. 设 dueDate → 卡片上显示日期
5. 设一个**过去**的 dueDate → 显示逾期高亮
6. 跨租户访问该卡片 → **403**

---

## 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd frontend && npx tsc --noEmit` | **0**（先跑，几秒） |
| `cd backend-java && mvn -o test` | **> 1337** |
| `cd frontend && npm run test:run` | **> 377** |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

## 红线（沿用 + 本批强调）

既有红线全部继续有效。本批特别强调三条：

1. 🚫 **严禁"只展示、不落库"** —— 勾选后**刷新页面**必须仍生效。
   只改组件 state = 未实现（重进就丢）。
2. 🚫 **严禁"类型声明了但未使用"冒充接线** —— `dueDate` 只在 TS 接口里
   出现而无 JSX 渲染 = 未接。
3. 🚫 **严禁把未实测的结论写成实测** —— 任务 1 的每条判定都要有代码证据。
   （若某处确实看不准，就标注"待确认"并单独列出，不要混入统计。）

## 交付清单

1. `docs/tenant-isolation-audit-sample.md`（20 处分类表 + 结论 + 建议）
2. 卡片字段接线代码 + **六步端到端实测原始输出**
3. 五项门禁实测输出
4. 提交并推送（`git log --oneline` + `git status` 干净）
5. 未做项说明（尤其是 Label，明确写"本批范围外"）
