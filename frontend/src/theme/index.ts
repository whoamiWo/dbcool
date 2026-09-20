/**
 * Glassmorphism Dark Theme - MUI Theme Configuration
 * Enterprise Collaboration Platform
 */

import { createTheme, ThemeOptions } from '@mui/material/styles';
import { themeTokens } from './tokens';

// MUI 暗色主题配置
const darkThemeOptions: ThemeOptions = {
  palette: {
    mode: 'dark',
    primary: {
      main: themeTokens.colors.primary[500],
      light: themeTokens.colors.primary[200],
      dark: themeTokens.colors.primary[700],
      contrastText: themeTokens.colors.text.primary,
    },
    secondary: {
      main: themeTokens.colors.secondary[500],
      light: themeTokens.colors.secondary[200],
      dark: themeTokens.colors.secondary[700],
      contrastText: themeTokens.colors.text.primary,
    },
    background: {
      default: themeTokens.colors.background.primary,
      paper: themeTokens.colors.background.secondary,
    },
    text: {
      primary: themeTokens.colors.text.primary,
      secondary: themeTokens.colors.text.secondary,
      disabled: themeTokens.colors.text.disabled,
    },
    divider: themeTokens.colors.border.medium,
    action: {
      hover: 'rgba(255, 255, 255, 0.08)',
      selected: 'rgba(255, 255, 255, 0.12)',
      disabledBackground: 'rgba(255, 255, 255, 0.06)',
    },
  },

  components: {
    // MUI 基础组件样式覆盖
    MuiCssBaseline: {
      styleOverrides: {
        '*': {
          scrollbarWidth: 'thin',
          scrollbarColor: `${themeTokens.colors.text.muted} ${themeTokens.colors.background.secondary}`,
          '&::-webkit-scrollbar': {
            width: '6px',
            height: '6px',
          },
          '&::-webkit-scrollbar-track': {
            background: themeTokens.colors.background.secondary,
          },
          '&::-webkit-scrollbar-thumb': {
            background: themeTokens.colors.text.muted,
            borderRadius: themeTokens.radius.full,
          },
        },
        body: {
          backgroundColor: themeTokens.colors.background.primary,
          color: themeTokens.colors.text.primary,
          fontFamily: themeTokens.typography.fontFamily,
          fontSize: '14px',
          lineHeight: 1.5,
        },
      },
    },

    // Card 组件 - 毛玻璃效果
    MuiCard: {
      defaultProps: {
        elevation: 0,
      },
      styleOverrides: {
        root: {
          background: themeTokens.glass.medium.background,
          backdropFilter: themeTokens.glass.medium.backdropFilter,
          border: themeTokens.glass.medium.border,
          boxShadow: themeTokens.glass.medium.boxShadow,
          borderRadius: themeTokens.radius.lg,
          transition: `all ${themeTokens.transitions.normal}`,
          '&:hover': {
            background: 'rgba(255, 255, 255, 0.08)',
            boxShadow: themeTokens.shadows.lg,
            transform: 'translateY(-2px)',
          },
        },
      },
    },

    // Paper 组件
    MuiPaper: {
      defaultProps: {
        elevation: 0,
      },
      styleOverrides: {
        root: {
          background: themeTokens.glass.medium.background,
          backdropFilter: themeTokens.glass.medium.backdropFilter,
          border: themeTokens.glass.medium.border,
          borderRadius: themeTokens.radius.md,
        },
      },
    },

    // Button 组件
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: themeTokens.radius.md,
          fontWeight: themeTokens.typography.fontWeight.medium,
          textTransform: 'none',
          transition: `all ${themeTokens.transitions.fast}`,
          '&:hover': {
            transform: 'translateY(-1px)',
          },
        },
        contained: {
          background: themeTokens.colors.primary[500],
          color: themeTokens.colors.text.primary,
          '&:hover': {
            background: themeTokens.colors.primary[600],
            boxShadow: themeTokens.shadows.glow,
          },
        },
        outlined: {
          border: themeTokens.colors.border.medium,
          color: themeTokens.colors.text.primary,
          '&:hover': {
            background: 'rgba(255, 255, 255, 0.08)',
            borderColor: themeTokens.colors.primary[500],
          },
        },
        text: {
          color: themeTokens.colors.text.primary,
          '&:hover': {
            background: 'rgba(255, 255, 255, 0.08)',
          },
        },
      },
    },

    // TextField 组件
    MuiTextField: {
      styleOverrides: {
        root: {
          '& .MuiOutlinedInput-root': {
            background: themeTokens.glass.light.background,
            borderRadius: themeTokens.radius.md,
            '& fieldset': {
              borderColor: themeTokens.colors.border.medium,
            },
            '&:hover fieldset': {
              borderColor: themeTokens.colors.primary[500],
            },
            '&.Mui-focused fieldset': {
              borderColor: themeTokens.colors.primary[500],
              boxShadow: `0 0 0 2px ${themeTokens.colors.primary[500]}33`,
            },
          },
          '& .MuiInputLabel-root': {
            color: themeTokens.colors.text.muted,
          },
          '& .MuiInputBase-input': {
            color: themeTokens.colors.text.primary,
          },
        },
      },
    },

    // Chip 组件
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: themeTokens.radius.full,
          fontWeight: themeTokens.typography.fontWeight.medium,
        },
      },
    },

    // IconButton 组件
    MuiIconButton: {
      styleOverrides: {
        root: {
          borderRadius: themeTokens.radius.md,
          transition: `all ${themeTokens.transitions.fast}`,
          '&:hover': {
            background: 'rgba(255, 255, 255, 0.08)',
          },
        },
      },
    },

    // AppBar 组件
    MuiAppBar: {
      styleOverrides: {
        root: {
          background: `linear-gradient(135deg, ${themeTokens.colors.background.secondary} 0%, ${themeTokens.colors.background.tertiary} 100%)`,
          backdropFilter: 'blur(20px)',
          borderBottom: themeTokens.colors.border.medium,
          boxShadow: themeTokens.shadows.md,
        },
      },
    },

    // Tooltip 组件
    MuiTooltip: {
      styleOverrides: {
        tooltip: {
          background: themeTokens.colors.background.tertiary,
          color: themeTokens.colors.text.primary,
          boxShadow: themeTokens.shadows.lg,
          border: themeTokens.colors.border.light,
          backdropFilter: 'blur(8px)',
          borderRadius: themeTokens.radius.md,
        },
      },
    },

    // Menu 组件
    MuiMenu: {
      styleOverrides: {
        paper: {
          background: themeTokens.glass.strong.background,
          backdropFilter: themeTokens.glass.strong.backdropFilter,
          border: themeTokens.glass.strong.border,
          boxShadow: themeTokens.shadows.xl,
          borderRadius: themeTokens.radius.lg,
          mt: 1,
        },
      },
    },

    // Dialog 组件
    MuiDialog: {
      styleOverrides: {
        paper: {
          background: themeTokens.glass.strong.background,
          backdropFilter: themeTokens.glass.strong.backdropFilter,
          border: themeTokens.glass.strong.border,
          boxShadow: themeTokens.shadows.xl,
          borderRadius: themeTokens.radius.xl,
        },
      },
    },

    // Table 组件
    MuiTable: {
      styleOverrides: {
        root: {
          '& .MuiTableCell-root': {
            borderBottom: themeTokens.colors.border.light,
            color: themeTokens.colors.text.primary,
          },
          '& .MuiTableHead-root .MuiTableCell-root': {
            background: 'rgba(255, 255, 255, 0.04)',
            color: themeTokens.colors.text.secondary,
            fontWeight: themeTokens.typography.fontWeight.medium,
          },
        },
      },
    },

    // List 组件
    MuiList: {
      styleOverrides: {
        root: {
          '& .MuiListItem-root': {
            borderRadius: themeTokens.radius.md,
            transition: `all ${themeTokens.transitions.fast}`,
            '&:hover': {
              background: 'rgba(255, 255, 255, 0.04)',
            },
          },
        },
      },
    },

    // Divider 组件
    MuiDivider: {
      styleOverrides: {
        root: {
          borderColor: themeTokens.colors.border.light,
        },
      },
    },

    // Tab 组件
    MuiTab: {
      styleOverrides: {
        root: {
          textTransform: 'none',
          fontWeight: themeTokens.typography.fontWeight.medium,
          minHeight: 48,
          '&.Mui-selected': {
            color: themeTokens.colors.primary[400],
          },
        },
      },
    },
  },
};

// 创建并导出主题
export const darkTheme = createTheme(darkThemeOptions);

// 导出主题令牌
export { themeTokens };

// 默认导出
export default darkTheme;
