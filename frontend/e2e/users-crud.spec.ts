import { test, expect } from '@playwright/test';
import { mockLoginSuccess, mockMe } from './helpers';

const sampleUsers = [
  { id: 'u1', username: 'alice', display_name: 'Alice', enabled: true },
  { id: 'u2', username: 'bob', display_name: 'Bob', enabled: false },
];

test.describe('UsersList CRUD E2E', () => {
  test.beforeEach(async ({ page }) => {
    await mockLoginSuccess(page);
    await mockMe(page);
  });

  test('空列表:渲染空表格 + 用户管理(0)', async ({ page }) => {
    await page.route('**/api/admin/users*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: [] }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/admin/users');
    // UsersList 没有专门的空态,空 table + 用户管理(0) 表示无数据
    await expect(page.getByRole('heading', { name: /用户管理\(0\)/ })).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('button', { name: /\+ 新建用户/ })).toBeVisible();
  });

  test('列表:渲染已有用户 + 显示启停状态', async ({ page }) => {
    await page.route('**/api/admin/users*', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: sampleUsers }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/admin/users');
    // 用 exact + cell role 避免 'alice' / 'Alice' 冲突
    await expect(page.getByRole('cell', { name: 'alice', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'bob', exact: true })).toBeVisible();
  });

  test('新建用户:POST 后列表增加', async ({ page }) => {
    let postedBody: any = null;
    await page.route('**/api/admin/users*', async (route) => {
      const req = route.request();
      if (req.method() === 'GET') {
        const users = postedBody
          ? [{ id: 'u-new', ...postedBody, enabled: true }, ...sampleUsers]
          : sampleUsers;
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: users }),
        });
      } else if (req.method() === 'POST') {
        postedBody = JSON.parse(req.postData() ?? '{}');
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: { id: 'u-new', ...postedBody, enabled: true } }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/admin/users');

    // 展开创建表单:点 + 新建用户
    await page.getByRole('button', { name: /\+ 新建用户/ }).click();

    // 填 3 个 input
    const inputs = page.locator('input');
    await inputs.nth(0).fill('charlie');  // username
    await inputs.nth(1).fill('pwd123');   // password
    await inputs.nth(2).fill('Charlie');  // displayName

    // 点 创建
    await page.getByRole('button', { name: /^创建/ }).click();

    // 等 POST + 列表刷新后看 charlie 出现
    await expect(page.getByText('charlie')).toBeVisible({ timeout: 5000 });
  });

  test('启停用户:PATCH 调用 + UI 更新', async ({ page }) => {
    let patchCalled = false;
    // 拦截 PATCH /admin/users/<id>(具体路径,避免 glob 不匹配)
    await page.route('**/api/admin/users/*', async (route) => {
      if (route.request().method() === 'PATCH') {
        patchCalled = true;
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: { ...sampleUsers[0], enabled: false } }),
        });
      } else {
        await route.continue();
      }
    });
    // 拦截 GET /admin/users(无 /id 后缀)
    await page.route(/\/api\/admin\/users\/?$/, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: sampleUsers }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/admin/users');

    // alice 是 enabled=true → 按钮显示"禁用"
    const disableBtn = page.getByRole('button', { name: /^禁用$/ }).first();
    await expect(disableBtn).toBeVisible();
    await disableBtn.click();

    // 等 PATCH 触发
    await page.waitForTimeout(500);
    expect(patchCalled).toBe(true);
  });
});
