import '@testing-library/jest-dom';
// PHASE 56 P2-1: 全局初始化 i18n（默认 zh-CN）。
// 放在 setup 中，所有测试自动生效，无需每个测试文件单独 import。
// 默认语言必须是 zh-CN —— 既有测试与 E2E 断言的是中文文案，
// 只有默认值与硬编码原文一致，断言才不会被破坏。
import '@/i18n';

// jsdom 的 fetch 不支持相对 URL（无 base 解析），测试环境统一补全为绝对地址
// 方案 A（推荐）：不改生产代码，仅在测试环境修复（避免影响线上域名适配）
const _realFetch = globalThis.fetch;
globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
  if (typeof input === 'string' && input.startsWith('/')) {
    return _realFetch(`http://localhost${input}`, init);
  }
  return _realFetch(input as RequestInfo | URL, init);
}) as typeof globalThis.fetch;
