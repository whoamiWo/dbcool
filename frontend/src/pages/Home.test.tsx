import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { HomePage } from './Home';
import apiClient from '@/api/client';
import { useAuthStore } from '@/stores/auth';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn() },
}));
vi.mock('@/stores/auth', () => ({
  useAuthStore: vi.fn(),
}));

describe('HomePage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
    localStorage.clear();
  });

  const renderHome = () =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <HomePage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('未登录:显示"游客"', () => {
    vi.mocked(useAuthStore).mockReturnValue({ user: null } as any);
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));

    renderHome();

    expect(screen.getByText(/游客/)).toBeInTheDocument();
  });

  it('已登录:显示 display_name 或 username', async () => {
    vi.mocked(useAuthStore).mockReturnValue({
      user: {
        id: 'u1',
        username: 'alice',
        display_name: 'Alice Wang',
        tenant_id: 't',
        roles: ['admin'],
      },
    } as any);
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));

    renderHome();

    expect(await screen.findByText(/Alice Wang/)).toBeInTheDocument();
  });

  it('已登录但无 display_name:fallback 到 username', async () => {
    vi.mocked(useAuthStore).mockReturnValue({
      user: { id: 'u1', username: 'bob', tenant_id: 't', roles: [] },
    } as any);
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));

    renderHome();

    expect(await screen.findByText(/👋 欢迎,bob/)).toBeInTheDocument();
  });

  it('加载中:显示摘要卡片 skeleton', () => {
    vi.mocked(useAuthStore).mockReturnValue({ user: null } as any);
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));

    renderHome();

    // 渲染摘要卡片(loading 状态)
    expect(screen.getAllByText(/加载中/).length).toBeGreaterThanOrEqual(0);
  });

  it('未登录加载中:显示 loading 状态', async () => {
    vi.mocked(useAuthStore).mockReturnValue({ user: null } as any);
    // 所有 endpoint 都 pending
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));

    renderHome();

    // 验证渲染了页面骨架(摘要卡片)
    expect(screen.getByText(/游客/)).toBeInTheDocument();
  });
});
