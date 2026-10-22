/**
 * PHASE 4.2 覆盖率补测：theme/tokens.ts
 *
 * 设计令牌为纯常量导出，测试聚焦于结构完整性与关键取值契约，
 * 防止后续重构误删令牌导致样式静默失效。
 */
import { describe, it, expect } from 'vitest';
import { themeTokens, cssVariables } from './tokens';

describe('themeTokens', () => {
  it('包含完整的颜色色阶（50-900）', () => {
    for (const scale of ['primary', 'secondary', 'blue'] as const) {
      const group = themeTokens.colors[scale];
      for (const step of [50, 100, 200, 300, 400, 500, 600, 700, 800, 900]) {
        expect(group[step as keyof typeof group]).toMatch(/^#[0-9a-f]{6}$/i);
      }
    }
  });

  it('背景色与文字色符合暗色主题取值', () => {
    expect(themeTokens.colors.background.primary).toBe('#0F172A');
    expect(themeTokens.colors.background.secondary).toBe('#1E293B');
    expect(themeTokens.colors.text.primary).toBe('#F8FAFC');
  });

  it('功能色齐备且为合法十六进制', () => {
    const { success, warning, error, info } = themeTokens.colors;
    for (const c of [success, warning, error, info]) {
      expect(c).toMatch(/^#[0-9A-F]{6}$/i);
    }
  });

  it('毛玻璃三级强度均含必要属性', () => {
    for (const level of ['light', 'medium', 'strong'] as const) {
      const glass = themeTokens.glass[level];
      expect(glass.background).toContain('rgba');
      expect(glass.backdropFilter).toContain('blur');
      expect(glass.boxShadow).toContain('rgba');
    }
  });

  it('圆角/间距/字号/z-index 层级单调递增', () => {
    const { radius, spacing, zIndex } = themeTokens;
    expect(radius.full).toBe('9999px');
    expect(spacing.xs).toBe('4px');
    expect(zIndex.tooltip).toBeGreaterThan(zIndex.modal);
    expect(zIndex.modal).toBeGreaterThan(zIndex.base);
  });

  it('字体族包含中文字体回退链', () => {
    expect(themeTokens.typography.fontFamily).toContain('PingFang SC');
    expect(themeTokens.typography.fontWeight.bold).toBe(700);
  });
});

describe('cssVariables', () => {
  it('主色/背景/文字变量与 tokens 保持一致', () => {
    expect(cssVariables['--color-primary-500']).toBe(themeTokens.colors.primary[500]);
    expect(cssVariables['--color-bg-primary']).toBe('#0f172a');
    expect(cssVariables['--color-text-primary']).toBe('#f8fafc');
  });

  it('所有变量值均为非空字符串', () => {
    for (const [key, value] of Object.entries(cssVariables)) {
      expect(key.startsWith('--')).toBe(true);
      expect(typeof value).toBe('string');
      expect(value.length).toBeGreaterThan(0);
    }
  });

  it('包含圆角/阴影/过渡变量', () => {
    expect(cssVariables['--radius-full' as keyof typeof cssVariables] ?? cssVariables['--radius-xl']).toBeDefined();
    expect(cssVariables['--shadow-md']).toBeDefined();
    expect(cssVariables['--transition-fast']).toBe('150ms ease');
  });
});
