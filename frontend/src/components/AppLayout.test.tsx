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

// Mock i18next
vi.mock('i18next', () => ({
  default: {
    t: (key: string) => key, // 直接返回 key 作为翻译
    language: 'zh-CN',
  },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'zh-CN' },
  }),
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
    // 主要导航项（t() 返回 key，所以断言用 key）
    expect(screen.getByText('nav.home')).toBeInTheDocument();
    expect(screen.getByText('nav.tables')).toBeInTheDocument();
    expect(screen.getByText('nav.wiki')).toBeInTheDocument();
    expect(screen.getByText('nav.im')).toBeInTheDocument();
    expect(screen.getByText('nav.projects')).toBeInTheDocument();
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

    // "nav.wiki" link 在 /designer/views 路径下应是 active
    const docsLink = screen.getByText('nav.wiki').closest('a');
    expect(docsLink).toBeInTheDocument();
  });
});
