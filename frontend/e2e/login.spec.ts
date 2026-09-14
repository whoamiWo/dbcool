import { test, expect } from '@playwright/test';
import { mockLoginSuccess, mockLoginFailure, ADMIN_USER } from './helpers';

test.describe('Login 流程 E2E', () => {
  test('成功登录 → 跳转 /home + token 写入 localStorage', async ({ page }) => {
    await mockLoginSuccess(page);
    await page.goto('/login');

    await expect(page.locator('h1')).toContainText('NocoBase');
    await page.locator('input[autocomplete="username"]').fill('admin');
    await page.locator('input[type="password"]').fill('secret123');
    await page.locator('button[type="submit"]').click();

    // 看到成功提示
    await expect(page.getByText('登录成功,正在跳转')).toBeVisible({ timeout: 3000 });

    // 跳转后到 /home
    await page.waitForURL('**/home', { timeout: 5000 });

    // token 写入 localStorage(auth store 的 persist middleware)
    const token = await page.evaluate(() => localStorage.getItem('nocobase_access_token'));
    expect(token).toBe('fake-jwt-token-for-e2e');
  });

  test('登录失败 → 显示错误消息 + 不跳转', async ({ page }) => {
    await mockLoginFailure(page, '用户名或密码错误');
    await page.goto('/login');

    await page.locator('input[autocomplete="username"]').fill('admin');
    await page.locator('input[type="password"]').fill('wrong');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('用户名或密码错误')).toBeVisible({ timeout: 3000 });
    await expect(page).toHaveURL(/\/login/);
  });

  test('空表单提交 → 显示 zod 校验错误', async ({ page }) => {
    // 不需要 mock(校验在前端)
    await page.goto('/login');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('请输入用户名')).toBeVisible();
    await expect(page.getByText('请输入密码')).toBeVisible();
  });

  test('未登录访问 /admin/users → 401 → 跳 /login', async ({ page }) => {
    // mock /admin/users 返回 401,触发 api client 拦截器跳转
    await page.route('**/api/admin/users*', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ code: 401, message: '未认证', data: null }),
      });
    });

    await page.goto('/admin/users');

    // API client 拦截器清 token + 跳转 /login
    await page.waitForURL('**/login', { timeout: 5000 });

    const token = await page.evaluate(() => localStorage.getItem('nocobase_access_token'));
    expect(token).toBeNull();
  });

  test('API 返回非 200 但含错误 → 显示 message', async ({ page }) => {
    await mockLoginFailure(page, '账号被锁定');
    await page.goto('/login');

    await page.locator('input[autocomplete="username"]').fill('locked');
    await page.locator('input[type="password"]').fill('any');
    await page.locator('button[type="submit"]').click();

    await expect(page.getByText('账号被锁定')).toBeVisible();
  });
});
