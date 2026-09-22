import '@testing-library/jest-dom';

// jsdom 的 fetch 不支持相对 URL（无 base 解析），测试环境统一补全为绝对地址
// 方案 A（推荐）：不改生产代码，仅在测试环境修复（避免影响线上域名适配）
const _realFetch = globalThis.fetch;
globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' && input.startsWith('/')) {
    return _realFetch(`http://localhost${input}`, init);
  }
  return _realFetch(input as RequestInfo | URL, init);
}) as typeof globalThis.fetch;
