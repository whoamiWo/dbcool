# PHASE75 任务书 — 看板卡片字段接线（Checklist / Label / dueDate）

> 选题依据：`COMPREHENSIVE_PLATFORM_ASSESSMENT.md` §四 维度 5（看板 / Trello）L83：
> 「✅ ✅ 🟡 —— 拖拽真落库；**Checklist / Label / dueDate 前端未接**」
>
> 前置：P1 全项 + 🔒-8 已完成，本批不动。
> 不做的（需真实外联环境，本机无法实测）：🔒-9 灰度发布与回滚（需 K8s）、
> 钉钉免登 JS-SDK 与消息卡片（需真钉钉环境）。

---

## §0 现状审计（CodeBuddy 实测）

### 0.1 后端能力是齐全的 ✅

`backend-java/src/main/java/com/nocobase/project/` 完整包含：

```
CardEntity / CardChecklistEntity / CardChecklistItemEntity / CardLabelEntity
BoardListEntity + 各自 Repository
CardMoveService（拖拽落库）
ProjectBoardController / ProjectController / ProjectService
```

`ProjectBoardController` 已有 Checklist 端点：

```
:161  @PostMapping("/checklists")
:185  @GetMapping("/checklists/by-task/{taskId}")
:203  @PutMapping("/checklists/{checklistId}")
```

### 0.2 前端只接了一部分 ❌

`frontend/src/features/project/BoardColumn.tsx`：

```tsx
:27   dueDate?: string;              // ← 类型里有，但…
:28   labels?: string[];
:66   {card.labels?.map(label => (      // ← 只有 labels 被渲染
:67     <Chip key={label} label={label} size="small" … />
```

即：

| 字段 | 后端 | 前端 |
|---|---|---|
| **Checklist** | ✅ 实体 + 3 个端点 | ❌ **未渲染**（`BoardColumn` 全文无 `checklist`） |
| **Label** | ✅ | ✅ 已渲染为 Chip |
| **dueDate** | ✅ | ❌ **未渲染**（仅类型声明，无 JSX 使用） |

### 0.3 这正是本项目的"老毛病"

又一次「**后端有、用户够不着**」（第 7 次）：
- PHASE62 集成市场（后端完整 + 前端 0 页面）
- PHASE70 CRDT 服务（引擎在、配置在，前端无法创建）
- PHASE71 rollup 与 14 种字段类型
- PHASE72 反向链接
- **PHASE75 看板 Checklist / dueDate**

---

## §1 任务

### T1（P0）卡片 Checklist 接线

- 卡片详情中展示 Checklist 及条目
- **勾选必须真的落库**（调 `POST/PUT /api/.../checklists`），不是只改本地 state
- 展示进度（如 `3/5`）
- 支持新增/删除条目（若后端支持）

### T2（P0）卡片 dueDate 接线

- 卡片上展示 dueDate（字段有值才渲染，无值不显示占位）
- **逾期高亮**（dueDate < 今天）
- 日期格式化统一（避免 `toLocaleString` 在不同环境不一致）

### T3（P1）Label 可视化选择

- 确认当前 `labels` 是否可由用户编辑（当前仅为 `string[]` 渲染）
- 若不可编辑：补"选择/新建标签"的交互（含配色）
- 若可编辑：确认落库路径并补断言

### T4（P0）端到端实测（红线：必须贴输出）

容器内跑通全流程：

1. 建项目 / 列表 / 卡片
2. 给卡片加 Checklist 条目
3. 勾选一条 → **刷新页面后仍为已勾选**（证明落库，而非本地 state）
4. 给卡片设 dueDate → 卡片上显示日期
5. 设一个过去的 dueDate → 显示逾期高亮
6. 跨租户访问该卡片 → 403

### T5（P0）测试与交付

- 前端：checklist 渲染与勾选、dueDate 渲染与逾期高亮
- 后端：checklist CRUD 落库断言
- 提交并推送

---

## §2 范围边界（明确不做）

- 不做 🔒-9 灰度发布与回滚（需 K8s 环境，本机无）
- 不做钉钉免登 JS-SDK / 消息卡片（需真钉钉环境）
- 不改后端租户隔离方案
- 不做卡片拖拽增强（已真落库）

---

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1337**（基线 1337） |
| `cd frontend && npm run test:run` | **> 377**（基线 377） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## §4 红线（沿用既有 + 本轮新增）

既有红线继续有效（禁 stub 主路径、禁 skip 或弱化断言、禁 `.env` 入库、
禁回滚已闭环提交、每项须实测、禁 mock 被测主路径、禁 Flyway 迁移用
`CONCURRENTLY`、禁 fail-open、严禁"只打日志"冒充完成、严禁修改已应用迁移、
严禁禁用校验绕过问题、严禁"只加枚举不加渲染"、严禁"有端点无界面"、
严禁删代码留悬空测试、**严禁提交编译不过的改动**、严禁"声明 hook 不使用"、
严禁"只出报告不接门禁"、严禁审计器抓不住故意留的反例）。

**本轮新增两条**：

1. 🚫 **严禁"只展示、不落库"**。判据：checklist 勾选后**刷新页面**必须仍为已勾选；
   只在组件 state 里改 = 未实现（重进就丢）。
2. 🚫 **严禁"类型声明了但未使用"冒充字段接线**。
   判据：`dueDate` 只在 TS 接口里出现而无 JSX 渲染 = 未接。
   （本项目已两次栽在这上面：PHASE70 的 `useIsMobile`、PHASE71 的整篇替换。）

---

## §5 本项目教训（择要）

1. **「后端有、用户够不着」已第 7 次出现**：PHASE62 集成市场、PHASE70 CRDT 服务、
   PHASE71 rollup 与字段类型、PHASE72 反向链接、PHASE75 Checklist/dueDate。
   判据：**追到用户能否点得到、看得到、刷新后还在不在**。
2. **类型声明 ≠ 接线**：`dueDate?: string` 写进接口但没渲染，等于没接。
3. **验证静态规则前先读清判定口径**（本次复现审计器：判定的是**方法体内出现
   实体类型名**，我前两次探针都因构造不对而未触发，差点误判"审计器无效"）。
4. **MUI v9 的 `Drawer` 无 `PaperProps`**，用 `slotProps={{ paper: {...} }}`。
5. **`useMediaQuery` 首帧返回 false**，移动端断点断言用 `toHaveCSS`（自带重试）。
6. **验收第一步跑 `tsc`**（几秒），立刻抓出"改了但编译不过"的交付。
7. **下"没有"的结论前要换搜索维度**（compose 环境变量 / 前端直接 fetch /
   自绘底部导航不含 `BottomNavigation` 字符串）。
8. **PostgreSQL `jsonb_set` 只能创建最后一级键**：`{data,id}` 无效，`{id}` 才生效。

---

## §6 交付清单（回报必须包含）

1. **卡片字段接线证据**：Checklist 渲染 + **勾选落库后刷新仍生效**的实测输出
2. **dueDate 证据**：日期渲染 + 逾期高亮实测
3. **Label 交互**（若做了）：选择/新建 + 落库断言
4. **端到端实测输出**：§1 T4 的 6 步全流程原始输出（含跨租户 403）
5. **全量门禁数字**：五项实测输出
6. **提交记录** + `git status` 干净

---

## §7 一句话总结

**看板拖拽是真落库的，但卡片上的 Checklist 和 dueDate 后端全都支持、前端一个都没渲染 ——
用户点开卡片看不到待办、看不到截止日。**
本批把这两块接到界面上，并且要求实测到「勾选后刷新页面仍然生效」。
