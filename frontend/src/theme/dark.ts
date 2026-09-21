/** 暗色主题 CSS 变量映射工具函数 */

export const dark = {
  // 背景色
  bgPrimary: 'var(--color-bg-primary)',
  bgSecondary: 'var(--color-bg-secondary)',
  bgTertiary: 'var(--color-bg-tertiary)',
  glassLight: 'var(--glass-bg-light)',
  glassMedium: 'var(--glass-bg-medium)',
  glassStrong: 'var(--glass-bg-strong)',
  // 文字色
  textPrimary: 'var(--color-text-primary)',
  textSecondary: 'var(--color-text-secondary)',
  textMuted: 'var(--color-text-muted)',
  textDisabled: 'var(--color-text-disabled)',
  // 边框色
  borderLight: 'var(--color-border-light)',
  borderMedium: 'var(--color-border-medium)',
  borderDark: 'var(--color-border-dark)',
  // 功能色
  primary500: 'var(--color-primary-500)',
  primary400: 'var(--color-primary-400)',
  primary300: 'var(--color-primary-300)',
  success: 'var(--color-success)',
  warning: 'var(--color-warning)',
  error: 'var(--color-error)',
  info: 'var(--color-info)',
  secondary: 'var(--color-secondary-500)',
  // 阴影
  shadow: 'var(--shadow-md)',
  shadowGlow: 'var(--shadow-glow)',
  // 圆角
  radiusSm: 'var(--radius-sm)',
  radiusMd: 'var(--radius-md)',
  radiusLg: 'var(--radius-lg)',
  // 毛玻璃
  glassBlur: 'var(--glass-blur)',
  glassBorder: 'var(--glass-border)',
} as const;

export type DarkTokens = typeof dark;

/** 按钮样式工厂 */
export const btnStyles = (variant: 'primary' | 'secondary' | 'ghost' = 'primary') => {
  if (variant === 'primary') return { background: 'var(--color-primary-500)', color: '#fff', border: 'none' };
  if (variant === 'secondary') return { background: 'var(--glass-bg-light)', color: 'var(--color-text-primary)', border: '1px solid var(--color-border-medium)' };
  return { background: 'transparent', color: 'var(--color-text-secondary)', border: 'none' };
};

/** 表格行样式 */
export const tableRowStyles = { even: 'var(--color-bg-secondary)', odd: 'var(--color-bg-primary)', header: 'var(--color-bg-tertiary)' };

/** 卡片背景 */
export const cardBg = 'var(--glass-bg-medium)';
export const cardBorder = 'var(--glass-border)';

/** 输入框样式 */
export const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '6px 10px',
  border: '1px solid var(--color-border-medium)',
  borderRadius: 'var(--radius-sm)',
  background: 'var(--color-bg-secondary)',
  color: 'var(--color-text-primary)',
  fontSize: 13,
  outline: 'none',
};

/** 标签样式 */
export const tagStyles = (color: string) => ({
  display: 'inline-flex',
  alignItems: 'center',
  padding: '2px 8px',
  borderRadius: 'var(--radius-sm)',
  background: `${color}20`,
  color,
  fontSize: 11,
  fontWeight: 600,
});