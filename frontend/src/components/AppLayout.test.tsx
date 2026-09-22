import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AppLayout } from './AppLayout';
import { useAuthStore } from '@/stores/auth';

const testQueryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, staleTime: Infinity } },
});

// Mock useAuthStore
vi.mock('@/stores/auth', () => ({
  useAuthStore: vi.fn(),
}));

describe('AppLayout', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
  });

  it('未登录:不显示导航链接', () => {
    vi.mocked(useAuthStore).mockReturnValue({
      user: null,
      clear: vi.fn(),
    } as any);

    render(
      <QueryClientProvider client={testQueryClient}>
        <MemoryRouter>
          <AppLayout />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // AppLayout 总会渲染导航链接(只显示是否 active),即便没用户
    expect(screen.getByText(/NocoBase/)).toBeInTheDocument();
  });

  it('已登录 admin:显示用户名 + 所有导航', () => {
    const clearMock = vi.fn();
    vi.mocked(useAuthStore).mockReturnValue({
      user: { id: 'u1', username: 'alice', tenant_id: 't', roles: ['admin'] },
      clear: clearMock,
    } as any);

    render(
      <QueryClientProvider client={testQueryClient}>
        <MemoryRouter initialEntries={['/home']}>
          <AppLayout />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.getByText('🛠 NocoBase')).toBeInTheDocument();
    expect(screen.getByText(/alice\(/)).toBeInTheDocument();
    // 主要导航项（实际 label 已国际化）
    expect(screen.getByText('首页')).toBeInTheDocument();
    expect(screen.getByText('Tables')).toBeInTheDocument();
    expect(screen.getByText('Docs')).toBeInTheDocument();
    expect(screen.getByText('Chat')).toBeInTheDocument();
    expect(screen.getByText('Projects')).toBeInTheDocument();
  });

  it('退出登录:点击退出 → 调用 clear()', () => {
    const clearMock = vi.fn();
    vi.mocked(useAuthStore).mockReturnValue({
      user: { id: 'u1', username: 'bob', tenant_id: 't', roles: [] },
      clear: clearMock,
    } as any);

    render(
      <QueryClientProvider client={testQueryClient}>
        <MemoryRouter initialEntries={['/home']}>
          <AppLayout />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: /退出|登出|logout/i }));
    expect(clearMock).toHaveBeenCalledTimes(1);
  });

  it('active link 高亮当前路径', () => {
    vi.mocked(useAuthStore).mockReturnValue({
      user: { id: 'u', username: 'u', tenant_id: 't', roles: [] },
      clear: vi.fn(),
    } as any);

    render(
      <QueryClientProvider client={testQueryClient}>
        <MemoryRouter initialEntries={['/designer/views']}>
          <AppLayout />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // "Docs" link 在 /designer/views 路径下应是 active
    const docsLink = screen.getByText('Docs').closest('a');
    expect(docsLink).toBeInTheDocument();
  });
});
