# Phase52 第五轮返工提示词（GLM-5.3 执行版 H1）

> 编排方：CodeBuddy(HY4)　执行方：GLM-5.3　复审计方：CodeBuddy
> 生成时间：2026-09-22　基线提交：`b56909d`（G2-R 已交付并通过复审计）
> 前置文档：`PHASE52_GLM53_PROMPT.md`（T1–T10）、`REWORK`（R1–R9）、`REWORK2`（F1–F7）、`REWORK3`（G1–G2）、`REWORK4`（G2-R）

---

## 0. 背景：Phase52 功能收口已完成，剩余**全量测试污染**是唯一红灯

第四轮复审计结论：**G2-R ✅ 通过**，Phase52 功能收口闭环：

| 项 | 结果 |
|---|---|
| Java `mvn test` | **1075 PASS / 0 fail / BUILD SUCCESS** ✅ |
| `tsc --noEmit` | **0 errors** ✅ |
| Python `compileall` | **OK** ✅ |
| G1 端点契约 / G2-R 内容回显 | **均已通过复审计** ✅ |
| **vitest 全量** | **98 failed / 152 passed（250 用例，26 文件失败）** ❌ **唯一红灯** |

### 关键定性（我已实测证明，这是污染不是功能缺陷）

| 验证 | 命令 | 结果 |
|---|---|---|
| 全量失败文件**作为子集跑** | 26 个失败文件一起跑 | **181/181 全通过，0 失败** ✅ |
| 7 个"新增失败"文件**单独跑** | 逐个/小批跑 | **27/27 通过** ✅ |
| 同 commit 全量**跑两次** | `npx vitest run --pool=forks --poolOptions.forks.singleFork` | **两次均 98 失败**（可复现，非随机抖动） |

> **结论**：这些测试**单独/小子集全部通过**，只在全量并发下失败 →
> 是**跨文件污染**（共享 jsdom 全局 / 模块级单例 / 未清理的定时器或 mock），
> **不是产品功能缺陷**，也**不是** G2-R 引入的回归。

**本轮目标**：让 `npx vitest run`（**默认配置，不加任何 pool 参数**）达到 **0 failed**，
且不删、不 skip、不弱化任何测试。

---

## 1. 全局约定

### 1.1 五条红线（重申）
1. **禁止 `log.info` + `// TODO` 冒充接真**；未配置外部服务返回明确错误（禁空 catch 吞异常）。
2. **禁止臆造 API**：调用任何符号前确认真实存在与签名。
3. **前端禁硬编码亮色**：一律 `var(--color-*)` + MUI `sx`。
4. **迁移版本号从 V35 起**；本轮不改 schema。
5. **不引入重型依赖**。

### 1.2 本轮特别禁令（**最重要，违反即判 FAIL**）
- ❌ **禁止删除测试文件、禁止 `it.skip` / `it.todo` / `describe.skip`**。
- ❌ **禁止弱化断言**（不得把 `findByText('Hello')` 之类改成更松的查询来"变绿"；
  只允许**把歧义选择器改精确**，例如加 `getByRole(..., {name})` 或 `within()` 限定范围）。
- ❌ **禁止靠改 `test-setup.ts` 打补丁掩盖**（第三轮已证明无效）。
- ✅ **允许且推荐**：改 `vite.config.ts` 的 test 配置（隔离策略）、补全局 `afterEach` 清理、
  消除模块级可变单例、把歧义选择器改精确。

### 1.3 门禁命令
```bash
# 本轮唯一硬指标：默认命令全绿
cd frontend && npx vitest run --reporter=verbose > /tmp/h1.log 2>&1
grep -cE '^\s*× ' /tmp/h1.log     # 必须为 0
grep -E "Test Files|Tests " /tmp/h1.log | tail -2   # 须看到汇总行（否则视为未跑完）

# 守卫（不得劣化）
cd frontend && npx tsc --noEmit                       # 0 errors
cd frontend && npx vitest run src/api/endpoints.contract.test.ts   # 11/11
cd frontend && npx vitest run src/pages/wiki/WikiPageEdit.test.tsx # 3/3
cd backend-java && mvn test 2>&1 | grep -E "Tests run:|BUILD" | tail -3   # 1075
```

### 1.4 提交规范
按栈分提交：`[java]` / `[python]` / `[js]` / `[docs]`。本轮预计仅 `[js]`。

---

## 2. 返工任务

### H1（P0）定位并消除 vitest 全量跨文件污染

**失败文件清单（26 个，全量实测）**
```
src/components/AppLayout.test.tsx
src/components/forms/FieldRulesEditor.test.tsx
src/components/forms/FormRuntimeReadOnly.test.tsx
src/components/forms/FormSubmitAction.test.tsx
src/components/views/FilterBar.test.tsx
src/features/im/ChannelList.test.tsx
src/features/im/MessageList.test.tsx
src/pages/AuditLogs.test.tsx
src/pages/CollectionsList.test.tsx
src/pages/FormsList.test.tsx
src/pages/Home.test.tsx
src/pages/Login.test.tsx
src/pages/MessagesInbox.test.tsx
src/pages/MyTasks.test.tsx
src/pages/NotificationChannels.test.tsx
src/pages/Profile.test.tsx
src/pages/RolesList.test.tsx
src/pages/UsersList.test.tsx
src/pages/ViewsList.test.tsx
src/pages/wiki/KnowledgeBaseList.test.tsx
src/pages/wiki/WikiCategoryManagement.test.tsx
src/pages/wiki/WikiPageEdit.test.tsx
src/pages/wiki/WikiPageList.test.tsx
src/pages/wiki/WikiPageRead.test.tsx
src/pages/wiki/WikiSearch.test.tsx
src/pages/wiki/WikiVersionHistory.test.tsx
```

