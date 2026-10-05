# PHASE75 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE75：看板卡片字段接线（Checklist / Label / dueDate）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React + MUI
- 你的工作目录即仓库根目录

### 项目环境速查（前人踩过的坑，直接照做可省很多时间）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线 —— 不能下载新依赖**） |
| 类型检查 | `cd frontend && npx tsc --noEmit`（几秒，验收第一步就跑） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| **多行提交信息** | **必须** `git commit -F - <<'EOF' … EOF`；不要用 `-m "...\n..."`（bash 不解释 `\n`，`&&` 会被命令替换吃掉） |

---

## §1 为什么做这项

选题来自 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md` §四维度 5（看板 / Trello）**L83**：
「✅ ✅ 🟡 —— 拖拽真落库；**Checklist / Label / dueDate 前端未接**」。

P1 全项 + 🔒-8 已完成，**本批不要动它们**。

### 1.1 后端能力齐全 ✅

`backend-java/src/main/java/com/nocobase/project/` 已有完整链路：

```
CardEntity / CardChecklistEntity / CardChecklistItemEntity / CardLabelEntity
+ 各自 Repository
CardMoveService（拖拽落库实测通过）
ProjectBoardController:161 POST /checklists
                     :185 GET  /checklists/by-task/{taskId}
                     :203 PUT  /checklists/{checklistId}
```

### 1.2 前端只接了一部分 ❌

`frontend/src/features/project/BoardColumn.tsx`：

```tsx
:27   dueDate?: string;          // 类型里有，但没渲染
:28   labels?: string[];
:66   {card.labels?.map(label => (   // 只有 labels 被渲染成 Chip
:67     <Chip key={label} label={label} … />
```

| 字段 | 后端 | 前端 |
|---|---|---|
| **Checklist** | ✅ 实体 + 3 个端点 | ❌ **未渲染**（文件内无 `checklist`） |
| **Label** | ✅ | ✅ 已渲染 |
| **dueDate** | ✅ | ❌ **未渲染**（仅 TS 声明） |

---

## §2 任务

### T1（P0）卡片 Checklist 接线

- 卡片详情展示 Checklist 及条目
- **勾选必须真的落库**（刷新页面仍生效），不是只改本地 state
- 展示进度（如 `3/5`）
- 支持新增/删除条目（若后端支持）

### T2（P0）卡片 dueDate 接线

- 卡片展示 dueDate（有值才渲染，无值不占位）
- **逾期高亮**（dueDate < 今天）
- 日期格式化统一（避免 `toLocaleString` 跨环境不一致）

### T3（P1）Label 可视化选择

- 确认 `labels`（`string[]`）当前是否可由用户编辑
- 若不可编辑：补"选择/新建标签"交互（含配色）
- 若可编辑：确认落库路径并补断言

### T4（P0）端到端实测（红线：必须贴输出）

容器内跑通：

1. 建项目 / 列表 / 卡片
2. 给卡片加 Checklist 条目
3. 勾选一条 → **刷新页面后仍为已勾选**
4. 设 dueDate → 卡片显示日期
5. 设过去日期 → 显示逾期高亮
6. 跨租户访问该卡片 → 403

### T5（P0）测试与交付

- 前端：checklist 渲染与勾选、dueDate 渲染与逾期高亮
- 后端：checklist CRUD 落库断言
- 提交并推送

---

## §3 范围边界（明确不做）

- 不做 🔒-9 灰度发布与回滚（需 K8s，本机无）
- 不做钉钉免登 JS-SDK / 消息卡片（需真钉钉环境）
- 不改后端租户隔离方案
- 不做卡片拖拽增强（已真落库）

---

## §4 门禁基线（必须全部满足并贴输出）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1337**（基线 1337） |
| `cd frontend && npm run test:run` | **> 377**（基线 377） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## §5 红线

既有红线继续有效：禁 stub 主路径 / 禁 skip 或弱化断言 / 禁 `.env` 入库 /
禁回滚已闭环提交 / 每项须实测 / 禁 mock 被测主路径 /
禁 Flyway 迁移用 `CONCURRENTLY` / 禁 fail-open / 严禁"只打日志"冒充完成 /
严禁修改已应用迁移 / 严禁禁用校验绕过问题 /
严禁"只加枚举不加渲染"冒充完成 / 严禁"有端点无界面"冒充完成 /
严禁删代码留悬空测试 / **严禁提交编译不过的改动** /
严禁"声明 hook 不使用"冒充适配 / 严禁"只出报告不接门禁" /
严禁审计器抓不住故意留的反例。

**本轮新增两条**：

1. 🚫 **严禁"只展示、不落库"**。判据：勾选后**刷新页面**必须仍生效。
2. 🚫 **严禁"类型声明了但未使用"冒充字段接线**。判据：`dueDate` 只在 TS
   接口里出现而无 JSX 渲染 = 未接。

---

## §6 本项目教训（择要）

1. **「后端有、用户够不着」已第 7 次出现** —— 判据是追到用户能否点得到、
   看得到、**刷新后还在不在**（本次新增第三问）。
2. **类型声明 ≠ 接线**：`dueDate?: string` 写进接口但无渲染 = 没接。
3. **验证静态规则前先读清判定口径**（否则会误判审计器无效）。
4. **MUI v9 的 `Drawer` 无 `PaperProps`**，用 `slotProps={{ paper: {...} }}`。
5. **`useMediaQuery` 首帧返回 false**，移动端断点断言用 `toHaveCSS`。
6. **验收第一步跑 `tsc`**（几秒）。
7. **下"没有"的结论前要换搜索维度**。
8. **PostgreSQL `jsonb_set` 只能创建最后一级键**：`{data,id}` 无效。
9. **只服务于构建/测试期的工具类不要放 `src/main`**（会进生产 jar + 分发敏感清单）。

---

## §7 回报清单（必须包含，缺项会被打回）

1. **卡片字段接线证据**：Checklist 渲染 + **勾选落库后刷新仍生效**的实测输出
2. **dueDate 证据**：日期渲染 + 逾期高亮实测
3. **Label 交互**（若做了）：选择/新建 + 落库断言
4. **端到端实测输出**：§2 T4 的 6 步全流程原始输出（含跨租户 403）
5. **全量门禁数字**：五项实测输出
6. **提交记录** + `git status` 干净
7. **未做项说明**：不许"做了不说"，也不许"没做装做"

-----END PROMPT-----
