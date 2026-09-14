/**
 * E2E: WorkflowDesigner 工作流设计器
 * 覆盖 WorkflowsList 列表 → 新建工作流 → 元数据填写 → 保存
 * Week 40 Step E1
 */
import { test, expect } from '@playwright/test';
import { mockLoginSuccess, mockMe } from './helpers';

const sampleWorkflow = {
  id: 'wf-1',
  name: 'order_approval',
  title: '订单审批',
  description: '订单提交后自动审批',
  collection_name: 'orders',
  enabled: true,
  trigger_json: '{"type":"on_create"}',
  nodes_json: '[]',
  edges_json: '[]',
  tenant_id: 't1',
  created_at: '2026-01-01T00:00:00Z',
  updated_at: null,
};

/** 列表 vs 详情判断 helper. */
function isListUrl(url: string): boolean {
  // URL 末尾是 /workflows 或 /workflows/(带 trailing slash),但不能是 /workflows/<id>
  const pathname = new URL(url.split('?')[0]).pathname;
  return /\/api\/workflows\/?$/.test(pathname);
}
function isWf1Url(url: string): boolean {
  const path = url.split('?')[0];
  // URL 末尾必须是 /workflows/wf-1(支持 trailing slash)
  return /\/api\/workflows\/wf-1\/?$/.test(new URL(path).pathname);
}

test.describe('WorkflowDesigner E2E', () => {
  test.beforeEach(async ({ page }) => {
    await mockLoginSuccess(page);
    await mockMe(page);
  });

  test('WorkflowsList:显示已有工作流', async ({ page }) => {
    await page.route('**/api/workflows*', async (route) => {
      if (isListUrl(route.request().url())) {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: [sampleWorkflow] }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/designer/workflows');
    await expect(page.getByText('订单审批')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('order_approval')).toBeVisible();
  });

  test('WorkflowsList:空状态', async ({ page }) => {
    await page.route('**/api/workflows*', async (route) => {
      if (isListUrl(route.request().url())) {
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 0, message: 'ok', data: [] }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/designer/workflows');
    await expect(page.getByText(/暂无工作流/)).toBeVisible({ timeout: 5000 });
  });

  test('进 WorkflowDesigner:看到元数据表单 + 节点面板', async ({ page }) => {
    await page.goto('/designer/workflows/new');

    await expect(page.getByText(/技术名/)).toBeVisible({ timeout: 5000 });
    await expect(page.getByText(/Collection/)).toBeVisible();
    await expect(page.getByText(/触发器/)).toBeVisible();
    await expect(page.getByText(/启用/)).toBeVisible();

    await expect(page.getByText('📝 审批')).toBeVisible();
    await expect(page.getByText('🔔 通知')).toBeVisible();
    await expect(page.getByText('🔀 条件')).toBeVisible();
    await expect(page.getByText('🌐 HTTP')).toBeVisible();

    await expect(page.getByRole('button', { name: /💾 保存/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /测试运行/ })).toBeVisible();
  });

  test('新建工作流:填元数据 + 保存 + POST + 跳转', async ({ page }) => {
    let postedBody: any = null;

    await page.route('**/api/workflows*', async (route) => {
      if (route.request().method() === 'POST' && isListUrl(route.request().url())) {
        postedBody = JSON.parse(route.request().postData() ?? '{}');
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({
            code: 0, message: 'ok',
            data: { ...sampleWorkflow, id: 'wf-new', name: postedBody.name },
          }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/designer/workflows/new');

    const nameInput = page.locator('input').first();
    await nameInput.fill('custom_workflow');
    const titleInput = page.locator('input').nth(1);
    await titleInput.fill('自定义工作流');
    const collectionInput = page.locator('input').nth(2);
    await collectionInput.fill('customer');
    const triggerSelect = page.locator('select').first();
    await triggerSelect.selectOption('on_update');

    await page.getByRole('button', { name: /💾 保存/ }).click();

    await page.waitForURL('**/designer/workflows', { timeout: 5000 });

    expect(postedBody).toBeTruthy();
    expect(postedBody.name).toBe('custom_workflow');
    expect(postedBody.title).toBe('自定义工作流');
    expect(postedBody.collectionName).toBe('customer');
    expect(postedBody.trigger).toContain('on_update');
    expect(postedBody.enabled).toBe(true);
  });

  test('编辑已有工作流:加载元数据', async ({ page }) => {
    // 直接拦 /api/workflows/wf-1
    await page.route('**/api/workflows/wf-1', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: sampleWorkflow }),
      });
    });

    await page.goto('/designer/workflows/wf-1/edit');

    // input.value 由 useEffect 填充
    await expect(page.locator('input').first()).toHaveValue('order_approval', { timeout: 5000 });
    await expect(page.locator('input').nth(1)).toHaveValue('订单审批');
    await expect(page.locator('input').nth(2)).toHaveValue('orders');
    await expect(page.locator('select').first()).toHaveValue('on_create');
  });
});
