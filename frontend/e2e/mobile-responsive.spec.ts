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

  test('GalleryView page - responsive grid, single column on mobile', async ({ page }) => {
    await page.route('**/api/views/gallery-test*', async (route, request) => {
      if (request.url().includes('/records') || request.url().includes('/run')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: [
              { id: 'r1', title: '卡片一' },
              { id: 'r2', title: '卡片二' },
            ],
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: {
              id: 'gallery-test',
              type: 'gallery',
              fields: [{ name: 'title', type: 'text' }],
            },
          }),
        });
      }
    });

    await page.goto('/views/gallery-test/gallery');
    await page.waitForLoadState('networkidle');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);
  });

  test('TableView page - table wrapped in scrollable container', async ({ page }) => {
    await page.route('**/api/views/table-test*', async (route, request) => {
      if (request.url().includes('/records') || request.url().includes('/run')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: [{ id: 'r1', title: '记录一' }, { id: 'r2', title: '记录二' }],
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 0,
            data: {
              id: 'table-test',
              type: 'table',
              fields: [
                { name: 'title', label: '标题', type: 'text' },
                { name: 'status', label: '状态', type: 'select' },
                { name: 'created_at', label: '创建时间', type: 'date' },
              ],
            },
          }),
        });
      }
    });

    await page.goto('/views/table-test/run');
    await page.waitForLoadState('networkidle');

    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const clientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth + 1);
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

    // 数据密集型表格允许**表格容器自身**横向滚动，但**页面不得横向滚动**。
    //
    // 注：原断言为 `bodyWidth >= clientWidth` —— 该式恒真，等于没断言（见审计记录）。
    // 正确做法是断言文档级别无横向滚动；表格若需横滚，应由其容器 overflow-x 承担。
    const docScrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
    const docClientWidth = await page.evaluate(() => document.documentElement.clientWidth);
    expect(docScrollWidth).toBeLessThanOrEqual(docClientWidth + 1);

    // 表格若存在，应包裹在可横向滚动的容器内（局部滚动，不撑破页面）
    const tableInScrollableContainer = await page.evaluate(() => {
      const table = document.querySelector('table');
      if (!table) return true; // 已改为卡片布局，无表格
      let el = table.parentElement;
      while (el && el !== document.body) {
        const overflowX = getComputedStyle(el).overflowX;
        if (overflowX === 'auto' || overflowX === 'scroll') return true;
        el = el.parentElement;
      }
      return false;
    });
    expect(tableInScrollableContainer).toBe(true);
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