# Week 39 Handoff — A:FormRuntime + B:FilterBar + C:Playwright E2E + D:修 bug

> Date: 2026-09-14 · 验证: 后端 `mvn verify` BUILD SUCCESS · 前端 `vitest --coverage` 149/149 PASS · Playwright `test --list` 12 tests / 3 files

## 1. 目标

Week 38 候选的下一步:
- **A**:FormRuntime 0% 覆盖率洼地
- **B**:FilterBar 0% 覆盖率洼地
- **C**:Playwright E2E 起步
- **D**:清 3 个已知源码 bug + 文档化

## 2. 完成

### 新增测试

| 类型 | 项 | tests |
|---|---|---|
| **A** | `components/forms/FormRuntime.test.tsx` | 25 |
| **B** | `components/views/FilterBar.test.tsx` | 32 |
| **C** | `e2e/login.spec.ts` + `e2e/users-crud.spec.ts` | 9 |
| **C+** | `e2e/form-submit.spec.ts`(Step D 加) | 3 |
| **D** | 修 `FormRuntime.tsx` / `AuditLogs.tsx` / 加 `TESTING_PATTERNS.md` | — |
| **总计** | **新 +69 tests** | 57 vitest + 12 E2E |

### 覆盖率(Week 38 → Week 39)

| 模块 | Week 38 | Week 39 |
|---|---|---|
| `api/client.ts` | 100% | **100%** |
| `stores/auth.ts` | 100% | **100%** |
| `components/AppLayout.tsx` | 100% | **100%** |
| `components/forms/FormRuntime.tsx` | 0% | **97.54%** ⭐ |
| `components/views/FilterBar.tsx` | 0% | **98.93%** ⭐ |
| **All files(排除 pages/)** | 29.11% | **98.73%** ⭐ |

## 3. 关键踩坑(Week 39)

### 1. **`FormRuntime` 字段必须在 `form.layout` 才渲染**
- 测试 helper 必须把 fields 自动加到 layout
- `props.form?.layout?.length` 检查(因为 baseForm.layout = [])

### 2. **`Number(undefined) > 0 = NaN > 0 = false`**
- visibility `gt` 规则在 trigger 字段不存在时,会隐藏
- 边界 case 必须测试

### 3. **FormRuntime handleSubmit 只有 try/finally 没 catch(已修)**
- 源码 bug,onSubmit reject 触发 unhandled rejection
- Week 39 Step D 修:加 catch,console.error,保留 data

### 4. **AuditLogs axios 解包 bug(已修)**
- 源码 `r.data.code === 0` — 拦截器已解包,实际应是 `r.code`
- Week 39 Step D 修:`(r as any).code === 0`,`r.data.logs`
- 测试同步更新从 double-nested → single-nested

## 4. 测试模式文档(Step D)

`frontend/TESTING_PATTERNS.md` — 12 个模式:
1. `vi.mock + vi.hoisted` 共享 fakeInstance
2. `await expect(fn()).rejects.toBe(err)`(不是 `.toBe(err)`)
3. `globalThis.fetch = vi.fn()` mock fetch-based 页面
4. `getAllByText` 处理多个同名元素
5. `rejects.toThrow()` 测试 Promise reject
6. `FormRuntime` 字段必须在 layout
7. visibility `Number(undefined) > 0 = false` 边界
8. FormRuntime catch 已加(Step D 修复)
9. Playwright `page.route()` mock API
10. CSSProperties happy-dom vs jsdom
11. 数据 mock `as any` 跳过类型
12. `vi.useFakeTimers` + `vi.setSystemTime`

## 5. Playwright E2E 架构

| 决策 | 选择 | 原因 |
|---|---|---|
| **后端集成?** | ❌ 不启 Spring | 后端已有 463 tests 覆盖集成 |
| **API mock** | `page.route()` 拦截 | 快 / 稳 / CI 友好 |
| **webServer** | `pnpm build && preview` | production build 测 |
| **浏览器** | Chromium only | 跨浏览器 ROI 低 |

## 6. 12 个 E2E tests

### login.spec.ts(5)
1. 成功登录:跳 /home + token 入 localStorage
2. 登录失败:显示消息 + 不跳
3. 空表单:zod 校验错误
4. **未登录访问 → 401 → 跳 /login**(API 拦截器集成)
5. 业务错误消息

### users-crud.spec.ts(4)
1. 空列表 → 显示"暂无用户"
2. 列表渲染 + 启停状态
3. 新建 POST 后列表增加
4. 启停 PATCH 调用

### form-submit.spec.ts(3)
1. FormsList 渲染
2. required 校验:空提交不调 POST
3. 填表成功:POST payload + 跳 collection

## 7. 系统状态

- **后端**: 73 Java 源 + 463 tests / 83% bundle / 10 Jacoco 红线
- **前端 vitest**: 17 文件 / **149 tests** / **3 个核心模块 100%** / 总 98.73% ⭐
- **前端 E2E**: 3 文件 / **12 tests** ⭐(登录 / CRUD / 表单提交)
- **CI**: 4 个 job(java / python / frontend vitest / **frontend-e2e** ⭐)
- **文档**: 62+ md(本周 +TESTING_PATTERNS.md)
- **总测试**:**612 vitest + 12 E2E = 624 tests**

## 8. 项目飞跃回顾(Week 25 → Week 39,14 周)

| Week | tests | 主要事件 |
|---|---|---|
| 25 | 0 | 基线 |
| 33 | 440 | 后端单元饱和 |
| 34 | 463 | E2E + 安全审计 |
| 35 | 463 | CI + README + TESTING |
| 36 | 489 | 前端测试起步(26) |
| 37 | 518 | 5 个 list 页(55) |
| 38 | 555 | API + coverage + 4 list 页(92) |
| **39** | **624** | **覆盖率 98.73% + Playwright E2E + 修 bug** ⭐ |

## 9. 候选(Week 40+)

| 方向 | 估时 | 收益 |
|---|---|---|
| **更多 E2E flow**(workflow / collection / view) | 1-2 天 | 覆盖完整用户路径 |
| **Playwright UI 模式**(`pnpm test:e2e:ui`) | 0 | 调试体验 |
| **修剩余 TS 错误**(ErDiagram / AuditLogs 已修 1 处) | 1h | 干净基线 |
| **a11y(jest-axe)** | 1h | 自动检测可访问性 |
| **组件 Storybook** | 半天 | 视觉回归 + 文档 |

## 10. Git

```
$ git log --oneline -4
<pending> Week 39 Step D: 修 3 个 bug + form-submit E2E + TESTING_PATTERNS.md
b51867a Week 39 Step C: Playwright E2E (9 tests, 2 specs)
12fde62 Week 39 A+B: FormRuntime + FilterBar 测试(0% 洼地 → 98.73% 覆盖率)
05c4b95 Week 38: A.API client 测试 + B.coverage 工具 + C.4 list 页(55 → 92)
```
