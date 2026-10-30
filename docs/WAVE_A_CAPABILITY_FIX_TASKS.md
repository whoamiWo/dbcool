# Wave 0.5 · 能力断链修复 — 任务书（Wave A）

> 创建日期：2026-10-30　路线依据：[`PRODUCTIZATION_ROADMAP.md`](./PRODUCTIZATION_ROADMAP.md) §5 Wave 0.5
> 证据依据：[`PLATFORM_BENCHMARK_ANALYSIS_2026-10-30.md`](./PLATFORM_BENCHMARK_ANALYSIS_2026-10-30.md) §4（P0-1 ~ P0-6）
> 基线：`02b3a59`（实测于 2026-10-30）　工作量：约 8–12 人日
> 性质：**打通已建能力**（调用链与端点对齐），不新增业务语义；完成后 Wave 1 才能开工。

---

## 0. 全局红线（违反即判定不通过）

| # | 红线 | 说明 |
|---|---|---|
| R1 | 禁止弱化断言让测试变绿 | 不删用例、不加 `.skip`/`.todo`/`xit`、不放宽断言容差 |
| R2 | 禁止恒真 PASS | 新增契约测试必须可失败，且必须做「反向自检」（人为造一处路径漂移 → 必须红）并贴输出 |
| R3 | 禁止编造数字 | 报告里每个数字来自本次实测命令输出，标注 `实测于 YYYY-MM-DD @<commit>` |
| R4 | 不新增业务功能 | 只做端点对齐、鉴权修复、通道订阅、渲染修复；不新增字段/模块/端点语义（A-4、A-6 后端侧例外需在报告说明为何「对齐」而非「新增」） |
| R5 | 禁止 commit / push | 改完保持未提交；报告末尾写「commit & push：待你授权」 |
| R6 | 行号以实测为准 | 本文行号基于 2026-10-30 核对，漂移自行定位并说明 |
| R7 | 不谎报实测 | 每项必须贴真实请求/响应或测试输出；无环境则写「未实测」 |

---

## 1. 前置：确认基线能跑

```bash
cd /home/who/multistack-project && git log --oneline -1 && git status --short | head -10
cd backend-java && mvn -o verify 2>&1 | tail -5          # 期望 2585 run / 0F / 0E
cd ../frontend && npm run test:run 2>&1 | tail -8        # 期望 0 failed（5 errors 属 W0-T3，本批一并修）
```

若基线本身红且非本批引起：立即停止并报告。

---

## 2. 任务 A-1　块编辑器请求带 JWT（1 人日）

**问题**：`NotionStyleEditor.tsx` 内 4 处请求（自动保存/模板/反向链接/应用模板）用裸 `fetch`，
不带 `Authorization` → 后端 `@PreAuthorize("isAuthenticated()")` → 401。
证据：`frontend/src/components/wiki/NotionStyleEditor.tsx:337-375,401,433-435` vs `frontend/src/api/client.ts:148-157`。

**要求**：改用项目现有 `apiClient`（自动附 token）；保留失败 Alert 提示；确认请求体 DTO 不变
（`pageId/blocks/type/content/language/checked`，对应 `WikiController.java:478-500`）。

**验收（贴输出）**：
```bash
grep -n "fetch(" frontend/src/components/wiki/NotionStyleEditor.tsx   # 期望 0 命中（或仅注释）
# 登录态手动/脚本实测：POST /api/wiki/blocks/batch-upsert 返回 200，Block 树落库
```

---

## 3. 任务 A-2　CRDT 初始化通道 + update 字段（2 人日）

**问题**：
① 前端只订 topic，未订 `/user/queue/collab-init` → 后加入成员拿不到服务端初始文档；
② `RealtimeService` 合并成功分支 payload 只有 `state`，无 `update` → 前端 `if (!msg.update) return` 丢弃。
证据：`frontend/src/lib/stompClient.ts:108-129`、`frontend/src/features/realtime/CollabEditor.tsx:198-221`、
`backend-java/src/main/java/com/nocobase/realtime/RealtimeCollabController.java:47-63`、`RealtimeService.java:124-143`。

**要求**：stompClient 增加用户队列订阅（复用现有 JWT 握手）；合并分支 payload 补 `update` 字段
（或前端兼容 `state`，择一并在报告说明理由）；不改合并算法本身。

**验收**：两个浏览器窗口实测——后加入者能看到既有内容；A/B 交替输入均可见（截图或日志贴报告）。

