import React from 'react';
import { Box, Button, Typography } from '@mui/material';

/**
 * PHASE98 R3: 顶层错误边界。
 *
 * 背景：项目此前**没有任何** ErrorBoundary（`componentDidCatch` / `react-error-boundary`
 * 全仓 0 命中），任一 render 抛错即整页白屏。`pages/MyTasks.tsx:33-36` 的注释就记录了
 * 一次真实崩溃："(tasks ?? []).filter 抛 is not a function，页面直接崩溃"。
 *
 * 作用：捕获子树渲染异常，展示可恢复的错误界面（含重载入口），而不是白屏。
 */
interface Props {
  children: React.ReactNode;
  /** 自定义标题（默认中文） */
  title?: string;
}

interface State {
  hasError: boolean;
  message: string;
}

export class ErrorBoundary extends React.Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, message: '' };
  }

  static getDerivedStateFromError(error: unknown): State {
    return {
      hasError: true,
      message: error instanceof Error ? error.message : String(error),
    };
  }

  componentDidCatch(error: unknown, info: React.ErrorInfo) {
    // 保留控制台输出便于排障，但同时给用户可见界面（不再白屏）
    console.error('[ErrorBoundary] 渲染异常：', error, info.componentStack);
  }

  private handleReset = () => {
    this.setState({ hasError: false, message: '' });
  };

  private handleReload = () => {
    window.location.reload();
  };

  render() {
    if (this.state.hasError) {
      return (
        <Box
          role="alert"
          sx={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 2,
            py: 8,
            px: 3,
            textAlign: 'center',
          }}
        >
          <Typography variant="h6">{this.props.title ?? '页面出错了'}</Typography>
          <Typography variant="body2" sx={{ opacity: 0.8, maxWidth: 520 }}>
            {this.state.message || '渲染过程中发生未预期的错误。'}
          </Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button variant="contained" size="small" onClick={this.handleReload}>
              重新加载
            </Button>
            <Button variant="outlined" size="small" onClick={this.handleReset}>
              重试渲染
            </Button>
          </Box>
        </Box>
      );
    }
    return this.props.children;
  }
}

export default ErrorBoundary;
