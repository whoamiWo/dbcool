/**
 * E2E: 表单导出 API 契约测试
 * 覆盖：CSV / Excel 导出接口响应格式、Content-Disposition、鉴权
 * PHASE130: 表单数据导出链路验证（后端 API 契约）
 * 
 * 测试策略：
 * 1. 使用 real backend (http://localhost:4173) → 触发实际 API 调用
 * 2. 使用已存在的 smoke 测试 collection (smoke060c44f2_form)
 * 3. 验证 HTTP 响应头、Content-Disposition、CSV 内容结构
 * 
 * 注意：
 * - export-file (xlsx/pdf) 依赖 Python 导出服务；未启动时后端返回 503，
 *   本测试对此做容错断言（200 或 503 均视为契约正确）。
 * - 不存在的集合当前返回 403（鉴权层先行拦截），非 404。
 */
import { test, expect } from '@playwright/test';
import fs from 'fs';
import path from 'path';

const TOKEN_FILE = path.resolve(process.cwd(), 'e2e/.auth/token.json');
const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:4173';

// 使用 E2E 真实存在的 smoke 测试 collection（有数据且字段定义完整）
const TEST_COLLECTION = 'smoke9dc165decol';

function adminToken(): string {
  const raw = fs.readFileSync(TOKEN_FILE, 'utf-8');
  const token = JSON.parse(raw).token as string;
  if (!token) throw new Error('global setup 未写入有效 token');
  return token;
}

test.describe('表单导出 API 契约', () => {
  let token: string;
  let authHeaders: { Authorization: string };

  test.beforeAll(async () => {
    token = adminToken();
    authHeaders = { Authorization: `Bearer ${token}` };
  });

  test('GET /api/collections/:name/export → 200 + CSV + Content-Disposition', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/api/collections/${TEST_COLLECTION}/export`, {
      headers: authHeaders,
    });

    // 1. 状态码
    expect(response.status()).toBe(200);

    // 2. 响应头
    const contentType = response.headers()['content-type'];
    expect(contentType).toContain('text/csv');

    const disposition = response.headers()['content-disposition'];
    expect(disposition).toContain('attachment');
    expect(disposition).toContain('filename=');
    expect(disposition).toContain(`${TEST_COLLECTION}_`);
    expect(disposition).toContain('.csv');

    // 3. 内容：至少包含一行（CSV 标题行；空集合时仅有标题）
    const body = await response.text();
    const lines = body.trim().split('\n').filter((l) => l.length > 0);
    expect(lines.length).toBeGreaterThanOrEqual(1);
  });

  test('GET /api/collections/:name/export?limit=5 → 行数受 limit 约束', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/api/collections/${TEST_COLLECTION}/export?limit=5`, {
      headers: authHeaders,
    });

    expect(response.status()).toBe(200);
    const body = await response.text();
    const lines = body.trim().split('\n').filter((l) => l.length > 0);
    // header + 最多 5 行数据
    expect(lines.length).toBeLessThanOrEqual(6);
  });

  test('GET /api/collections/:name/export-file?format=xlsx → xlsx 契约（200 或 503）', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/api/collections/${TEST_COLLECTION}/export-file?format=xlsx`, {
      headers: authHeaders,
    });

    // Python 导出服务可能未启动 → 503；启动则 200
    expect([200, 503]).toContain(response.status());

    if (response.status() === 200) {
      const contentType = response.headers()['content-type'];
      expect(contentType).toContain('spreadsheetml');

      const disposition = response.headers()['content-disposition'];
      expect(disposition).toContain('attachment');
      expect(disposition).toContain('.xlsx');

      // XLSX 文件头（ZIP magic number: PK）
      const buffer = await response.body();
      expect(buffer[0]).toBe(0x50); // 'P'
      expect(buffer[1]).toBe(0x4B); // 'K'
    } else {
      // 503 时应返回结构化错误体
      const json = await response.json();
      expect(json.code).toBe(5003);
      expect(json.message).toContain('暂不可用');
    }
  });

  test('GET /api/collections/:name/export-file?format=pdf → pdf 契约（200 或 503）', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/api/collections/${TEST_COLLECTION}/export-file?format=pdf`, {
      headers: authHeaders,
    });

    expect([200, 503]).toContain(response.status());

    if (response.status() === 200) {
      const contentType = response.headers()['content-type'];
      expect(contentType).toContain('pdf');

      const disposition = response.headers()['content-disposition'];
      expect(disposition).toContain('attachment');
      expect(disposition).toContain('.pdf');

      const buffer = await response.body();
      expect(buffer.slice(0, 4)).toEqual(Buffer.from('%PDF'));
    } else {
      const json = await response.json();
      expect(json.code).toBe(5003);
    }
  });

  test('GET /api/collections/不存在的集合/export → 拒绝（403/404）', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/api/collections/nonexistent-collection-123456/export`, {
      headers: authHeaders,
    });

    // 鉴权/资源校验层拦截：403 或 404 均表示"不导出"
    expect([403, 404]).toContain(response.status());
  });

  test('未授权访问 → 401', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/api/collections/${TEST_COLLECTION}/export`);
    expect(response.status()).toBe(401);
  });
});