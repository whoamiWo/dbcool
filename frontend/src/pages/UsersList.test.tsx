import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { UsersListPage } from './UsersList';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn() },
}));

describe('UsersListPage', () => {
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
          <UsersListPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('加载中:显示"加载中…"', () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('空列表:显示"用户管理(0)" + 新建按钮', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage();
    expect(await screen.findByText(/👥 用户管理\(0\)/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /新建用户/ })).toBeInTheDocument();
  });

  it('有数据:渲染用户列表 + 用户名 + 状态', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([
      { id: 'u1', username: 'alice', display_name: 'Alice', enabled: true, created_at: '2026-01-15T10:00:00Z' },
      { id: 'u2', username: 'bob', display_name: 'Bob', enabled: false, created_at: '2026-02-20T14:30:00Z' },
    ] as any);

    renderPage();

    expect(await screen.findByText(/👥 用户管理\(2\)/)).toBeInTheDocument();
    expect(screen.getByText('alice')).toBeInTheDocument();
    expect(screen.getByText('bob')).toBeInTheDocument();
    expect(screen.getByText('Alice')).toBeInTheDocument();
    expect(screen.getByText('Bob')).toBeInTheDocument();
    // 启用/禁用 badge 出现在状态列 + 操作按钮
    expect(screen.getAllByText('启用').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('禁用').length).toBeGreaterThanOrEqual(1);
  });

  it('点击"+ 新建用户":显示创建表单', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /新建用户/ }));

    expect(screen.getByRole('button', { name: /取消/ })).toBeInTheDocument();
    // 3 个 input: username / password / displayName
    expect(screen.getAllByRole('textbox').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByRole('button', { name: /^创建$/ })).toBeInTheDocument();
  });

  it('创建用户成功:调 POST', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    vi.mocked(apiClient.post).mockResolvedValueOnce({} as any);

    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /新建用户/ }));

    // 填表单
    const inputs = document.querySelectorAll('input:not([type="password"])');
    const passwordInput = document.querySelector('input[type="password"]') as HTMLInputElement;
    // 顺序: username / displayName(没有 type,textbox)/ password
    fireEvent.change(inputs[0], { target: { value: 'newuser' } });
    fireEvent.change(inputs[1], { target: { value: 'New User' } });
    fireEvent.change(passwordInput, { target: { value: 'pw123' } });
    fireEvent.click(screen.getByRole('button', { name: /^创建$/ }));

    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith('/admin/users', {
        username: 'newuser', password: 'pw123', displayName: 'New User',
      });
    });
  });

  it('创建用户失败:显示错误消息', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    vi.mocked(apiClient.post).mockRejectedValueOnce({
      response: { data: { message: '用户名已存在' } },
    });

    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /新建用户/ }));

    const inputs = document.querySelectorAll('input:not([type="password"])');
    const passwordInput = document.querySelector('input[type="password"]') as HTMLInputElement;
    fireEvent.change(inputs[0], { target: { value: 'dup' } });
    fireEvent.change(passwordInput, { target: { value: 'pw' } });
    fireEvent.click(screen.getByRole('button', { name: /^创建$/ }));

    expect(await screen.findByText(/用户名已存在/)).toBeInTheDocument();
  });

  it('创建按钮在 username/password 为空时 disabled', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /新建用户/ }));

    const createBtn = screen.getByRole('button', { name: /^创建$/ });
    expect(createBtn).toBeDisabled();
  });
});
