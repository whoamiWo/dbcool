# Frontend Testing Patterns — 踩坑与最佳实践(Week 38+)

> 本文件记录在写 React/Vitest/Playwright 测试时遇到的真实坑和解决方案。
> 代码可复制粘贴。

## 1. `vi.mock()` 必须配 `vi.hoisted()`(Week 38 踩坑)

**问题**:`vi.mock()` 工厂会被 hoist 到文件顶部,任何在它之前的 `const`/`let` 都不存在。

**错例**:
```typescript
const fakeInstance: any = { get: vi.fn() };  // ❌ hoist 时还没定义
vi.mock('axios', () => ({
  default: { create: () => fakeInstance },  // ReferenceError!
}));
```

**对例**:
```typescript
const mocks = vi.hoisted(() => {
  const fakeInstance: any = { get: vi.fn(), post: vi.fn(), ... };
  return { fakeInstance };
});
vi.mock('axios', () => ({
  default: Object.assign(vi.fn(), { create: vi.fn(() => mocks.fakeInstance) }),
}));
import apiClient from './client';  // 此时 mock 已生效
```

## 2. 响应拦截器返回 `Promise.reject`,不是 `err` 本身(Week 38)

**问题**:axios 拦截器约定 `return Promise.reject(error)`,测试用 `.toBe(err)` 会失败。

**错例**:
```typescript
const out = responseErrorFn(err);
expect(out).toBe(err);  // ❌ out 是 Promise,不是 err
```

**对例**:
```typescript
await expect(responseErrorFn(err)).rejects.toBe(err);  // ✅
```

## 3. fetch-based 页面用 `globalThis.fetch = vi.fn()`(Week 38)

**问题**:不用 axios 的页面(直接 `fetch()`)需要 mock 全局 fetch。

**对例**:
```typescript
beforeEach(() => {
  globalThis.fetch = vi.fn();
});
fetchMock.mockImplementation((url: string, options?: any) => {
  if (url.includes('/types')) return Promise.resolve({ ok: true, json: () => Promise.resolve({ data: [] }) });
  if (options?.method === 'POST') return Promise.resolve({ ok: true, json: () => Promise.resolve({ data: {} }) });
  return Promise.resolve({ ok: true, json: () => Promise.resolve({ data: [] }) });
});
```

## 4. 同名元素多次出现用 `getAllByText`(Week 38)

**问题**:同一文本在 badge / select option / 表格头里都出现,`getByText` 报 "Found multiple"。

**对例**:
```typescript
expect(screen.getAllByText(/CREATE/).length).toBeGreaterThanOrEqual(1);
// 或更精确: 用 container.querySelector 定位到具体元素
```

## 5. `await expect(promiseFn()).rejects.toThrow()`(Week 39)

**问题**:Promise reject 不抛同步异常,要用 `rejects` matcher。

**对例**:
```typescript
await expect(apiClient.get('/protected')).rejects.toThrow();
```

## 6. `FormRuntime` 只有 `form.layout` 里的字段才渲染(Week 39)

**问题**:传 `fields` 不够,字段必须在 `form.layout` 里才会渲染。

**错例**:
```tsx
<FormRuntime form={{ layout: [] }} fields={[textField]} />  // 不渲染
```

**对例**:
```tsx
<FormRuntime form={{ layout: [{ field: 'name' }] }} fields={[textField]} />  // ✅
```

**测试 helper** 自动加 layout:
```typescript
const wrap = ({ fields = [textField], form }) => {
  const layout = form?.layout?.length ? form.layout : fields.map(f => ({ field: f.name }));
  return render(<FormRuntime form={{ ...baseForm, ...form, layout }} fields={fields} ... />);
};
```

## 7. visibility 规则的边界:`Number(undefined) > 0` 是 `NaN`(Week 39)

```typescript
// 当 trigger 字段不存在,data[name] === undefined
// Number(undefined) === NaN
// NaN > 0 === false → 字段被隐藏
```

测试要覆盖这种边界,否则 bug 难发现。

## 8. `FormRuntime` handleSubmit 已加 catch(Week 39+ fix)

之前只有 try/finally,onSubmit reject 会触发 unhandled rejection。
修后(Week 39 Step D):
```typescript
try {
  await onSubmit(data);
  setData({});
} catch (err) {
  console.error('FormRuntime submit error:', err);  // 静默失败,不重置 data
} finally {
  setSubmitting(false);
}
```

## 9. Playwright + page.route() mock API(Week 39 Step C)

**不启后端**,只 mock `/api/*`:

```typescript
await page.route('**/api/auth/login', async (route) => {
  await route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ code: 0, message: 'ok', data: { access_token: 'fake', ... } }),
  });
});
await page.goto('/login');
// ... 后续操作
```

**优点**:快(不启 Spring)、稳(API 行为固定)、CI 友好。

## 10. CSSProperties 内联 style + happy-dom vs jsdom

默认 vitest 用 jsdom 25,对 React 18 + 现代 CSS 支持好。
如果切换到 happy-dom(更快),需要测试 input event 行为差异。

## 11. 数据 mock:用 `vi.mocked(apiClient.get).mockResolvedValue({...} as any)`

apiClient.get 返回类型 `Promise<T>`,但 T 由调用方推断。mock 时直接给数据:

```typescript
import apiClient from '@/api/client';
vi.mocked(apiClient.get).mockResolvedValue({ data: { items: [...] } } as any);
```

用 `as any` 跳过类型检查,避免 mock shape 不匹配编译失败。

## 12. Time/Date mock:用 `vi.useFakeTimers()` + `vi.setSystemTime()`

```typescript
beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

it('今天', () => {
  vi.setSystemTime(new Date('2026-09-14'));
  // ...
});
```

---

## 常用导入模板

```typescript
// Vitest + React Testing Library
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

// Playwright E2E
import { test, expect, type Page, type Route } from '@playwright/test';
```

## 一句话总结

> **mock 模块用 vi.hoisted / Promise reject 用 rejects / fetch mock globalThis / layout 决定渲染**
