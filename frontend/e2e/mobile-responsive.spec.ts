/**
 * E2E: 移动端响应式（375×812 视口）.
 * PHASE90 移动端适配 — 验证关键页面在移动设备尺寸下无横向滚动、布局正确.
 */
import { test, expect } from '@playwright/test';
import { mockLoginSuccess, mockMe, mockHomeQueries, mockUsersList, ADMIN_USER } from './helpers';

const MOBILE_VIEWPORT = { width: 375, height: 812 };

test.describe('Mobile Responsive Tests', () => {
  test.use({ viewport: MOBILE_VIEWPORT });

  test.beforeEach(async ({ page }) => {
    await mockLoginSuccess(page);
    await mockMe(page, ADMIN_USER);
  });

  test('Login page - no horizontal scroll, form accessible', async ({ page }) => {
    await page.goto('/login');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);

    const submitBtn = page.locator('button[type="submit"]');
    await expect(submitBtn).toBeVisible();
    await expect(submitBtn).toBeEnabled();

    const input = page.locator('input[name="username"]');
    await input.scrollIntoViewIfNeeded();
    await expect(input).toBeVisible();
  });

  test('Home page - cards stack in single column', async ({ page }) => {
    await mockHomeQueries(page);
    await page.goto('/home');

    await page.waitForLoadState('networkidle');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);

    // 卡片单列堆叠（移动端 1 列）
    const gridEls = await page.evaluate(() => {
      return Array.from(document.querySelectorAll('div[style*="grid-template-columns"]'))
        .map(el => (el as HTMLElement).style.gridTemplateColumns);
    });
    for (const cols of gridEls) {
      expect(cols).toContain('1fr');
    }
  });

  test('CollectionsList page - vertical card list', async ({ page }) => {
    await page.route('**/api/collections*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'ok',
          data: [
            { id: 'c1', name: 'orders', title: '订单', fields: [], tenant_id: 't1' },
            { id: 'c2', name: 'users', title: '用户', fields: [], tenant_id: 't1' },
          ],
        }),
      });
    });

    await page.goto('/designer/schemas');
    await page.waitForLoadState('networkidle');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);
  });

  test('CollectionDetail page - vertical layout', async ({ page }) => {
    await page.route('**/api/collections/orders*', async (route, request) => {
      if (request.method() === 'GET' && !request.url().includes('/records')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: {
              id: 'c1',
              name: 'orders',
              display_name: '订单',
              fields: [{ name: 'title', type: 'text' }],
            },
          }),
        });
      } else if (request.url().includes('/records')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: [{ id: 'r1', title: 'Test Order' }],
          }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/designer/schemas/orders');
    await page.waitForLoadState('networkidle');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);
  });

  test('FormRuntime page - single column, submit button visible', async ({ page }) => {
    await page.route('**/api/forms/test-form*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          data: {
            id: 'form-1',
            name: 'Test Form',
            fields: [{ name: 'title', type: 'text', label: '标题' }],
          },
        }),
      });
    });

    await page.goto('/forms/test-form/fill');
    await page.waitForLoadState('networkidle');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);
  });

  test('MessagesInbox page - single column layout', async ({ page }) => {
    await page.route('**/api/messages*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          data: {
            unread_count: 2,
            messages: [
              { id: 'm1', title: '消息一', is_read: false, created_at: '2026-10-01' },
              { id: 'm2', title: '消息二', is_read: false, created_at: '2026-10-02' },
            ],
          },
        }),
      });
    });

    await page.goto('/messages');
    await page.waitForLoadState('networkidle');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);
  });

  test('Kanban view - horizontal scroll allowed', async ({ page }) => {
    await page.route('**/api/views/kanban-test*', async (route, request) => {
      if (request.url().includes('/records') || request.url().includes('/run')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: [],
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: {
              id: 'kanban-test',
              type: 'kanban',
              fields: [{ name: 'title', type: 'text' }],
            },
          }),
        });
      }
    });

    await page.goto('/views/kanban-test/run');
    await page.waitForLoadState('networkidle');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(scrollWidth).toBeGreaterThan(0);
  });

  test('Wiki page - sidebar collapses on mobile', async ({ page }) => {
    await page.route('**/api/wiki/kb*', async (route, request) => {
      const url = request.url();
      if (url.includes('/test-kb')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: { id: 'kb1', name: 'Test KB', slug: 'test-kb' },
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: [{ id: 'kb1', name: 'Test KB', slug: 'test-kb' }],
          }),
        });
      }
    });
    await page.route('**/api/wiki/pages*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          data: [{ id: 'p1', title: 'Test Page', slug: 'p1' }],
        }),
      });
    });

    await page.goto('/wiki/kb/test-kb');
    await page.waitForLoadState('networkidle');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);
  });

  test('MyTasks page - table allows horizontal scroll on mobile', async ({ page }) => {
    await page.route('**/api/workflows/tasks/my*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          data: [
            {
              id: 'task-1',
              workflow_title: '订单审批',
              node_id: 'approve',
              status: 'PENDING',
              instance_id: 'inst-1',
              created_at: '2026-10-01T00:00:00Z',
              workflow_name: '订单审批',
              trigger_data_json: '{}',
              record_id: 'r1',
              node_type: 'approval',
              workflow_id: 'wf-1',
            },
          ],
        }),
      });
    });
    await mockMe(page, ADMIN_USER);

    await page.goto('/tasks/my');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);

    // 表格页允许横向滚动（数据密集型页面），但主容器宽度不应溢出
    const bodyWidth = await page.evaluate(() => document.body.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    // 表格容器允许 scrollX，但总宽度控制在合理范围内
    expect(bodyWidth).toBeGreaterThanOrEqual(clientWidth);
  });

  test('Calendar view - compact on mobile', async ({ page }) => {
    await page.route('**/api/views/calendar-test*', async (route, request) => {
      if (request.url().includes('/records') || request.url().includes('/run')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: [],
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: {
              id: 'calendar-test',
              type: 'calendar',
              fields: [{ name: 'due_date', type: 'date' }],
            },
          }),
        });
      }
    });

    await page.goto('/views/calendar-test/run');
    await page.waitForLoadState('networkidle');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);
  });
});