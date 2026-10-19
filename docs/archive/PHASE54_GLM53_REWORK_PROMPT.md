# Phase54 返工执行提示词（GLM-5.3 执行版 R7–R9）

> 编排方：CodeBuddy(HY4)　执行方：**GLM-5.3**　复审计方：CodeBuddy
> 生成时间：2026-09-25
> 前置：`PHASE53_GLM53_PROMPT.md`（W1–W6）。本轮为其**阻断项收口返工**。
> 工作区状态：R1–R5 改动**已落盘但未 commit**，接手后**严禁回滚**。

---

## 0. 背景：审计判定「阻断」，代码层已修、门禁未收口

CodeBuddy 于 2026-09-25 完成**实测审计**（抓包 + grep + git diff，非静态推断）。上一轮（GLM-5.3 的 W6/W7/W8）因「用测试分支掩盖生产缺陷」被判定阻断，已由审计方完成 R1–R5 返工：

| 项 | 状态 | 证据 |
|---|---|---|
| R1 生产代码净化（`client.ts` 两处 `__isE2E__` 污染） | ✅ 已完成 | `git diff frontend/src/api/client.ts` **空输出** = 已完整回滚至 HEAD |
| R2 接口路径归一（`wiki.ts` 21 处去 `/api` 前缀） | ✅ 已完成 | `wiki.ts` 中 grep `/api` **0 命中**；路径均为 `/wiki/kb`、`/wiki/pages`、`/wiki/search` 等 |
| R3 移除固定等待（`wiki-phase1` 无 `waitForTimeout`） | ✅ 已完成 | 改用 `expect().toBeVisible()` 自动等待 |
| R4 备份 fail-closed（`backup.py` Redis 失败抛异常） | ✅ 已完成 | `_redis_snapshot()` 返回 `bytes`，未启用/失败均 **raise**，不再「warn + 返回空仍报成功」 |
| R5 死代码清理（`name` 参数 + `subprocess`/`shutil` 导入） | ✅ 已完成 | `create_backup()` 无参；`routers/backup.py` 调用点已同步 |
| **R6 全栈门禁** | ❌ **未达标** | E2E **8 passed / 4 failed** |

**当前门禁实测**：

| 门禁 | 结果 |
|---|---|
| `npx tsc --noEmit` | ✅ 0 errors |
| `npx vitest run` | ✅ 250 passed |
| `python compileall`（backup.py） | ✅ OK |
| 前端 E2E（wiki-phase1 + wiki-permission + dingtalk） | ❌ **8 passed / 4 failed** |
| ├ dingtalk-login | ✅ 6/6 通过 |
| ├ wiki-phase1 | ❌ chromium + firefox 双失败 |
| └ wiki-permission | ❌ chromium + firefox 双失败 |
| 后端 `mvn test` | ⚠️ 本轮未跑（backend-java 无改动） |

**目标：E2E ≥54 passed、failed ≤10。**

---

## 1. 全局约定

### 1.1 五条红线（重申）
1. **禁止 `log.info` + `// TODO` 冒充接真**；未配置外部服务返回明确错误（禁空 catch 吞异常）
2. **禁止臆造 API**：调用任何符号前用 code-explorer / lsp 确认真实存在与**参数签名**
3. **前端禁硬编码亮色**：一律 `var(--color-*)` + MUI `sx`
4. **禁止用「改产品代码」绕过测试失败**（本轮 R1 已回滚此类污染，见 1.2 特别禁令）
5. **不引入重型依赖**

### 1.2 特别禁令（本轮强约束）
- ❌ **严禁在 `client.ts` 重新引入 `__isE2E__` 分支或 `baseURL = ''`**。
  上一轮正是靠它绕过失败，结果把 `baseURL` 置空、破坏其余 16 个模块与 E2E mock 的匹配，导致通过数从 54 跌到 40、失败从 10 涨到 26。**本轮 401 的根因已在 R7 定位为 mock glob 缺陷，不是产品代码问题**，不得再动产品代码。
- ❌ 禁删测试 / `it.skip` / `it.todo` / 弱化断言
- ❌ 禁改 `vite.config.ts` 的 `pool:'forks' + isolate:true + sequence.concurrent:false`
- ❌ 禁改 `src/test-setup.ts` 打补丁掩盖
- ❌ 禁回滚 R1–R5 已完成的改动（`client.ts` / `wiki.ts` / `backup.py` / `wiki-phase1.spec.ts`）

---

## 2. R7 — 修复 `mockWikiKb` glob 不匹配详情路由（**401 真凶，最高优先级**）

**文件**：`frontend/e2e/helpers.ts:154`

### 现状
```ts
await page.route('**/api/wiki/kb*', async (route) => { ... });
```
Playwright glob 中 **`*` 不跨 `/`**，导致：

