/**
 * E2E: 表单提交流程
 * 覆盖 FormsList 列表 → 点开表单 → 填写 → 提交 → 跳转 collection
 */
import { test, expect } from '@playwright/test';
import { mockLoginSuccess, mockMe } from './helpers';

const sampleForm = {
  id: 'f1',
  collection_name: 'orders',
  title: '订单表单',
  description: '请填写订单',
  layout_json: '[{"field":"customer"},{"field":"amount"},{"field":"agreed"}]',
  rules_json: '{"validation":{"customer":[{"type":"required"}]}}',
  tenant_id: 't1',
  created_at: '2026-01-01T00:00:00Z',
};

const sampleFields = [
  { name: 'customer', label: '客户', type: 'text', required: true },
  { name: 'amount', label: '金额', type: 'number', required: false },
  { name: 'agreed', label: '同意条款', type: 'boolean', required: false },
];

test.describe('表单提交流程 E2E', () => {
  test.beforeEach(async ({ page }) => {
    await mockLoginSuccess(page);
    await mockMe(page);
  });

  test('FormsList → 点开表单 → 看到表单标题', async ({ page }) => {
    await page.route('**/api/forms*', async (route) => {
      if (route.request().method() === 'GET' && !route.request().url().includes('f1')) {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: [sampleForm] }),
        });
      } else {
        await route.continue();
      }
    });

    await page.route('**/api/forms/f1', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: sampleForm }),
      });
    });

    await page.route('**/api/collections/orders', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 0, message: 'ok',
          data: { name: 'orders', display_name: '订单', fields: sampleFields },
        }),
      });
    });

    // 1. 进 FormsList
    await page.goto('/designer/forms');
    await expect(page.getByText('订单表单')).toBeVisible({ timeout: 5000 });
  });

  test('填表 + required 校验失败:不调 POST', async ({ page }) => {
    let postCalled = false;

    await page.route('**/api/forms/f1', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: sampleForm }),
      });
    });
    await page.route('**/api/collections/orders', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 0, message: 'ok',
          data: { name: 'orders', display_name: '订单', fields: sampleFields },
        }),
      });
    });
    await page.route('**/api/collections/orders/records', async (route) => {
      if (route.request().method() === 'POST') {
        postCalled = true;
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: { id: 'rec-1' } }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/forms/f1/fill');

    // 等表单渲染
    await expect(page.getByRole('heading', { name: /订单表单/ })).toBeVisible({ timeout: 5000 });

    // 必填字段空着,点提交
    const submitBtn = page.getByRole('button', { name: /^提交$/ });
    await submitBtn.click();

    // 应该看到错误提示
    await expect(page.getByText(/客户 必填|必填/)).toBeVisible({ timeout: 3000 });

    // POST 没被调
    expect(postCalled).toBe(false);
  });

  test('填表成功:POST 调用 + 跳 collection', async ({ page }) => {
    let postedPayload: any = null;

    await page.route('**/api/forms/f1', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: sampleForm }),
      });
    });
    await page.route('**/api/collections/orders', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          code: 0, message: 'ok',
          data: { name: 'orders', display_name: '订单', fields: sampleFields },
        }),
      });
    });
    await page.route('**/api/collections/orders/records', async (route) => {
      if (route.request().method() === 'POST') {
        postedPayload = JSON.parse(route.request().postData() ?? '{}');
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: { id: 'rec-new' } }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/forms/f1/fill');

    // 填客户字段(text 输入)
    const customerInput = page.locator('input').filter({ hasNot: page.locator('[type="checkbox"]') }).first();
    await customerInput.fill('张三');

    // 提交
    const submitBtn = page.getByRole('button', { name: /^提交$/ });
    await submitBtn.click();

    // 跳转到 collection 详情
    await page.waitForURL('**/designer/collections/orders', { timeout: 5000 });

    // POST 被调 + payload 包含 customer
    expect(postedPayload).toBeTruthy();
    expect(postedPayload.customer).toBe('张三');
  });
});
