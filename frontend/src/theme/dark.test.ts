import { describe, it, expect } from 'vitest';
import { dark, btnStyles, tableRowStyles, cardBg, cardBorder, inputStyle, tagStyles } from '../theme/dark';

describe('dark theme', () => {
  it('导出完整的 dark tokens', () => {
    expect(dark.bgPrimary).toBe('var(--color-bg-primary)');
    expect(dark.textPrimary).toBe('var(--color-text-primary)');
    expect(dark.primary500).toBe('var(--color-primary-500)');
    expect(Object.keys(dark).length).toBeGreaterThan(20);
  });

  it('btnStyles primary', () => {
    const style = btnStyles('primary');
    expect(style.background).toBe('var(--color-primary-500)');
    expect(style.color).toBe('#fff');
  });

  it('btnStyles secondary', () => {
    const style = btnStyles('secondary');
    expect(style.background).toBe('var(--glass-bg-light)');
    expect(style.border).toContain('solid');
  });

  it('btnStyles 默认返回 primary', () => {
    const style = btnStyles();
    expect(style.background).toBe('var(--color-primary-500)');
  });

  it('btnStyles ghost', () => {
    const style = btnStyles('ghost');
    expect(style.background).toBe('transparent');
    expect(style.border).toBe('none');
  });

  it('tableRowStyles 包含三种状态', () => {
    expect(tableRowStyles.even).toBe('var(--color-bg-secondary)');
    expect(tableRowStyles.odd).toBe('var(--color-bg-primary)');
    expect(tableRowStyles.header).toBe('var(--color-bg-tertiary)');
  });

  it('cardBg 和 cardBorder', () => {
    expect(cardBg).toBe('var(--glass-bg-medium)');
    expect(cardBorder).toBe('var(--glass-border)');
  });

  it('inputStyle 包含必要属性', () => {
    expect(inputStyle.width).toBe('100%');
    expect(inputStyle.padding).toBe('6px 10px');
    expect(inputStyle.borderRadius).toBe('var(--radius-sm)');
  });

  it('tagStyles 返回正确结构', () => {
    const style = tagStyles('#ff0000');
    expect(style.display).toBe('inline-flex');
    expect(style.padding).toBe('2px 8px');
    expect(style.background).toBe('#ff000020');
    expect(style.color).toBe('#ff0000');
  });
});
