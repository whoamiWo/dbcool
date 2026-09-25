import { test, expect } from '@playwright/test';
import { mockLoginSuccess, mockMe, mockWikiKb, mockWikiPages, mockWikiKbPermission, mockHomeQueries, ADMIN_USER, MockUser } from './helpers';

const USER2: MockUser = { id: 'u-user2', username: 'user2', tenant_id: 't1', roles: ['user'] };

test.describe('Wiki Permission E2E', () => {
  test('用户创建知识库后，其他用户无法访问（权限隔离）', async ({ page, browser }) => {
    // 用户 1 (admin) 登录并创建知识库
    await mockLoginSuccess(page);
    await mockMe(page, ADMIN_USER);
    await mockWikiKb(page, []);
    await mockHomeQueries(page);
    await page.goto('/login');
    await page.fill('[autocomplete="username"]', 'admin');
    await page.fill('[type="password"]', 'admin');
    await page.click('button:has-text("登录")');

    await page.goto('/wiki/kb');
    await page.click('button:has-text("新建知识库")');
    await page.getByLabel('名称').fill('私有知识库');
    await page.getByLabel('Slug').fill('private-kb');
    await page.getByRole('button', { name: '创建' }).click();
    await expect(page.locator('dialog')).not.toBeVisible();

    // 用户 2 登录
    const context2 = await browser.newContext();
    const page2 = await context2.newPage();
    await mockLoginSuccess(page2, USER2);
    await mockMe(page2, USER2);
    await mockWikiKbPermission(page2, USER2);
    await mockWikiPages(page2);
    await mockHomeQueries(page2);
    await page2.goto('/login');
    await page2.fill('[autocomplete="username"]', 'user2');
    await page2.fill('[type="password"]', 'user2');
    await page2.click('button:has-text("登录")');

    // 用户 2 尝试访问私有知识库
    await page2.goto('/wiki/kb/private-kb');
    await expect(page2.locator('text=/无权限|403|拒绝/')).toBeVisible();

    await context2.close();
  });

  test('管理员可以访问所有知识库', async ({ page }) => {
    await mockLoginSuccess(page);
    await mockMe(page, ADMIN_USER);
    await mockWikiKb(page);
    await mockHomeQueries(page);
    await page.goto('/login');
    await page.fill('[autocomplete="username"]', 'admin');
    await page.fill('[type="password"]', 'admin');
    await page.click('button:has-text("登录")');

    await page.goto('/wiki/kb');
    await expect(page.getByRole('heading', { name: '知识库' })).toBeVisible();
  });
});