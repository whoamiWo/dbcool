# Week 36 Handoff — 路线 X:前端测试补齐(1 → 26 tests)

> Date: 2026-09-14 · 验证: 后端 `mvn verify` BUILD SUCCESS · 前端 `vitest` 26/26 PASS

## 1. 目标

按推荐路线 X:前端测试补齐。后端 463 tests vs 前端 1 test 是质量大漏洞。
Week 36 完成 5 个测试文件 + 26 tests,从 1 → 26(+25)。

## 2. 完成

### 新增前端测试(5 个文件 / 25 tests + 修复 1 个旧测试)

| 文件 | tests | 目标 |
|---|---|---|
| `pages/Login.test.tsx` | **7** | 渲染 / 校验 / 登录成功 / 后端错误 / 网络错误 / 记住用户名 / 预填 |
| `pages/Profile.test.tsx` | **5** | 渲染 / 加载中 / 显示字段 / 改密码成功 / 失败 |
| `pages/Home.test.tsx` | **5** | 游客 / 欢迎 display_name / fallback username / loading |
| `components/AppLayout.test.tsx` | **4** | 导航 / 退出 / active link / 未登录 |
| `stores/auth.test.ts` | **5** | 初始 / setAuth / 多次调用覆盖 / clear / localStorage |
| **总计** | **26 PASS** | |

### vitest 配置更新(`vite.config.ts`)

- `include: ['src/**/*.{test,spec}.{ts,tsx}']` — 只跑 src/,避免被 `.vscode-server` 干扰
- `exclude: ['node_modules', 'dist', '.vscode-server']` — 显式排除

### frontend README 更新

新增 "🧪 测试覆盖(Week 36)" 段,包含:
- 5 个测试文件清单 + 各自覆盖目标
- vitest 配置说明
- 测试命令(`pnpm test:run`)

### CI 已就绪

`.github/workflows/ci.yml` 已包含 `frontend-test` job,自动跑 `pnpm test:run` + lint + tsc + build。

## 3. 关键踩坑(Week 36)

### 1. **vitest 跑 VSCode 目录的测试**
- 默认行为:`vitest` 会扫所有 `.test.ts` 文件,包括 `.vscode-server/bin/...`
- 解决:`include: ['src/**/*.{test,spec}.{ts,tsx}']` 限定范围

### 2. **`<label>` 没 `htmlFor` → `getByLabelText` 失败**
- Login.tsx 的 `<label>` 没有 `htmlFor` 属性,无法用 `getByLabelText` 匹配
- 解决:用 `getByPlaceholderText` 或 `document.querySelector('input[type="password"]')` 直接定位

### 3. **Profile.tsx queryFn 解包 `res.data`**
- Profile mock 应返 `{ data: {...} }`,不是直接的 MeData
- 解决:`vi.mocked(apiClient.get).mockResolvedValue({ data } as any)`

### 4. **AppLayout 显示 "username(roles)" 拼接文本**
- 单纯 `getByText('alice')` 失败,因为实际渲染 "alice(admin)"
- 解决:`getByText(/alice\(/)` 正则匹配

### 5. **多个元素同名(`修改密码` 在 h3 和 button)**
- `getByText('修改密码')` → "found multiple"
- 解决:用 `getByRole('heading', { name: '修改密码' })` 或 `getByText(/修改密码/)` 加正则

### 6. **fireEvent.change 比 userEvent 简单**
- `@testing-library/user-event` 没装(本地 npm 失败)
- 解决:用 `fireEvent.change(input, { target: { value: 'x' } })` 替代

## 4. 项目测试规模(Week 36 末)

| 类型 | 数量 | 位置 |
|---|---|---|
| **后端 Java 单测** | 440 | `backend-java/src/test/java/com/nocobase/<pkg>/` |
| **后端 Java E2E** | 8 | `backend-java/src/test/java/com/nocobase/e2e/` |
| **后端 Java 安全** | 15 | `backend-java/src/test/java/com/nocobase/security/` |
| **前端 React 单测** | **26** ⭐ | `frontend/src/{pages,components,stores}/` |
| **总计** | **489 tests** | |

## 5. CI 验证

### 后端
```bash
$ cd backend-java && mvn verify
[INFO] Tests run: 463, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 前端
```bash
$ cd frontend && pnpm test:run
 ✓ src/pages/Profile.test.tsx (5 tests) 162ms
 ✓ src/pages/Login.test.tsx (7 tests) 197ms
 ✓ src/stores/auth.test.ts (5 tests)
 ✓ src/pages/Home.test.tsx (5 tests)
 ✓ src/components/AppLayout.test.tsx (4 tests)
 Test Files  5 passed (5)
      Tests  26 passed (26)
```

## 6. 关键设计决策

### 6.1 用 `vi.mock` 而非 `jest.mock`
- vitest API 类似 jest,但用 `vi`
- `vi.mock('@/api/client', () => ({ default: { post: vi.fn(), get: vi.fn() } }))`
- `vi.mocked(apiClient.post).mockResolvedValueOnce(...)`

### 6.2 不引入额外依赖
- 不装 `@testing-library/user-event`(npm 失败)
- 用 `fireEvent` 替代(简单事件 trigger)
- 不装 MSW(网络 mock)— 用 `vi.fn()` 直接 mock `apiClient`

### 6.3 jsdom + jsx
- vitest 用 jsdom 模拟浏览器环境
- 直接渲染 React 组件 + waitFor 异步操作

## 7. 项目飞跃回顾(Week 25 → Week 36,11 周)

| Week | tests | 主要事件 |
|---|---|---|
| 25 | 0 (后端 0%) | 基线 |
| 26-32 | 440 | 后端单元测试饱和 |
| 33 | 440 | Jacoco 红线饱和上限 |
| 34 | 463 | E2E + 安全审计(后端方向变更) |
| 35 | 463 | CI + README + 测试架构文档 |
| **36** | **489** | **前端测试 1 → 26 补齐** ⭐ |

## 8. 候选(Week 37+)

按 X 路线继续可加:
- **更多 page 测试**:FormsList / WorkflowsList / RolesList / UsersList
- **自定义 hooks**:useAuth / useQuery wrappers
- **API client 测试**:拦截器逻辑(token 注入 / 401 重定向)
- **utils 测试**:格式化函数 / 验证函数
- **Vitest 覆盖率报告**(类似后端 Jacoco)
- **前端 E2E**:Playwright 真实点击 + 截图对比

## 9. Git

```
$ git log --oneline -5
<pending> Week 36: 前端测试补齐 1 → 26(Login/Profile/Home/AppLayout/auth store)
fb81c35 Week 35 路线 B 收官: CI(mvn verify) + README + ARCHITECTURE_TESTING + test profile
5682123 Week 34: SecurityAuditTest + CollectionLifecycleE2ETest + E2ESetupSmoke
3b8c979 Week 33: Jacoco 红线抬到饱和上限
```

## 10. 系统状态

- **后端**: 73 个 Java 源 + 463 tests / 83% bundle / 10 Jacoco 红线
- **前端**: 27 个 page + 3 个 component + **26 tests** ⭐
- **CI**: GitHub Actions(后端 + 前端 job)自动跑
- **文档**: 60+ md(35+ WEEK handoff + README + ARCHITECTURE_TESTING + ADR)
- **总 commits**: 47
