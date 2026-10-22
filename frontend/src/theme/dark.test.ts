/**
 * PHASE 4.2 覆盖率补测：theme/dark.ts
 *
 * dark 对象是 CSS 变量引用映射，测试确保每个键都指向 var(--*) 形式，
 * 避免出现硬编码色值绕过主题切换。
 */
import { describe, it, expect } from 'vitest';
import { dark } from './dark';

describe('dark theme mapping', () => {
  it('所有取值均为 CSS 变量引用', () => {
    for (const [key, value] of Object.entries(dark)) {
      expect(typeof value, `${key} 应为字符串`).toBe('string');
      expect(value, `${key} 应引用 CSS 变量`).toMatch(/^var\(--[\w-]+\)$/);
    }
  });

  it('背景与文字映射正确', () => {
    expect(dark.bgPrimary).toBe('var(--color-bg-primary)');
    expect(dark.textPrimary).toBe('var(--color-text-primary)');
    expect(dark.borderMedium).toBe('var(--color-border-medium)');
  });

  it('功能色映射正确', () => {
    expect(dark.success).toBe('var(--color-success)');
    expect(dark.warning).toBe('var(--color-warning)');
    expect(dark.error).toBe('var(--color-error)');
    expect(dark.info).toBe('var(--color-info)');
  });

  it('圆角与阴影映射存在', () => {
    expect(dark.radiusSm).toBe('var(--radius-sm)');
    expect(dark.shadow).toBe('var(--shadow-md)');
    expect(dark.glassBlur).toBe('var(--glass-blur)');
  });
});
