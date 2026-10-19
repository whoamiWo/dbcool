# Week 37 Handoff — 5 个简单 list 页前端测试(26 → 55)

> Date: 2026-09-14 · 验证: 后端 `mvn verify` BUILD SUCCESS · 前端 `vitest` 55/55 PASS

## 1. 目标

按推荐 X 路线续作:选 ROI 最高的 5 个简单 list 页测试。
CollectionsList(只读)→ FormsList → ViewsList(只读)→ RolesList → UsersList(都有 CRUD)。

## 2. 完成

### 新增前端测试(5 个文件 / 29 tests)

| 文件 | tests | 目标 |
|---|---|---|
| `pages/CollectionsList.test.tsx` | **5** | 加载 / 错误 / 空 / 数据(表格 + 字段数 + 打开链接)/ 新建按钮 |
| `pages/FormsList.test.tsx` | **6** | 加载 / 错误 / 无 collection / 有 collection / 数据 / collection 特定空状态 |
| `pages/ViewsList.test.tsx` | **4** | 加载 / 空 / collection 过滤 / 数据(table/badge/monospace) |
| `pages/RolesList.test.tsx` | **7** | 加载 / 空 / 数据(emoji 🎭 + name)/ 创建按钮 / 成功 / 失败 / disabled |
| `pages/UsersList.test.tsx` | **7** | 加载 / 空 / 数据(状态 badge)/ 创建按钮 / 成功 / 失败 / disabled |
| **总计** | **29 tests PASS** | |

**前端 26 → 55(+29)**,**总计 489 → 518 tests**。

## 3. 关键踩坑(Week 37)

### 1. **`useParams` 需要 `<Routes>` 包裹**
- 直接 `<MemoryRouter><ViewsListPage /></MemoryRouter>` → `useParams()` 返 `{}`
- 解决:用 `<Routes><Route path="/..." element={...} /></Routes>` 包裹,react-router v6 才能解析 `:collection` 参数

### 2. **QueryClient 缓存 + gcTime:0**
- 多个 test 共享 module state,前一个 test 的 `mockResolvedValue([])` 残留到下一 test
- 解决:`gcTime: 0, staleTime: 0` + 每个 beforeEach 新建 QueryClient

### 3. **拼接文本用正则**
- `🎭 admin` 是 `<h3>🎭 admin</h3>`,`getByText('admin')` 失败
- 解决:`getByText(/🎭 admin/)` 正则匹配

### 4. **同名元素出现多次**
- `修改密码` 出现在 h3 和 button,`getByText('修改密码')` → "found multiple"
- `启用/禁用` 出现在 status badge + 操作 button
- 解决:`getAllByText(/.../)` 验证至少 N 个

### 5. **mock data 字段名要看 type 定义**
- `UserMeta` 没有 `roles` 字段(只有 username/display_name/enabled)
- 测 `admin/editor` 找不到 → 改用状态 badge(启用/禁用)
- 教训:写 mock 前先看 `types/`

### 6. **MemoryRouter initialEntries 必须匹配 Route path**
- `/designer/views` 不匹配 `/designer/views/:collection` → 渲染失败
- 必须定义两个 Route 分别对应两个 path

### 7. **`<Routes>` import 风格**
- 必须 `import { MemoryRouter, Routes, Route } from 'react-router-dom'`
- 单引号 vs 双引号:保持与项目一致(单引号)

## 4. 测试模式总结(可复用)

```typescript
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { MyPage } from './MyPage';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

describe('MyPage', () => {
  let qc: QueryClient;
  beforeEach(() => {
    qc = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
    });
    vi.clearAllMocks();
  });

  const renderPage = (path = '/path') => render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/path" element={<MyPage />} />
          <Route path="/path/:id" element={<MyPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  it('加载中', () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });
});
```

## 5. 项目飞跃回顾(Week 25 → Week 37,12 周)

| Week | tests | 主要事件 |
|---|---|---|
| 25 | 0 | 基线 |
| 26-32 | 440 | 后端单元饱和 |
| 33 | 440 | Jacoco 红线饱和上限 |
| 34 | 463 | E2E + 安全审计 |
| 35 | 463 | CI + README + TESTING 文档 |
| 36 | 489 | 前端测试起步(26 tests) |
| **37** | **518** | **5 个简单 list 页(55 tests)** ⭐ |

## 6. 项目当前测试规模

| 类型 | 数量 | 占比 |
|---|---|---|
| **后端单元** | 440 | 85% |
| **后端 E2E** | 8 | 1.5% |
| **后端安全** | 15 | 3% |
| **前端 React** | 55 | 11% |
| **总计** | **518** | |

## 7. 候选(Week 38+)

X 路线继续可加:
- **更多 page**:AuditLogs / MessagesInbox / MyTasks / NotificationChannels(中等)
- **复杂 designer**:SchemaDesigner / SchemaEditor / WorkflowDesigner(难,需要 mock complex deps)
- **utils/hooks**:暂无 utils 目录,hooks 也是空
- **api/client.ts 测试**:token 注入拦截器 / 401 重定向
- **Playwright E2E**:真实浏览器点击 + 截图对比

## 8. Git

```
$ git log --oneline -5
<pending> Week 37: 5 个简单 list 页前端测试(CollectionsList/FormsList/ViewsList/RolesList/UsersList)
0dee101 Week 36 路线 X: 前端测试补齐 1 → 26(Login/Profile/Home/AppLayout/auth store)
fb81c35 Week 35 路线 B 收官: CI(mvn verify) + README + ARCHITECTURE_TESTING + test profile
```

## 9. 系统状态

- **后端**: 73 Java 源 + 463 tests / 83% bundle / 10 Jacoco 红线
- **前端**: 27 page + 3 component + **55 tests** ⭐
- **CI**: GitHub Actions 自动跑(后端 + 前端)
- **文档**: 60+ md
- **总 commits**: 48
- **总测试**:518 tests 全 PASS
