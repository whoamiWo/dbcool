import { test, expect } from '@playwright/test';
import { mockDingTalkAuthUrl, mockDingTalkSyncOrg } from './helpers';

const BASE = 'http://localhost:4173';

test.describe('DingTalk Login E2E', () => {
  test('钉钉登录页面渲染正确', async ({ page }) => {
    await page.goto('/auth/dingtalk');

    // 检查钉钉登录按钮
    await expect(page.locator('button:has-text("钉钉扫码登录")')).toBeVisible();
    await expect(page.locator('h5')).toContainText('钉钉登录');
  });

  test('获取钉钉授权 URL 成功', async ({ page }) => {
    await mockDingTalkAuthUrl(page);
    const data = await page.evaluate(async (base) => {
      const r = await fetch(`${base}/api/dingtalk/auth-url`, { method: 'POST' });
      return r.json();
    }, BASE);

    expect(data.code).toBe(0);
    expect(data.data.authUrl).toContain('https://oapi.dingtalk.com/oauth2/auth');
  });

  test('钉钉组织架构同步接口响应', async ({ page }) => {
    await mockDingTalkSyncOrg(page, 5);
    const data = await page.evaluate(async (base) => {
      const r = await fetch(`${base}/api/dingtalk/sync-org`, { method: 'POST' });
      return r.json();
    }, BASE);

    expect(data.code).toBe(0);
    expect(data.data).toHaveProperty('synced');
  });
});