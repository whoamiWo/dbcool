import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ProfilePage } from './Profile';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

// Profile.queryFn 解包 res.data
const mockMe = (data: object) =>
  vi.mocked(apiClient.get).mockResolvedValue({ data } as any);

describe('ProfilePage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  const renderProfile = () =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <ProfilePage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('渲染标题 + "我的信息" + 加载中', () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    renderProfile();

    expect(screen.getByText('👤 个人中心')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '我的信息' })).toBeInTheDocument();
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('获取 /auth/me 后显示用户名 + 角色 badge', async () => {
    mockMe({
      id: 'u1',
      username: 'alice',
      tenant_id: 'tenant_default',
      roles: ['admin', 'editor'],
      created_at: '2026-01-15T10:00:00Z',
    });

    renderProfile();

    // 等 query 完成
    expect(await screen.findByText('alice')).toBeInTheDocument();
    expect(screen.getByText('admin')).toBeInTheDocument();
    expect(screen.getByText('editor')).toBeInTheDocument();
  });

  it('修改密码按钮在 empty 输入时 disabled', async () => {
    mockMe({
      id: 'u', username: 'bob', tenant_id: 't', roles: [], created_at: '2026-01-01T00:00:00Z',
    });

    renderProfile();

    expect(await screen.findByText('bob')).toBeInTheDocument();
    const btn = screen.getByRole('button', { name: /修改密码/ });
    expect(btn).toBeDisabled();
  });

  it('修改密码成功:显示成功消息 + 清空输入', async () => {
    mockMe({
      id: 'u', username: 'bob', tenant_id: 't', roles: [], created_at: '2026-01-01T00:00:00Z',
    });
    vi.mocked(apiClient.post).mockResolvedValueOnce({ code: 0, message: '密码已修改' });

    renderProfile();

    expect(await screen.findByText('bob')).toBeInTheDocument();
    const inputs = document.querySelectorAll('input[type="password"]');
    fireEvent.change(inputs[0], { target: { value: 'old' } });
    fireEvent.change(inputs[1], { target: { value: 'new' } });

    fireEvent.click(screen.getByRole('button', { name: /修改密码/ }));

    await waitFor(() => {
      expect(screen.getByText(/密码已修改/)).toBeInTheDocument();
    });
    // 输入被清空
    expect((inputs[0] as HTMLInputElement).value).toBe('');
    expect((inputs[1] as HTMLInputElement).value).toBe('');
  });

  it('修改密码失败:显示后端返回的错误消息', async () => {
    mockMe({
      id: 'u', username: 'bob', tenant_id: 't', roles: [], created_at: '2026-01-01T00:00:00Z',
    });
    vi.mocked(apiClient.post).mockRejectedValueOnce({
      response: { data: { message: '旧密码错误' } },
    });

    renderProfile();

    expect(await screen.findByText('bob')).toBeInTheDocument();
    const inputs = document.querySelectorAll('input[type="password"]');
    fireEvent.change(inputs[0], { target: { value: 'old' } });
    fireEvent.change(inputs[1], { target: { value: 'new' } });

    fireEvent.click(screen.getByRole('button', { name: /修改密码/ }));

    await waitFor(() => {
      expect(screen.getByText(/旧密码错误/)).toBeInTheDocument();
    });
  });
});
