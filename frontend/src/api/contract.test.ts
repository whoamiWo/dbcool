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
  stack?: string;
}

interface FrontendMapping {
  path: string;
  file: string;
  line: number;
}

function loadJson<T>(path: string): T {
  // __dirname = frontend/src/api → 上三级到仓库根，contracts/ 在仓库根而非 frontend/ 下
  return JSON.parse(readFileSync(resolve(__dirname, '..', '..', '..', path), 'utf-8'));
}

/**
 * Wave0.5 A-8：模式级比对 —— 前端模板段（${id} → {}）与后端 @PathVariable（{id}）
 * 参数名可以不同，但段数与字面量必须一致；查询串不参与比对。
 * 与 scripts/api-contract-verify.py 保持同一口径，避免两套门禁结论打架。
 */
function normalize(path: string): string {
  return path.split('?', 1)[0].replace(/\{[^/]*\}/g, '{}');
}

/**
 * 显式例外：前端模板段实为字面量枚举的合法调用。
 * ${op} ∈ {approve, reject} → 后端 /api/workflows/tasks/{id}/approve|reject
 * 逐条人工维护，禁止默认宽松匹配（否则真漂移会被吞掉）。
 */
const APPROVED_WILDCARDS: Array<{ pattern: string; segIdx: number }> = [
  { pattern: '/api/workflows/tasks/{}/{}', segIdx: 4 },
];

function wildcardMatch(front: string, back: string, segIdx: number): boolean {
  // strip('/') 后分段，与 Python 版 segments 口径一致（'/api/...' → ['api','...']）
  const f = front.replace(/^\//, '').split('/');
  const b = back.replace(/^\//, '').split('/');
  if (f.length !== b.length) return false;
  return f.every((fp, i) => (i === segIdx && fp === '{}') || fp === b[i]);
}

describe('前后端 API 契约比对', () => {
  const backendMappings = loadJson<BackendMapping[]>('contracts/backend-mappings.json');
  const frontendMappings = loadJson<FrontendMapping[]>('contracts/frontend-mappings.json');

  // 后端路径集合（含 /api 前缀，归一化后的模式集合）
  const backendPaths = new Set(backendMappings.map(m => m.path));
  const backendPatterns = new Set(backendMappings.map(m => normalize(m.path)));

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
    const missing = frontendFullPaths.filter(m => {
      const norm = normalize(m.fullPath);
      if (backendPatterns.has(norm)) return false;
      return !APPROVED_WILDCARDS.some(w =>
        w.pattern === norm && [...backendPatterns].some(bp => wildcardMatch(norm, bp, w.segIdx))
      );
    });
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
