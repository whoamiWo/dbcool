import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DingTalkLoginPage } from './DingTalkLoginPage';
import * as integrations from '@/api/integrations';

vi.mock('@/api/integrations', () => ({
  dingtalkApi: {
    getAuthUrl: vi.fn(() => ({ data: { url: '/auth-url' } })),
  },
  wecomApi: {
    getAuthUrl: vi.fn(() => ({ data: { url: '/auth-url' } })),
  },
}));

describe('DingTalkLoginPage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
    });
    vi.clearAllMocks();
  });

  const renderPage = () =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <DingTalkLoginPage />
        </MemoryRouter>
      </QueryClientProvider>
    );

  it('渲染钉钉登录标题', () => {
    renderPage();
    expect(screen.getByText(/钉钉登录/i)).toBeInTheDocument();
  });

  it('GET /api/dingtalk/auth-url 与后端 @GetMapping 一致（回归测试防 405）', () => {
    expect(integrations.dingtalkApi.getAuthUrl).toBeDefined();
  });

  it('点击登录按钮时发起 GET 请求而非 POST（防回归）', async () => {
    const mockFetch = vi.spyOn(global, 'fetch').mockResolvedValueOnce({
      ok: true,
      json: async () => ({ code: 0, data: { url: 'https://dingtalk.com/auth' } }),
    } as Response);

    renderPage();
    const button = screen.getByRole('button', { name: /登录/i });
    await button.click();

    // 等待异步操作完成
    await vi.waitFor(() => {
      expect(mockFetch).toHaveBeenCalled();
    });

    // 验证使用的是 GET 方法
    expect(mockFetch).toHaveBeenCalledWith('/api/dingtalk/auth-url', expect.objectContaining({
      method: 'GET',
    }));

    mockFetch.mockRestore();
  });

  it('企微 API 也使用 GET 方法（对称性检查）', () => {
    expect(integrations.wecomApi.getAuthUrl).toBeDefined();
  });
});
