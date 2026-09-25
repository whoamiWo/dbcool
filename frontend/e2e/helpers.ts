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
  // 同步写入 localStorage + 标记 E2E 环境（阻止 axios 401 重定向）
  await page.addInitScript((u) => {
    (window as any).__isE2E__ = true;
    localStorage.setItem('nocobase_access_token', 'fake-jwt-token-for-e2e');
    localStorage.setItem('nocobase_refresh_token', 'fake-refresh-token');
    localStorage.setItem('nocobase-auth', JSON.stringify({ state: { user: u } }));
  }, user);
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

/** Mock 钉钉授权 URL（POST /api/dingtalk/auth-url）. */
export async function mockDingTalkAuthUrl(page: Page) {
  await page.route('**/api/dingtalk/auth-url', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        data: { authUrl: 'https://oapi.dingtalk.com/oauth2/auth?response_type=code&client_id=mock' },
      }),
    });
  });
}

/** Mock 钉钉组织架构同步（POST /api/dingtalk/sync-org）. */
export async function mockDingTalkSyncOrg(page: Page, synced = 5) {
  await page.route('**/api/dingtalk/sync-org', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: { synced } }),
    });
  });
}

/** Mock Home 页查询 endpoint (拦截 401 → 防止 axios 拦截器触发 window.location.href='/login'). */
export async function mockHomeQueries(page: Page) {
  await mockMe(page);
  await page.route('**/api/collections', async (route) => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: [{ id: 'c1', name: 'orders', display_name: '订单' }] }),
    });
  });
  await page.route('**/api/workflows*', async (route) => {
    const url = route.request().url();
    if (url.includes('/tasks/my')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: { total: 0, items: [] } }) });
    } else if (url.includes('/instances')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: [] }) });
    } else {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: [{ id: 'w1', name: '审批流' }] }),
      });
    }
  });
  await page.route(/\/api\/messages\?.*/, async (route) => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: { unread_count: 0, messages: [] } }),
    });
  });
  await page.route('**/api/audit/logs', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: [] }) });
  });
  await page.route('**/api/admin/tenants', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: [{ id: 't1', name: 'default', status: 'ACTIVE' }] }) });
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

/** Mock 知识库列表 + 创建 + 单个知识库(POST /api/wiki/kb, GET /api/wiki/kb, GET /api/wiki/kb/:id). */
export async function mockWikiKb(page: Page, kbs: any[] = [{ id: 'kb1', name: '测试知识库', slug: 'test-kb', description: '测试' }]) {
  await page.route(/\/api\/wiki\/kb(\/[^/?]+)?(?:$|\/categories)/, async (route) => {
    const url = route.request().url();
    const method = route.request().method();
    // 单个知识库: /api/wiki/kb/<id>（不含末尾 /categories 等）
    const idMatch = url.match(/\/api\/wiki\/kb\/([^/?]+)(?:$|\/categories)/);
    if (method === 'GET' && idMatch) {
      const kb = kbs.find(k => k.id === idMatch[1]) || kbs[0];
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: kb }),
      });
    } else if (method === 'GET') {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: kbs }),
      });
    } else if (method === 'POST') {
      const body = JSON.parse(route.request().postData() || '{}');
      const newKb = { id: `kb-${Date.now()}`, ...body };
      kbs.push(newKb);
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: newKb }),
      });
    } else if (method === 'DELETE') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: null }) });
    } else {
      await route.continue();
    }
  });
}

/** Mock 知识库权限校验: 403 除 admin 外. */
export async function mockWikiKbPermission(page: Page, user: MockUser = ADMIN_USER) {
  await page.route(/\/api\/wiki\/kb(\/[^/?]+)?(?:$|\/categories)/, async (route) => {
    if (user.roles.includes('admin')) {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: { id: 'kb1', name: '私有知识库', slug: 'private-kb', description: '' } }),
      });
    } else {
      await route.fulfill({
        status: 403, contentType: 'application/json',
        body: JSON.stringify({ code: 403, message: '无权限访问', data: null }),
      });
    }
  });
  // 私有知识库详情页: 管理员可见，普通用户 403
  // 路由已覆盖 **/api/wiki/kb/** 通配，无需额外处理
}

/** Mock 文档列表 + 创建 + 按 slug 取页面 + 版本 + 搜索. */
export async function mockWikiPages(page: Page, seed: any[] = []) {
  let pages: any[] = [...seed];
  await page.route(/\/api\/wiki\/pages(\/[^/?]+)?/, async (route) => {
    const url = route.request().url();
    const method = route.request().method();
    if (url.includes('/versions')) {
      const pid = url.match(/\/api\/wiki\/pages\/([^/]+)\/versions/)?.[1];
      const page = pages.find(p => p.id === pid) || pages[0];
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: [{ id: 'v1', version: 1, title: page?.title || '测试文档', created_at: '2026-09-24T10:00:00Z' }] }),
      });
    } else if (url.includes('/by-slug/')) {
      const slug = url.split('/by-slug/')[1].split('?')[0];
      const page = pages.find(p => p.slug === slug) || { id: 'p-test', slug, title: '测试文档', content: '# 测试内容\n\nHello Wiki' };
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: page }),
      });
    } else if (method === 'POST') {
      const body = JSON.parse(route.request().postData() || '{}');
      const newPage = { id: `p-${Date.now()}`, ...body };
      pages.push(newPage);
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: newPage }),
      });
    } else if (method === 'GET') {
      const kbId = new URL(url).searchParams.get('kbId');
      const filtered = kbId ? pages.filter(p => p.knowledge_base_id === kbId) : pages;
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: filtered }),
      });
    } else if (method === 'DELETE' || method === 'PUT') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: null }) });
    } else {
      await route.continue();
    }
  });

  await page.route(/\/api\/wiki\/search/, async (route) => {
    const url = new URL(route.request().url());
    const q = url.searchParams.get('q') || '';
    const results = pages.filter(p => p.title?.includes(q) || p.content?.includes(q));
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', data: results, total: results.length }),
    });
  });
}


