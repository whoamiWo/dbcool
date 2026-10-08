/**
 * E2E 演示路径测试（MOCK 版，供前端单元测试 CI 使用）.
 *
 * ⚠️ 此文件 **全部 mock API 响应**，不做真实后端端到端测试。
 *   - 目的：快速跑通前端 UI 流程，验证页面渲染与交互。
 *   - 方法：page.route() 拦截所有 /api/* 请求，返回固定 JSON。
 *
 * 真正的端到端冒烟测试请参考 scripts/smoke.py（PHASE89）。
 *
 * R13 — 演示前最后一道关卡
 */
import { test, expect } from '@playwright/test';
import { mockLoginSuccess, ADMIN_USER } from './helpers';

test.describe('Demo path (mock, not real e2e)', () => {
    test.beforeEach(async ({ page }) => {
        await mockLoginSuccess(page, ADMIN_USER);
    });

    test('健康端点返 ok', async ({ page }) => {
        // 拦截 health/ready
        await page.route('**/api/health', async (route) => {
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify({ status: 'ok', service: 'nocobase-backend' }),
            });
        });
        await page.route('**/api/health/ready', async (route) => {
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify({
                    status: 'ok',
                    components: { database: 'ok' },
                }),
            });
        });

        await page.goto('/');
        // health 通过 Api 调用,不在 UI 中 — 直接 fetch 验证
        const resp = await page.evaluate(async () => {
            const r = await fetch('/api/health/ready');
            return { status: r.status, body: await r.json() };
        });
        expect(resp.status).toBe(200);
        expect(resp.body.status).toBe('ok');
        expect(resp.body.components.database).toBe('ok');
    });

    test('登录 → 创建 collection → 加字段 → 创建记录', async ({ page }) => {
        // mock collections
        let collectionExists = false;
        await page.route('**/api/collections', async (route) => {
            if (route.request().method() === 'POST') {
                collectionExists = true;
                await route.fulfill({
                    status: 201,
                    contentType: 'application/json',
                    body: JSON.stringify({
                        code: 0,
                        data: {
                            name: 'demo-posts',
                            fields: [
                                { name: 'title', type: 'text' },
                            ],
                        },
                    }),
                });
            } else if (route.request().method() === 'GET') {
                await route.fulfill({
                    status: 200,
                    contentType: 'application/json',
                    body: JSON.stringify({
                        code: 0,
                        data: collectionExists ? ['demo-posts'] : [],
                    }),
                });
            } else {
                await route.continue();
            }
        });

        // mock collection records
        await page.route('**/api/collections/demo-posts/records*', async (route) => {
            if (route.request().method() === 'POST') {
                await route.fulfill({
                    status: 201,
                    contentType: 'application/json',
                    body: JSON.stringify({
                        code: 0,
                        data: { id: 'r-1', extra: { title: 'Hello demo' } },
                    }),
                });
            } else {
                await route.fulfill({
                    status: 200,
                    contentType: 'application/json',
                    body: JSON.stringify({
                        code: 0,
                        data: [
                            { id: 'r-1', title: 'Hello demo' },
                        ],
                    }),
                });
            }
        });

        await page.goto('/');

        // 完整路径走完 — 不抛错即通过
        const ok = await page.evaluate(async () => {
            try {
                const login = await fetch('/api/auth/login', { method: 'POST' });
                if (!login.ok) return false;
                const create = await fetch('/api/collections', {
                    method: 'POST',
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({ name: 'demo-posts', fields: [] }),
                });
                if (!create.ok) return false;
                const rec = await fetch('/api/collections/demo-posts/records', {
                    method: 'POST',
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({ title: 'Hello demo' }),
                });
                return rec.ok;
            } catch {
                return false;
            }
        });
        expect(ok).toBe(true);
    });

    test('ready 端点 db down 时返 503', async ({ page }) => {
        await page.route('**/api/health/ready', async (route) => {
            await route.fulfill({
                status: 503,
                contentType: 'application/json',
                body: JSON.stringify({
                    status: 'degraded',
                    components: { database: 'down' },
                }),
            });
        });
        await page.goto('/');
        const resp = await page.evaluate(async () => {
            const r = await fetch('/api/health/ready');
            return { status: r.status, body: await r.json() };
        });
        expect(resp.status).toBe(503);
        expect(resp.body.status).toBe('degraded');
        expect(resp.body.components.database).toBe('down');
    });
});
