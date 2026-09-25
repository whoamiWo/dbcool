import { test, expect } from '@playwright/test';
import { mockLoginSuccess, mockMe, mockWikiKb, mockWikiPages, mockHomeQueries, ADMIN_USER } from './helpers';

test.describe('Wiki Phase 1 E2E', () => {
  test('Wiki 核心流程: 知识库列表 → 创建文档 → 版本历史 → 搜索', async ({ page }) => {
    await mockLoginSuccess(page);
    await mockMe(page, ADMIN_USER);
    await mockWikiKb(page, []);
    await mockWikiPages(page);
    await mockHomeQueries(page);

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
    await page.getByLabel('名称').fill('测试知识库');
    await page.getByLabel('Slug').fill('test-kb');
    await page.click('button:has-text("创建")');
    // KnowledgeBaseList 成功后关闭 dialog 并刷新列表
    await expect(page.locator('dialog')).not.toBeVisible();
    await expect(page.locator('text=测试知识库')).toBeVisible();

    // 进入文档列表（点击刚创建的知识库"查看"按钮）
    await page.getByRole('button', { name: '查看' }).first().click();
    await page.waitForLoadState('networkidle');
    // 等待文档列表加载（新建文档按钮可见）
    await expect(page.getByRole('button', { name: '新建文档' })).toBeVisible();

    // 创建文档
    await page.getByRole('button', { name: '新建文档' }).click();
    await page.getByLabel('标题').fill('测试文档');
    await page.getByLabel('Slug').fill('test-doc');
    await page.getByLabel('内容').fill('# 测试内容\n\nHello Wiki');
    await page.getByRole('button', { name: '创建' }).last().click();
    await page.waitForLoadState('networkidle');

    // 版本历史（直接访问版本历史页面）
    await page.goto('/wiki/test-doc/versions');
    await page.waitForLoadState('networkidle');
    await expect(page.getByRole('heading', { name: /版本历史/ })).toBeVisible();
    await expect(page.getByText(/版本 1/)).toBeVisible();

    // 搜索
    await page.goto('/wiki/search');
    await page.waitForLoadState('networkidle');
    await page.getByPlaceholder(/搜索/).fill('测试');
    await page.getByRole('button', { name: '搜索' }).click();
    await expect(page.locator('text=测试文档')).toBeVisible();
  });
});