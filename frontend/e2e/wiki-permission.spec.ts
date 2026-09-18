import { test, expect } from '@playwright/test';

test.describe('Wiki Permission E2E', () => {
  test('用户创建知识库后，其他用户无法访问（权限隔离）', async ({ page, browser }) => {
    // 用户 1 登录并创建知识库
    await page.goto('/login');
    await page.fill('[autocomplete="username"]', 'admin');
    await page.fill('[type="password"]', 'admin');
    await page.click('button:has-text("登录")');

    await page.goto('/wiki/kb');
    await page.click('button:has-text("新建知识库")');
    await page.fill('input[name="name"]', '私有知识库');
    await page.fill('input[name="slug"]', 'private-kb');
    await page.click('button:has-text("创建")');
    await expect(page.locator('.MuiAlert-root')).toContainText('成功');

    // 用户 2 登录
    const context2 = await browser.newContext();
    const page2 = await context2.newPage();
    await page2.goto('/login');
    await page2.fill('[autocomplete="username"]', 'user2');
    await page2.fill('[type="password"]', 'user2');
    await page2.click('button:has-text("登录")');

    // 用户 2 尝试访问私有知识库
    await page2.goto('/wiki/kb/private-kb');
    await expect(page2.locator('.MuiAlert-root')).toContainText(/无权限|403|拒绝/);

    await context2.close();
  });

  test('管理员可以访问所有知识库', async ({ page }) => {
    await page.goto('/login');
    await page.fill('[autocomplete="username"]', 'admin');
    await page.fill('[type="password"]', 'admin');
    await page.click('button:has-text("登录")');

    await page.goto('/wiki/kb');
    await expect(page.locator('h1')).toContainText('知识库');
  });
});
