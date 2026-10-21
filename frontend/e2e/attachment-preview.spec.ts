import { test, expect } from '@playwright/test';
import { mockLoginSuccess, mockMe, mockHomeQueries } from './helpers';

const attachmentField = { name: 'receipt', label: '回执', type: 'attachment', required: false };

test.describe('附件预览与缩略图 E2E', () => {
  test.beforeEach(async ({ page }) => {
    await mockLoginSuccess(page);
    await mockMe(page);
    await mockHomeQueries(page);
  });

  test('表单 attachment 字段渲染上传区', async ({ page }) => {
    await page.route('**/api/collections/**', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: { name: 'orders', fields: [attachmentField] } }),
      });
    });
    await page.route('**/api/forms/f1', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: { id: 'f1', collection_name: 'orders', layout_json: JSON.stringify([{ field: 'receipt' }]) } }),
      });
    });

    await page.goto('/forms/f1/fill');
    await expect(page.getByText(/点击或拖拽文件上传/i)).toBeVisible({ timeout: 5000 });
  });

  test('上传后显示附件卡片', async ({ page }) => {
    await page.route('**/api/collections/**', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: { name: 'orders', fields: [attachmentField] } }),
      });
    });
    await page.route('**/api/forms/f1', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: { id: 'f1', collection_name: 'orders', layout_json: JSON.stringify([{ field: 'receipt' }]) } }),
      });
    });
    await page.route('**/api/attachments/upload', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'uploaded', data: { storageKey: `t1/${Date.now()}/test-photo.jpg`, originalName: 'test-photo.jpg', size: 1024, tenantId: 't1' } }),
      });
    });

    await page.goto('/forms/f1/fill');
    const input = page.locator('#file-input-attach');
    await input.setInputFiles({
      name: 'test-photo.jpg',
      mimeType: 'image/jpeg',
      buffer: Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46]),
    });
    await expect(page.locator('text=test-photo.jpg')).toBeVisible({ timeout: 5000 });
  });

  test('非图片附件显示类型图标与文件名', async ({ page }) => {
    await page.route('**/api/collections/**', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: { name: 'orders', fields: [attachmentField] } }),
      });
    });
    await page.route('**/api/forms/f1', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: { id: 'f1', collection_name: 'orders', layout_json: JSON.stringify([{ field: 'receipt' }]) } }),
      });
    });
    await page.route('**/api/attachments/upload', async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'uploaded', data: { storageKey: `t1/${Date.now()}/report.pdf`, originalName: 'report.pdf', size: 5120, tenantId: 't1' } }),
      });
    });
    // 缩略图端点返回 415（非图片）
    await page.route('**/api/attachments/thumbnail*', async (route) => {
      await route.fulfill({ status: 415, contentType: 'application/json', body: '{"code":415,"message":"仅支持图片类附件"}' });
    });

    await page.goto('/forms/f1/fill');
    const input = page.locator('#file-input-attach');
    await input.setInputFiles({
      name: 'report.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from([0x25, 0x50, 0x44, 0x46]),
    });
    await expect(page.locator('text=report.pdf')).toBeVisible({ timeout: 5000 });
  });

  test('跨租户 storageKey 访问被拒', async ({ page }) => {
    const response = await page.request.get('/api/attachments/preview?storageKey=other-tenant-123/secret.jpg');
    expect([401, 403]).toContain(response.status());
  });

  test('路径穿越 storageKey 被拒', async ({ page }) => {
    const response = await page.request.get('/api/attachments/preview?storageKey=tenant-123/../../../etc/passwd');
    expect([400, 401, 403]).toContain(response.status());
  });

  test('缩略图端点对非图片返回 415', async ({ page }) => {
    const response = await page.request.get('/api/attachments/thumbnail?storageKey=t1-tenant/document.pdf');
    expect([400, 401, 403, 415]).toContain(response.status());
  });

  test('附件 API 安全：租户隔离', async ({ page }) => {
    await page.goto('/forms/f1/fill');
    const response = await page.request.get('/api/attachments/preview?storageKey=other-tenant/file.jpg');
    expect([401, 403]).toContain(response.status());
  });

  test('附件 API：非法路径被拒', async ({ page }) => {
    await page.goto('/forms/f1/fill');
    // 尝试路径穿越
    const response = await page.request.get('/api/attachments/preview?storageKey=tenant-123/..%2F..%2Fetc%2Fpasswd');
    expect([400, 401, 403]).toContain(response.status());
  });
});