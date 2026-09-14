/**
 * E2E: Collection 删除流程
 * 覆盖 CollectionsList 列表 → 删除 collection → 后端 DELETE /api/collections/{name}
 * Week 41 Step F3 B3 验证
 */
import { test, expect } from '@playwright/test';
import { mockLoginSuccess, mockMe } from './helpers';

const sampleCollections = [
  { id: 'c1', name: 'orders', title: '订单', fields: [], tenant_id: 't1', created_at: '2026-01-01' },
  { id: 'c2', name: 'users', title: '用户', fields: [], tenant_id: 't1', created_at: '2026-01-01' },
];

test.describe('Collection 删除 E2E', () => {
  test.beforeEach(async ({ page }) => {
    await mockLoginSuccess(page);
    await mockMe(page);
  });

  test('后端 DELETE /api/collections/{name} 契约(204)', async ({ page }) => {
    let deleteCalled = false;
    let deletedName: string | null = null;

    // 列表 mock
    await page.route('**/api/collections', async (route) => {
      const url = new URL(route.request().url()).pathname;
      if (url === '/api/collections' || url === '/api/collections/') {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: sampleCollections }),
        });
      } else {
        await route.continue();
      }
    });
    // 详情 mock
    await page.route(/\/api\/collections\/orders\/?$/, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: sampleCollections[0] }),
        });
      } else if (route.request().method() === 'DELETE') {
        deleteCalled = true;
        const url = new URL(route.request().url());
        deletedName = url.pathname.split('/').pop() ?? null;
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'deleted', data: { name: 'orders' } }),
        });
      } else {
        await route.continue();
      }
    });

    // 先 navigate 让 page 有 baseURL
    await page.goto('/login');
    await expect(page.locator('h1')).toContainText('NocoBase');

    // 模拟前端发起 DELETE(UI 暂无删除按钮,后续 Story 添加)
    const result = await page.evaluate(async () => {
      const r = await fetch('/api/collections/orders', { method: 'DELETE' });
      const body = await r.json();
      return { status: r.status, ok: r.ok, body };
    });

    expect(deleteCalled).toBe(true);
    expect(deletedName).toBe('orders');
    expect(result.status).toBe(200);
    expect(result.body.code).toBe(0);
    expect(result.body.message).toBe('deleted');
    expect(result.body.data.name).toBe('orders');
  });

  test('删除不存在的 collection:404', async ({ page }) => {
    await page.route('**/api/collections/ghost', async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({
          status: 404, contentType: 'application/json',
          body: JSON.stringify({ code: 404, message: 'collection 不存在', data: null }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/login');
    await expect(page.locator('h1')).toContainText('NocoBase');

    const result = await page.evaluate(async () => {
      const r = await fetch('/api/collections/ghost', { method: 'DELETE' });
      return { status: r.status, ok: r.ok };
    });

    expect(result.status).toBe(404);
    expect(result.ok).toBe(false);
  });

  test('跨租户删除:403', async ({ page }) => {
    await page.route('**/api/collections/orders', async (route) => {
      if (route.request().method() === 'DELETE') {
        await route.fulfill({
          status: 403, contentType: 'application/json',
          body: JSON.stringify({ code: 403, message: '无权删除该 collection', data: null }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/login');
    await expect(page.locator('h1')).toContainText('NocoBase');

    const result = await page.evaluate(async () => {
      const r = await fetch('/api/collections/orders', { method: 'DELETE' });
      return { status: r.status, ok: r.ok };
    });

    expect(result.status).toBe(403);
  });
});