**典型失败形态**
- `Found multiple elements with the text/role ...`（选择器歧义，多文本节点/多按钮同名）
- `expect(element).not.toBeInTheDocument()` 却找到了残留节点（上一个文件未清理的 DOM）
- 用例耗时异常拉长（全量中 2000–4700ms，子集里仅数十 ms → 说明在等待被污染的挂起 Promise/定时器）

**排查顺序（按此执行，先定位载体再动手）**

1. **确认隔离策略是否生效**：`vite.config.ts` 的 `test` 段当前为
   `globals: true, environment: 'jsdom', setupFiles: ['./src/test-setup.ts']`，
   **未显式设置 pool / isolate**。默认走 threads 共享环境。
   → 先试 `pool: 'forks'`（或 `isolate: true` / `sequence: { concurrent: false }`），
   看失败数是否骤降；这会直接验证"是否共享环境导致污染"。
2. **用二分法定位污染载体**：把 26 个失败文件分批与"干净文件"混跑，
   找出**哪个文件跑过后会让后续文件失败**（真正的污染源，通常 1–3 个）。
3. **检查模块级可变单例**（高危，已列为嫌疑）：
   - `src/api/client.ts`：`isRefreshing / refreshSubscribers / refreshRequest` 模块级变量
   - STOMP 客户端单例（`src/lib/stompClient.ts` 之类）
   - zustand `useAuthStore` 的持久化/localStorage 残留
   - 任何 `vi.mock` 未 `vi.resetModules()`、`localStorage` 未在 `afterEach` 清空
4. **补齐全局清理**：在 `src/test-setup.ts` 加
   `afterEach(() => { cleanup(); localStorage.clear(); vi.clearAllTimers(); })`
   （注意：这是**清理**而非掩盖，与第三轮"给 fetch 打补丁"性质不同）。
5. **修歧义选择器**：对仍然失败的用例，用 `getByRole(name)` / `within(container)` /
   `findAllBy*` + 索引 精确化，**保持断言强度不变**。

**验收（硬指标，缺一不可）**
```bash
cd frontend && npx vitest run --reporter=verbose > /tmp/h1.log 2>&1
#   1) 必须看到 Test Files / Tests 汇总行（证明跑完）
#   2) grep -cE '^\s*× ' = 0
#   3) 测试总数不减少（当前 250；删/skip 会导致总数下降 → 判 FAIL）
cd frontend && npx tsc --noEmit       # 0 errors
cd frontend && npx vitest run src/api/endpoints.contract.test.ts    # 11/11
cd frontend && npx vitest run src/pages/wiki/WikiPageEdit.test.tsx  # 3/3
```
并在报告里贴出**修复前后**的汇总行对比（`Test Files` / `Tests`）。

---

## 3. 范围边界（**不要越界**）

- 本轮**只做 H1（测试污染清理）**，不要动产品功能代码（除非是为消除模块级单例而必需的改法，需说明）。
- 不要去"修"产品 bug 来让测试变绿；本轮性质是**测试基础设施**专项。
- Java / Python 本轮不动（已全绿），无需重跑，除非你改到了它们。

---

## 4. 附录 A — 我已实测的可复现证据

```bash
# 1) 全量（可复现 98 失败，两次一致）
cd frontend && npx vitest run --pool=forks --poolOptions.forks.singleFork
# → Test Files  26 failed | 6 passed (32)
# → Tests       98 failed | 152 passed (250)

# 2) 同 26 个失败文件作为子集跑 → 全通过（证明非功能缺陷）
cd frontend && npx vitest run <上述 26 个文件>
# → Test Files  26 passed (26)
# → Tests       181 passed (181)

# 3) 7 个"新增失败"文件单独跑 → 全通过
cd frontend && npx vitest run src/pages/CollectionsList.test.tsx src/pages/Home.test.tsx \
  src/pages/ViewsList.test.tsx src/pages/wiki/KnowledgeBaseList.test.tsx \
  src/pages/wiki/WikiPageEdit.test.tsx src/pages/wiki/WikiPageList.test.tsx \
  src/pages/wiki/WikiPageRead.test.tsx
# → Test Files 7 passed (7) / Tests 27 passed (27)
```

## 5. 附录 B — 方法论（写进长期记忆）

1. **"子集全通过、全量失败"= 污染**：判定回归时不能只看全量计数，
   必须把失败文件**单独/成组复跑**；能单独通过就不是功能缺陷。
2. **全量必须见到汇总行**：`Test Files|Tests` 汇总行缺失 = 没跑完，此时任何数字都无效。
3. **同 commit 跑两次**验证计数是否稳定；稳定后才可与基线比。
4. **禁止用删/skip 变绿**：验收要同时看"失败数=0"与"测试总数不减少"。
