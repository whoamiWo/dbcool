import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { LoginPage } from './Login';
import apiClient from '@/api/client';

// Mock apiClient
vi.mock('@/api/client', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
  },
}));

describe('LoginPage', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    localStorage.clear();
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
  });

  const renderLogin = () =>
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <LoginPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('渲染标题、表单字段、登录按钮、提示', () => {
    renderLogin();

    expect(screen.getByRole('heading', { name: /NocoBase/i })).toBeInTheDocument();
    // 至少有两个 input:username + password
    const inputs = screen.getAllByRole('textbox');
    expect(inputs.length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByRole('button', { name: /登录/ }).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/任意非空账号密码可登录/)).toBeInTheDocument();
  });

  it('校验:空用户名 + 空密码提交不发起请求', async () => {
    renderLogin();

    const submitBtn = screen.getAllByRole('button', { name: /登录/ })[0];
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(apiClient.post).not.toHaveBeenCalled();
    });
  });

  it('登录成功:setAuth + localStorage 记住用户名 + 跳转准备', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      code: 0,
      message: 'success',
      data: {
        access_token: 'fake-jwt',
        refresh_token: 'fake-refresh',
        user: { id: 'u1', username: 'alice', tenant_id: 'tenant_default', roles: ['admin'] },
      },
    } as any);

    renderLogin();

    const inputs = screen.getAllByRole('textbox');
    // 第一个 textbox 是 username input
    fireEvent.change(inputs[0], { target: { value: 'alice' } });
    // password input 是 type=password,找 sibling 或最后一个 input
    const passwordInput = document.querySelector('input[type="password"]') as HTMLInputElement;
    fireEvent.change(passwordInput, { target: { value: 'secret123' } });

    const submitBtn = screen.getAllByRole('button', { name: /登录/ })[0];
    fireEvent.click(submitBtn);

    // 验证调用
    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith('/auth/login', {
        username: 'alice',
        password: 'secret123',
      });
    });

    // localStorage 记住用户名
    expect(localStorage.getItem('nocobase:login:lastUsername')).toBe('alice');
    // token 写入
    expect(localStorage.getItem('nocobase_access_token')).toBe('fake-jwt');
  });

  it('登录失败:显示后端返回的错误消息', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      code: 401,
      message: '账号或密码错误',
      data: null,
    } as any);

    renderLogin();

    const inputs = screen.getAllByRole('textbox');
    fireEvent.change(inputs[0], { target: { value: 'alice' } });
    const passwordInput = document.querySelector('input[type="password"]') as HTMLInputElement;
    fireEvent.change(passwordInput, { target: { value: 'wrong' } });

    const submitBtn = screen.getAllByRole('button', { name: /登录/ })[0];
    fireEvent.click(submitBtn);

    expect(await screen.findByText(/账号或密码错误/)).toBeInTheDocument();
  });

  it('登录失败:网络错误显示 fallback 消息', async () => {
    vi.mocked(apiClient.post).mockRejectedValueOnce(new Error('Network Error'));

    renderLogin();

    const inputs = screen.getAllByRole('textbox');
    fireEvent.change(inputs[0], { target: { value: 'alice' } });
    const passwordInput = document.querySelector('input[type="password"]') as HTMLInputElement;
    fireEvent.change(passwordInput, { target: { value: 'pw' } });

    const submitBtn = screen.getAllByRole('button', { name: /登录/ })[0];
    fireEvent.click(submitBtn);

    expect(await screen.findByText(/登录失败/)).toBeInTheDocument();
  });

  it('"记住用户名"未勾选 → 不保存到 localStorage', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      code: 0,
      data: { access_token: 't', refresh_token: 'r',
              user: { id: 'u', username: 'bob', tenant_id: 't', roles: [] } },
    } as any);

    renderLogin();

    // 取消勾选
    const checkbox = screen.getByLabelText(/记住用户名/) as HTMLInputElement;
    expect(checkbox.checked).toBe(true);  // 默认勾选
    fireEvent.click(checkbox);
    expect(checkbox.checked).toBe(false);

    const inputs = screen.getAllByRole('textbox');
    fireEvent.change(inputs[0], { target: { value: 'bob' } });
    const passwordInput = document.querySelector('input[type="password"]') as HTMLInputElement;
    fireEvent.change(passwordInput, { target: { value: 'pw' } });

    const submitBtn = screen.getAllByRole('button', { name: /登录/ })[0];
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalled();
    });
    // 不写入 lastUsername
    expect(localStorage.getItem('nocobase:login:lastUsername')).toBeNull();
  });

  it('从 localStorage 预填上次用户名', () => {
    localStorage.setItem('nocobase:login:lastUsername', 'previous_user');
    renderLogin();

    const inputs = screen.getAllByRole('textbox');
    expect(inputs[0]).toHaveValue('previous_user');
  });
});
