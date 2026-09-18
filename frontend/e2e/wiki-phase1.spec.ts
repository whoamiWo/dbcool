import { test, expect } from '@playwright/test';

test.describe('Wiki Phase 1 E2E', () => {
  test('Wiki 核心流程: 知识库列表 → 创建文档 → 版本历史 → 搜索', async ({ page }) => {
    // 登录
    await page.goto('/login');
    await page.fill('[autocomplete="username"]', 'admin');
    await page.fill('[type="password"]', 'admin');
    await page.click('button:has-text("登录")');

    // 进入知识库列表
    await page.goto('/wiki/kb');
    await expect(page.locator('h1')).toContainText('知识库');

    // 创建知识库
    await page.click('button:has-text("新建知识库")');
    await page.fill('input[name="name"]', '测试知识库');
    await page.fill('input[name="slug"]', 'test-kb');
    await page.click('button:has-text("创建")');
    await expect(page.locator('.MuiAlert-root')).toContainText('成功');

    // 进入文档列表
    await page.click('a[href*="/wiki/kb/"]');
    await expect(page.locator('h1')).toContainText('文档');

    // 创建文档
    await page.click('button:has-text("新建文档")');
    await page.fill('input[name="title"]', '测试文档');
    await page.fill('textarea[name="content"]', '# 测试内容\n\nHello Wiki');
    await page.click('button:has-text("保存")');

    // 版本历史
    await page.goto('/wiki/test-doc/versions');
    await expect(page.locator('h1')).toContainText('版本历史');

    // 搜索
    await page.goto('/wiki/search');
    await page.fill('input[placeholder*="搜索"]', '测试');
    await page.click('button:has-text("搜索")');
    await expect(page.locator('.MuiPaper-root')).toContainText('测试文档');
  });
});