| URL | 匹配 |
|---|---|
| `/api/wiki/kb` | ✅ 列表命中 |
| `/api/wiki/kb/<id>` | ❌ **详情不命中** |

### 实锤链路（调试抓包）
```
GET /api/wiki/kb/kb-1790338575393  → 401   (mock 未命中 → 打到真实后端)
GET /api/wiki/pages?kbId=...        → 200   (mock 命中)
POST /api/auth/refresh              → 403   (refresh 失败)
GET /login                          → 跳转  (页面回到登录页)
```
→ `WikiPageListPage` 无法渲染 → 「新建文档」按钮找不到 → **wiki-phase1 双浏览器失败**；wiki-permission 同源。

### 连带死代码
`helpers.ts:158` 的 `idMatch` regex（专门处理 `/api/wiki/kb/<id>`）因 glob 不匹配而**永不执行**。修好 glob 后该分支才生效。

### 修复方案（任选其一，须同时覆盖列表与详情）
```ts
// 方案 A：注册两条 route
await page.route('**/api/wiki/kb',   handler);  // 列表
await page.route('**/api/wiki/kb/**', handler);  // 详情 /api/wiki/kb/<id>

// 方案 B：改用正则
await page.route(/\/api\/wiki\/kb(\/[^/?]+)?(?:$|\/categories)/, handler);
```

> 参照：`mockWikiKbPermission`（第 188 行）已用 `'**/api/wiki/kb/**'` ✅ 能匹配详情，印证 `**` 才是正确写法。

### 验收
- `GET /api/wiki/kb/<id>` 返回 **200**（admin）或 **403**（非 admin），页面**不再跳 `/login`**
- `idMatch` 分支可执行
- wiki-phase1 与 wiki-permission 在 chromium + firefox 四组全部通过

---

## 3. R8 — 删除 4 个调试残留 spec 文件

这些是我方调试时创建的临时文件 + 上一轮遗留，**会被 `npx playwright test` 全量收集**，污染通过/失败统计。

**待删除**：
```
frontend/e2e/wiki-debug.spec.ts
frontend/e2e/wiki-debug17.spec.ts   ← 09:29 创建，上一轮遗留债
frontend/e2e/wiki-debug2.spec.ts
frontend/e2e/wiki-debug3.spec.ts
```

**验收**：`git status` 无 `??` 项；`ls frontend/e2e/` 下无 `wiki-debug*`。

---

## 4. R9 — 复跑全栈门禁确认达标

| 门禁 | 命令 | 目标 |
|---|---|---|
| 前端 E2E（全量） | `npx playwright test` | **≥54 passed**，failed **≤10** |
| 类型检查 | `npx tsc --noEmit` | 0 errors |
| 单测 | `npx vitest run` | 250 passed |
| 后端单测 | `cd backend-java && mvn test` | 全绿，无新增失败 |
| 脚本编译 | `python -m compileall` | OK |

**验收**：回到基线 **54 passed / 10 failed**，且不新增失败。

---

## 5. 可选清理（建议一并处理）

`frontend/e2e/helpers.ts:40` 的 `(window as any).__isE2E__ = true;` 因 `client.ts` 已无对应分支而失效（死代码），建议连同其注释「阻止 axios 401 重定向」一并移除，避免误导后续维护者。

---

## 6. 交付自检清单（GLM-5.3 提交前逐项勾选）

- [ ] `helpers.ts` 中 `mockWikiKb` 的 mock 同时覆盖 `/api/wiki/kb` 与 `/api/wiki/kb/<id>`，第 158 行 `idMatch` 逻辑可执行
- [ ] `frontend/e2e/` 下无任何 `wiki-debug*.spec.ts` 文件
- [ ] 全量 `npx playwright test`：**≥54 passed**、failed ≤10；wiki-phase1 与 wiki-permission 四组（chromium+firefox）全通过
- [ ] `npx tsc --noEmit` 0 errors；`npx vitest run` 250 passed
- [ ] 后端 `mvn test` 全绿；`python compileall` OK
- [ ] 反作弊闸门：新增 `it.skip` / 删测试 / 弱化断言 **均为 0**
- [ ] `client.ts` 中**不存在** `__isE2E__` 或 `baseURL = ''`（`git diff frontend/src/api/client.ts` 应为空）
- [ ] `wiki.ts` 中**不存在** `/api` 前缀路径

## 7. 提交规范

分栈提交，前缀 `[js]` / `[java]` / `[python]`。本轮主要为 `[js]`（前端 E2E + helpers）。

提交信息须写明：修复根因（mock glob 不跨 `/`）、门禁复跑数据（passed/failed 具体数字）、反作弊闸门结果。
