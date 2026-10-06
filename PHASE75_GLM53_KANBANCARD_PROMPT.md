# PHASE75 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE75：两件事 —— 误报率抽样 + 看板卡片字段接线

> 项目经理说明：本批**只有两件事**，范围严格限定，刻意压缩。
> 前 14 个批次的复盘结论是：每批 4–5 项 → 每项都停在"看起来做了"。
> 本批改为**一次做透、测透、交透**。请严格遵守范围，不要顺手改别的东西。

## §0 工作目录与环境速查

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React + MUI
- 工作目录即仓库根目录

| 事项 | 正确做法 |
|---|---|
| **类型检查（先跑这个）** | `cd frontend && npx tsc --noEmit`（几秒） |
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**，不能下载新依赖） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| **多行提交信息** | **必须** `git commit -F - <<'EOF' … EOF`；不要用 `-m "...\n..."`（bash 不解释 `\n`，`&&` 会被命令替换吃掉） |

---

## 任务 1（P1，先做）：租户审计器误报率抽样实测

### 为什么先做

PHASE74 报出 **134 处**租户归属校验缺失，但**没人知道其中多少是真越权**：
- 误报率 90% → 审计器只是噪音，得先调规则
- 误报率 10% → 约 120 处真实安全债，优先级应高于一切新功能

不搞清楚这个，后面所有优先级排序都是猜的。

### 要做什么

1. 从审计器输出的违规清单里**抽样 20 处**（建议按包分层：Controller 10 + Service 10）
2. **逐处人工看代码**，判定二选一：
   - **真越权**：确实访问租户数据，且路径上无任何归属校验 → 其他租户能读到/改到
   - **误报**：实际已有租户隔离（查询条件含 tenant_id、上游已校验、
     或该接口本就不涉及租户数据）
3. **每处必须贴代码证据**：文件路径 + 行号 + 关键代码片段（1–3 行）

### 产出：写进 `docs/tenant-isolation-audit-sample.md`

- 分类表：20 行，每行 = 文件 / 方法 / 判定 / 证据
- 结论：**真越权 X / 20，误报 Y / 20**，并据此推断 134 处的总体分布
- 建议：
  - 误报率 > 50% → 给出**规则改进方案**（改哪条、改后预计减少多少）
  - 误报率 ≤ 50% → 给出**修复优先级**（先修哪几类）

### 验收（缺一即不合格）

- ✅ 20 处**全部**有代码证据（路径 + 行号 + 片段）
- ✅ 结论是**实测统计**，**不得出现"推断""应该是""大概"**
- ❌ 只给"误报约 N 处"汇总而无逐条证据 → 判不合格
- 💡 某处实在看不准 → 单独标"待确认"列出，**不要混入统计**

---

## 任务 2（P0，主体）：看板卡片 Checklist + dueDate 接线

### 范围严格限定

**只做 Checklist 和 dueDate 两个字段。Label 不做**（已渲染为 Chip）。
不要顺手改别的东西。

### 现状（已实测，不用再调研）

后端齐全 ✅ `backend-java/src/main/java/com/nocobase/project/`：

```
CardEntity / CardChecklistEntity / CardChecklistItemEntity / CardLabelEntity
+ 各自 Repository
CardMoveService（拖拽落库，已实测）
ProjectBoardController:161  POST /checklists
                     :185  GET  /checklists/by-task/{taskId}
                     :203  PUT  /checklists/{checklistId}
```

前端缺 ❌ `frontend/src/features/project/BoardColumn.tsx`：

```tsx
:27   dueDate?: string;        // 只有类型声明，无 JSX 使用
:28   labels?: string[];
:66   {card.labels?.map(...)}  // 只有 labels 被渲染
```

→ `checklist` 全文无渲染；`dueDate` 无渲染。

### T1 Checklist 接线

- 卡片详情展示 Checklist 及条目
- **勾选必须落库**：调后端 `PUT /checklists/{checklistId}`
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

## 门禁基线（全部满足并贴输出）

| 门禁 | 要求 |
|---|---|
| `cd frontend && npx tsc --noEmit` | **0** |
| `cd backend-java && mvn -o test` | **> 1337** |
| `cd frontend && npm run test:run` | **> 377** |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## 红线

既有红线全部有效（禁 stub 主路径 / 禁 skip 或弱化断言 / 禁 `.env` 入库 /
禁回滚已闭环提交 / 每项须实测 / 禁 mock 被测主路径 / 禁 Flyway 用 `CONCURRENTLY` /
禁 fail-open / 严禁"只打日志"冒充完成 / 严禁修改已应用迁移 /
严禁禁用校验绕过问题 / 严禁"只加枚举不加渲染" / 严禁"有端点无界面" /
严禁删代码留悬空测试 / **严禁提交编译不过的改动** /
严禁"声明 hook 不使用" / 严禁"只出报告不接门禁" /
严禁审计器抓不住故意留的反例）。

本批特别强调三条：

1. 🚫 **严禁"只展示、不落库"** —— 勾选后**刷新页面**必须仍生效。
2. 🚫 **严禁"类型声明了但未使用"冒充接线** —— 只在 TS 接口里出现、无 JSX 渲染 = 未接。
3. 🚫 **严禁把未实测的结论写成实测** —— 任务 1 每条判定都要有代码证据。

---

## 本项目教训（择要）

1. **「后端有、用户够不着」已第 7 次出现**：PHASE62 集成市场、PHASE70 CRDT 服务、
   PHASE71 rollup 与字段类型、PHASE72 反向链接、**PHASE75 看板 Checklist/dueDate**。
   判据三问：**用户能否点得到？看得到？刷新后还在不在？**
2. **验证静态规则前先读清判定口径**（否则会误判规则无效）。
3. **MUI v9 的 `Drawer` 无 `PaperProps`**，用 `slotProps={{ paper: {...} }}`。
4. **`useMediaQuery` 首帧返回 false**，移动端断点断言用 `toHaveCSS`（自带重试）。
5. **验收第一步跑 `tsc`**（几秒），立刻抓出"改了但编译不过"的交付。
6. **下"没有"的结论前要换搜索维度**（compose 环境变量 / 前端直接 fetch /
   自绘底部导航不含 `BottomNavigation` 字符串）。
7. **PostgreSQL `jsonb_set` 只能创建最后一级键**：`{data,id}` 无效，`{id}` 才生效。
8. **只服务于构建/测试期的工具类不要放 `src/main`**（会进生产 jar + 分发敏感清单）。

---

## 回报清单（缺项会被打回）

1. `docs/tenant-isolation-audit-sample.md`：20 处分类表 + 结论 + 建议
2. 卡片字段接线代码 + **六步端到端实测原始输出**
3. 五项门禁实测输出
4. 提交并推送（`git log --oneline` + `git status` 干净）
5. 未做项说明（Label 明确写"本批范围外"）

-----END PROMPT-----
