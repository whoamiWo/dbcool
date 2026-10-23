/**
 * PHASE 4.2 覆盖率补测：theme/index.ts
 *
 * 该文件主体为 MUI 主题配置（常量 + createTheme 调用），导入即执行，
 * 因此本测试以"导出契约校验"为主：确保暗色主题构建成功、调色板与
 * 关键 token 符合预期，避免主题被误改导致全站样式回归。
 */
import { describe, it, expect } from 'vitest';
import darkTheme, { darkTheme as namedTheme } from './index';

describe('theme', () => {
  it('默认导出与命名导出为同一主题实例', () => {
    expect(darkTheme).toBe(namedTheme);
  });

  it('构建出 MUI theme 对象（含 palette / typography / shape）', () => {
    expect(darkTheme).toBeDefined();
    expect(darkTheme.palette).toBeDefined();
    expect(darkTheme.typography).toBeDefined();
    expect(darkTheme.shape).toBeDefined();
  });

  it('为暗色模式（palette.mode = dark）', () => {
    expect(darkTheme.palette.mode).toBe('dark');
  });

  it('palette 主色/背景/文本均已定义', () => {
    expect(darkTheme.palette.primary?.main).toBeTruthy();
    expect(darkTheme.palette.background?.default).toBeTruthy();
    expect(darkTheme.palette.text?.primary).toBeTruthy();
  });

  it('spacing 可用（MUI 默认 8px 基准）', () => {
    expect(typeof darkTheme.spacing).toBe('function');
    expect(darkTheme.spacing(1)).toBe('8px');
  });

  it('breakpoints 已配置', () => {
    expect(darkTheme.breakpoints).toBeDefined();
    expect(darkTheme.breakpoints.values).toBeDefined();
  });
});
