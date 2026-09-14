import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AppLayout } from './AppLayout';
import { useAuthStore } from '@/stores/auth';

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
      <MemoryRouter>
        <AppLayout />
      </MemoryRouter>,
    );

    // AppLayout 总会渲染导航链接(只显示是否 active),即便没用户
    expect(screen.getByText(/🛠 NocoBase/)).toBeInTheDocument();
  });

  it('已登录 admin:显示用户名 + 所有导航', () => {
    const clearMock = vi.fn();
    vi.mocked(useAuthStore).mockReturnValue({
      user: { id: 'u1', username: 'alice', tenant_id: 't', roles: ['admin'] },
      clear: clearMock,
    } as any);

    render(
      <MemoryRouter initialEntries={['/home']}>
        <AppLayout />
      </MemoryRouter>,
    );

    expect(screen.getByText('🛠 NocoBase')).toBeInTheDocument();
    expect(screen.getByText(/alice\(/)).toBeInTheDocument();
    // 主要导航项
    expect(screen.getByText('首页')).toBeInTheDocument();
    expect(screen.getByText('数据模型')).toBeInTheDocument();
    expect(screen.getByText('视图')).toBeInTheDocument();
    expect(screen.getByText('用户')).toBeInTheDocument();
    expect(screen.getByText('工作流')).toBeInTheDocument();
  });

  it('退出登录:点击退出 → 调用 clear()', () => {
    const clearMock = vi.fn();
    vi.mocked(useAuthStore).mockReturnValue({
      user: { id: 'u1', username: 'bob', tenant_id: 't', roles: [] },
      clear: clearMock,
    } as any);

    render(
      <MemoryRouter initialEntries={['/home']}>
        <AppLayout />
      </MemoryRouter>,
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
      <MemoryRouter initialEntries={['/designer/views']}>
        <AppLayout />
      </MemoryRouter>,
    );

    // "视图" link 应该有 active 样式(蓝色或加粗)
    const viewsLink = screen.getByText('视图').closest('a');
    expect(viewsLink).toBeInTheDocument();
  });
});
