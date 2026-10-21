import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { ThemeProvider, CssBaseline } from '@mui/material';
import createCache from '@emotion/cache';
import { CacheProvider } from '@emotion/react';
import { router } from './router';
import { darkTheme } from './theme';
import { ErrorBoundary } from './components/ErrorBoundary';
import { OfflineBanner } from './components/OfflineBanner';
import './i18n';
import './styles.css';
import './theme/glass.css';

/**
 * PHASE130: 读取构建期注入的 CSP nonce（见 vite.config.ts noncePlugin）。
 * Emotion 运行时注入的 <style> 标签必须携带该 nonce，否则在
 * style-src 'self' 'nonce-xxx' 策略下会被浏览器拦截，导致 MUI 样式失效。
 */
function readCspNonce(): string | undefined {
  const meta = document.querySelector<HTMLMetaElement>('meta[name="csp-nonce"]');
  return meta?.content || undefined;
}

const cspNonce = readCspNonce();

const emotionCache = createCache({
  key: 'css',
  nonce: cspNonce,
});

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <CacheProvider value={emotionCache}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider theme={darkTheme}>
          <CssBaseline />
          {/* PHASE98 R3: 顶层错误边界 —— 此前项目无任何 ErrorBoundary，
              任一 render 抛错即整页白屏。这里兜底为可恢复的错误界面。 */}
          <ErrorBoundary>
            <RouterProvider router={router} />
          </ErrorBoundary>
          <OfflineBanner />
        </ThemeProvider>
      </QueryClientProvider>
    </CacheProvider>
  </StrictMode>,
);
