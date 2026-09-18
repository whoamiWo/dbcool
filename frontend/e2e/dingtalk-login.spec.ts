import { test, expect } from '@playwright/test';

test.describe('DingTalk Login E2E', () => {
  test('钉钉登录页面渲染正确', async ({ page }) => {
    await page.goto('/login');

    // 检查钉钉登录按钮
    await expect(page.locator('button:has-text("钉钉扫码登录")')).toBeVisible();
    await expect(page.locator('h5')).toContainText('钉钉登录');
  });

  test('获取钉钉授权 URL 成功', async ({ page }) => {
    // 模拟获取授权 URL
    const response = await page.request.post('/api/dingtalk/auth-url');
    const data = await response.json();

    expect(data.code).toBe(0);
    expect(data.data.authUrl).toContain('https://oapi.dingtalk.com/oauth2/auth');
  });

  test('钉钉组织架构同步接口响应', async ({ page }) => {
    // 先登录
    await page.goto('/login');
    await page.fill('[autocomplete="username"]', 'admin');
    await page.fill('[type="password"]', 'admin');
    await page.click('button:has-text("登录")');

    // 调用组织架构同步
    const response = await page.request.post('/api/dingtalk/sync-org');
    const data = await response.json();

    expect(data.code).toBe(0);
    expect(data.data).toHaveProperty('synced');
  });
});
