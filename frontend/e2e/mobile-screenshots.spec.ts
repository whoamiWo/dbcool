/**
 * 母囊截图生成脚本
 * 用于生成 PHASE90 移动端响应式截图
 */
import { test } from '@playwright/test';

const MOBILE_VIEWPORT = { width: 375, height: 812 };

test.describe('Mobile Screenshots', () => {
  test.use({ viewport: MOBILE_VIEWPORT });

  const pages = [
    { name: 'login', path: '/login' },
    { name: 'home', path: '/home' },
    { name: 'collections', path: '/designer/schemas' },
    { name: 'my-tasks', path: '/tasks/my' },
    { name: 'messages', path: '/messages' },
    { name: 'wiki', path: '/wiki/kb' },
    { name: 'calendar', path: '/views/calendar-test/run' },
    { name: 'kanban', path: '/views/kanban-test/run' },
    { name: 'form', path: '/forms/test-form/fill' },
  ];

  for (const page of pages) {
    test(`${page.name} page screenshot`, async ({ page: pg }) => {
      await pg.goto(page.path);
      await pg.waitForLoadState('networkidle');
      await pg.waitForTimeout(500);
      await pg.screenshot({ path: `test-results/screenshots/${page.name}.png` });
    });
  }
});