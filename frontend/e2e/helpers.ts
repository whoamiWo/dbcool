/**
 * E2E mock helpers — 拦截 /api/* 请求返回固定数据.
 * 避免依赖真实 Spring Boot + H2(快、稳定、CI 友好).
 */
import type { Page, Route } from '@playwright/test';

export interface MockUser {
  id: string;
  username: string;
  tenant_id: string;
  roles: string[];
}

export const ADMIN_USER: MockUser = {
  id: 'u-admin',
  username: 'admin',
  tenant_id: 't1',
  roles: ['admin'],
};

/** Mock POST /api/auth/login → 200 + token. */
export async function mockLoginSuccess(page: Page, user: MockUser = ADMIN_USER) {
  await page.route('**/api/auth/login', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: {
          access_token: 'fake-jwt-token-for-e2e',
          refresh_token: 'fake-refresh-token',
          user,
        },
      }),
    });
  });
}

/** Mock POST /api/auth/login → 401(错误密码). */
export async function mockLoginFailure(page: Page, message = '用户名或密码错误') {
  await page.route('**/api/auth/login', async (route) => {
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ code: 401, message, data: null }),
    });
  });
}

/** Mock /api/users/me → 用户信息. */
export async function mockMe(page: Page, user: MockUser = ADMIN_USER) {
  await page.route('**/api/users/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: user }),
    });
  });
}

/** Mock /api/auth/logout. */
export async function mockLogout(page: Page) {
  await page.route('**/api/auth/logout', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{"code":0,"message":"ok","data":null}' });
  });
}

/** Mock Home 页 4 个查询 endpoint(users/me / collections / workflows / messages). */
export async function mockHomeQueries(page: Page) {
  await mockMe(page);
  await page.route('**/api/collections', async (route) => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: [{ id: 'c1', name: 'orders', display_name: '订单' }] }),
    });
  });
  await page.route('**/api/workflows', async (route) => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: [{ id: 'w1', name: '审批流' }] }),
    });
  });
  await page.route(/\/api\/messages\?.*/, async (route) => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: { unread_count: 0, messages: [] } }),
    });
  });
}

/** Mock /api/users 列表(UsersList 页). */
export async function mockUsersList(page: Page, users: any[] = []) {
  await page.route('**/api/users*', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: users }),
      });
    } else {
      await route.continue();
    }
  });
}
