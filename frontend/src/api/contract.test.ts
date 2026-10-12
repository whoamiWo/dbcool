import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { resolve } from 'path';

/**
 * PHASE97 T3: 真契约测试 —— 前端调用路径 vs 后端 @RequestMapping 映射
 *
 * 数据来源：
 * - 后端：contracts/backend-mappings.json（由 scripts/export-backend-mappings.py 生成）
 * - 前端：contracts/frontend-mappings.json（由 scripts/extract-frontend-mappings.py 生成）
 *
 * 这个测试不是自反射：它比对两个独立来源的清单，任一端点路径漂移都会 FAIL。
 * 替代了原 endpoints.contract.test.ts（断言常量等于自己，恒真）。
 */

interface BackendMapping {
  class: string;
  method: string;
  path: string;
  file: string;
  line: number;
}

interface FrontendMapping {
  path: string;
  file: string;
  line: number;
}

function loadJson<T>(path: string): T {
  return JSON.parse(readFileSync(resolve(__dirname, '..', '..', path), 'utf-8'));
}

describe('前后端 API 契约比对', () => {
  const backendMappings = loadJson<BackendMapping[]>('contracts/backend-mappings.json');
  const frontendMappings = loadJson<FrontendMapping[]>('contracts/frontend-mappings.json');

  // 后端路径集合（含 /api 前缀）
  const backendPaths = new Set(backendMappings.map(m => m.path));

  // 前端路径补上 baseURL /api 前缀后与后端比对
  const frontendFullPaths = frontendMappings.map(m => ({
    ...m,
    fullPath: `/api${m.path}`,
  }));

  it('后端映射清单非空（防止脚本静默失败）', () => {
    expect(backendMappings.length).toBeGreaterThan(100);
  });

  it('前端映射清单非空（防止脚本静默失败）', () => {
    expect(frontendMappings.length).toBeGreaterThan(10);
  });

  it('前端每个调用路径都在后端存在（消除路径错位）', () => {
    const missing = frontendFullPaths.filter(m => !backendPaths.has(m.fullPath));
    const details = missing.map(m =>
      `${m.fullPath} ← ${m.file}:${m.line}`
    ).join('\n');
    expect(missing, `以下前端调用在后端无对应映射：\n${details}`).toHaveLength(0);
  });

  it('前端路径不含双前缀 /api/api（baseURL 已带 /api）', () => {
    const doublePrefixed = frontendMappings.filter(m => m.path.startsWith('/api/'));
    const details = doublePrefixed.map(m =>
      `${m.path} ← ${m.file}:${m.line}`
    ).join('\n');
    expect(doublePrefixed, `以下前端路径重复携带 /api 前缀：\n${details}`).toHaveLength(0);
  });

  it('后端关键端点（auth/wiki/im/bi）确实存在于映射中', () => {
    const required = [
      '/api/auth/login',
      '/api/wiki/kb',
      '/api/im/messages',
      '/api/bi/reports',
    ];
    const missing = required.filter(p => !backendPaths.has(p));
    expect(missing, `以下关键端点缺失：\n${missing.join('\n')}`).toHaveLength(0);
  });
});
