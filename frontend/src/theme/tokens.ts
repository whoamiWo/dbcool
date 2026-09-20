/**
 * Glassmorphism Dark Theme Design Tokens
 * Enterprise Collaboration Platform
 */

export const themeTokens = {
  // 颜色系统
  colors: {
    // 主色调 (Primary)
    primary: {
      50: '#eef2ff',
      100: '#e0e7ff',
      200: '#c7d2fe',
      300: '#a5b4fc',
      400: '#818cf8',
      500: '#6366f1',
      600: '#4f46e5',
      700: '#4338ca',
      800: '#3730a3',
      900: '#312e81',
    },
    
    // 辅助色 (Secondary)
    secondary: {
      50: '#faf5ff',
      100: '#f3e8ff',
      200: '#e9d5ff',
      300: '#d8b4fe',
      400: '#c084fc',
      500: '#a855f7',
      600: '#9333ea',
      700: '#7e22ce',
      800: '#6b21a8',
      900: '#581c87',
    },
    
    // 蓝色系 (Blue)
    blue: {
      50: '#eff6ff',
      100: '#dbeafe',
      200: '#bfdbfe',
      300: '#93c5fd',
      400: '#60a5fa',
      500: '#3b82f6',
      600: '#2563eb',
      700: '#1d4ed8',
      800: '#1e40af',
      900: '#1e3a8a',
    },
    
    // 背景色系 (Backgrounds)
    background: {
      primary: '#0F172A',
      secondary: '#1E293B',
      tertiary: '#334155',
      elevated: '#475569',
    },
    
    // 文字色系 (Text)
    text: {
      primary: '#F8FAFC',
      secondary: '#E2E8F0',
      muted: '#94A3B8',
      disabled: '#64748B',
    },
    
    // 功能色 (Functional)
    success: '#10B981',
    warning: '#F59E0B',
    error: '#EF4444',
    info: '#3B82F6',
    
    // 边框色 (Borders)
    border: {
      light: 'rgba(255, 255, 255, 0.1)',
      medium: 'rgba(255, 255, 255, 0.15)',
      dark: 'rgba(255, 255, 255, 0.2)',
    },
  },
  
  // 毛玻璃效果 (Glassmorphism)
  glass: {
    light: {
      background: 'rgba(255, 255, 255, 0.05)',
      backdropFilter: 'blur(10px)',
      border: '1px solid rgba(255, 255, 255, 0.1)',
      boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)',
    },
    medium: {
      background: 'rgba(30, 41, 59, 0.7)',
      backdropFilter: 'blur(12px)',
      border: '1px solid rgba(255, 255, 255, 0.15)',
      boxShadow: '0 8px 32px rgba(0, 0, 0, 0.4)',
    },
    strong: {
      background: 'rgba(15, 23, 42, 0.8)',
      backdropFilter: 'blur(16px)',
      border: '1px solid rgba(255, 255, 255, 0.2)',
      boxShadow: '0 12px 48px rgba(0, 0, 0, 0.5)',
    },
  },
  
  // 阴影 (Shadows)
  shadows: {
    sm: '0 1px 2px rgba(0, 0, 0, 0.3)',
    md: '0 4px 16px rgba(0, 0, 0, 0.4)',
    lg: '0 8px 32px rgba(0, 0, 0, 0.5)',
    xl: '0 16px 64px rgba(0, 0, 0, 0.6)',
    glow: '0 0 24px rgba(99, 102, 241, 0.4)',
  },
  
  // 圆角 (Border Radius)
  radius: {
    sm: '4px',
    md: '8px',
    lg: '12px',
    xl: '16px',
    full: '9999px',
  },
  
  // 间距 (Spacing)
  spacing: {
    xs: '4px',
    sm: '8px',
    md: '16px',
    lg: '24px',
    xl: '32px',
    xxl: '48px',
  },
  
  // 排版 (Typography)
  typography: {
    fontFamily: '"PingFang SC", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
    fontSize: {
      xs: '12px',
      sm: '14px',
      md: '16px',
      lg: '18px',
      xl: '24px',
      xxl: '32px',
    },
    fontWeight: {
      normal: 400,
      medium: 500,
      semibold: 600,
      bold: 700,
    },
    lineHeight: {
      tight: 1.25,
      normal: 1.5,
      relaxed: 1.75,
    },
  },
  
  // 动画 (Animations)
  transitions: {
    fast: '150ms ease',
    normal: '200ms ease',
    slow: '300ms ease',
    spring: '300ms cubic-bezier(0.4, 0, 0.2, 1)',
  },
  
  // z-index 层级
  zIndex: {
    base: 0,
    dropdown: 100,
    sticky: 200,
    fixed: 300,
    modalBackdrop: 400,
    modal: 500,
    popover: 600,
    tooltip: 700,
  },
};

// CSS 变量映射
export const cssVariables = {
  '--color-primary-500': '#6366f1',
  '--color-primary-600': '#4f46e5',
  '--color-secondary-500': '#a855f7',
  '--color-success': '#10b981',
  '--color-warning': '#f59e0b',
  '--color-error': '#ef4444',
  '--color-info': '#3b82f6',
  
  '--color-bg-primary': '#0f172a',
  '--color-bg-secondary': '#1e293b',
  '--color-bg-tertiary': '#334155',
  '--color-bg-elevated': '#475569',
  
  '--color-text-primary': '#f8fafc',
  '--color-text-secondary': '#e2e8f0',
  '--color-text-muted': '#94a3b8',
  '--color-text-disabled': '#64748b',
  
  '--color-border-light': 'rgba(255, 255, 255, 0.1)',
  '--color-border-medium': 'rgba(255, 255, 255, 0.15)',
  '--color-border-dark': 'rgba(255, 255, 255, 0.2)',
  
  '--glass-background-light': 'rgba(255, 255, 255, 0.05)',
  '--glass-backdrop-filter': 'blur(10px)',
  '--glass-border-light': '1px solid rgba(255, 255, 255, 0.1)',
  '--glass-box-shadow': '0 8px 32px rgba(0, 0, 0, 0.3)',
  
  '--radius-sm': '4px',
  '--radius-md': '8px',
  '--radius-lg': '12px',
  '--radius-xl': '16px',
  
  '--shadow-sm': '0 1px 2px rgba(0, 0, 0, 0.3)',
  '--shadow-md': '0 4px 16px rgba(0, 0, 0, 0.4)',
  '--shadow-lg': '0 8px 32px rgba(0, 0, 0, 0.5)',
  '--shadow-xl': '0 16px 64px rgba(0, 0, 0, 0.6)',
  
  '--transition-fast': '150ms ease',
  '--transition-normal': '200ms ease',
  '--transition-slow': '300ms ease',
};