---

## 4. 任务 A-3　IM Pin 路径对齐（0.5 人日）

**问题**：前端 `/im/messages/{id}/pin` vs 后端 `@PostMapping("/pins")`（`ImMessageController.java:69,86`）→ 404。
证据：`frontend/src/features/im/api.ts:203-207`。

**要求**：前端对齐后端路径（推荐）；路径含义（批量 vs 单条）以现有后端语义为准，不新建端点。

**验收**：置顶 / 取消置顶实测 200（贴请求/响应）。

---

## 5. 任务 A-4　阅后即焚回执端点对齐（0.5 人日）

**问题**：前端调 `POST /im/messages/{id}/burn/read`（`api.ts:261`），后端无 Mapping（仅字段 + 清理 Job）。
**要求**：后端补该只读型回执端点（落点 `ImMessageController`），复用现有阅后即焚字段语义；
同时补契约测试锁定。报告中说明：该端点是对齐前端既有调用（非新增业务语义）。

**验收**：端到端 200；未读焚毁消息计数变化正确。

---

## 6. 任务 A-5　Checklist API 路径对齐（0.5 人日）

**问题**：`frontend/src/features/project/api.ts:100-110` 写 `/projects/boards/...`，后端 `/api/project-boards/...`
（`ProjectBoardController.java:169-355`）→ 404。
**要求**：前端路径对齐；api.ts 中同组其它端点逐一核对。

**验收**：勾选清单项实测落库；`BoardView` 清单 UI 生效。

---

## 7. 任务 A-6　日历翻月 + 服务端过滤（1.5 人日）

**问题**：
① `CalendarView.tsx:175-186` 月视图硬编码 `new Date()`，翻月无效；
② 前端发 `field_gte=value`，后端只认 `field:op:value` 且 op 白名单无 `gte/lte`
（`CollectionController.java:455-469`）→ 服务端过滤形同虚设。

**要求**：月视图改用组件 state（`currentMonth`）驱动渲染；过滤二选一并说明理由：
( a ) 前端改发后端支持语法（不改后端）；( b ) 后端 op 白名单补 `gte/lte`（对齐既有 eq/gt/lt 语义，非新增能力）。
无论哪种，必须补契约测试。

**验收**：翻月正确渲染对应月份；切换月份时列表数据随服务端过滤变化（贴网络面板截图/日志）。

---

## 8. 任务 A-7　前端 5 个 unhandled errors 根因修复（1 人日）

**问题**：`npm run test:run` 输出 `Errors 5 errors`（实测 2026-10-30，首个源 ErDiagram.test.tsx）。
**要求**：逐个复现 → 修根因（定位方式/mock/组件逻辑）；**禁 skip、禁删用例**；
若是产品代码缺陷则修产品代码并在报告说明。

**验收**：`cd frontend && npm run test:run 2>&1 | tail -8` → `0 failed` 且 **`Errors 0`**（贴输出）。

---

## 9. 任务 A-8　前后端端点契约测试（1 人日）

**要求**：新增契约测试（前端侧 or Python 契约脚本均可，风格对齐 `scripts/api-contract-verify.py`），
锁定本批全部端点：`/api/wiki/blocks/batch-upsert`、`/api/wiki/kb/{kbId}/templates`、`/im/messages/pins`、
`/im/messages/{id}/burn/read`、`/api/project-boards/...`、日历数据端点。源：前端 api 层路径声明 × 后端 @Mapping 扫描。

**验收**：
```bash
python3 scripts/api-contract-verify.py        # 0 漂移
# 反向自检：临时改坏一条路径 → 必须红（贴输出）→ 还原
```

---

## 10. 全量门禁（交付前必须逐条贴输出）

同 `PRODUCTIZATION_ROADMAP.md` §6 全表（mvn verify / npm test:run 0 failed 0 errors / tsc / pytest /
launch-final-verify / smoke / api-contract-verify / tenant-isolation / version-consistency / java-dependency-audit）。
Playwright E2E：环境具备则跑（期望全过），否则写「未实测」。

## 11. 报告要求

1. 逐任务 ✅/❌ + 证据（命令输出原文）；每改动写 `文件:行号`，漂移需说明。
2. 明确列出「未实测」项。
3. 结尾写：`commit & push：待你授权`。
4. 任一门禁 FAIL：写清项、根因、是否已修；**不得**自行改成「✅ 已完成」。
