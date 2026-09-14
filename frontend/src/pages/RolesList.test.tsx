import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { RolesListPage } from './RolesList';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

describe('RolesListPage', () => {
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
          <RolesListPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('加载中:显示"加载中…"', () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('空列表:显示"角色管理(0)" + 新建按钮', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage();
    expect(await screen.findByText(/🎭 角色管理\(0\)/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /新建角色/ })).toBeInTheDocument();
  });

  it('有数据:渲染所有角色卡片', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([
      { id: 'r1', name: 'admin', description: '系统管理员', tenant_id: 'tenant_default' },
      { id: 'r2', name: 'editor', description: '内容编辑', tenant_id: 'tenant_default' },
    ] as any);

    renderPage();

    expect(await screen.findByText(/🎭 角色管理\(2\)/)).toBeInTheDocument();
    // 角色名作为 emoji 🎭 + name 拼接,用正则
    expect(screen.getByText(/🎭 admin/)).toBeInTheDocument();
    expect(screen.getByText(/🎭 editor/)).toBeInTheDocument();
    expect(screen.getByText('系统管理员')).toBeInTheDocument();
    expect(screen.getByText('内容编辑')).toBeInTheDocument();
  });

  it('点击"+ 新建角色"按钮:显示创建表单', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage();

    // 等加载完成
    const newBtn = await screen.findByRole('button', { name: /新建角色/ });
    fireEvent.click(newBtn);

    // 创建按钮变成"取消",输入框出现
    expect(screen.getByRole('button', { name: /取消/ })).toBeInTheDocument();
    expect(screen.getByPlaceholderText('editor')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^创建$/ })).toBeInTheDocument();
  });

  it('创建角色成功:调 POST + 关闭表单', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      id: 'r3', name: 'viewer', description: '只读', tenant_id: 't',
    } as any);

    renderPage();
    const newBtn = await screen.findByRole('button', { name: /新建角色/ });
    fireEvent.click(newBtn);

    fireEvent.change(screen.getByPlaceholderText('editor'), { target: { value: 'viewer' } });
    fireEvent.click(screen.getByRole('button', { name: /^创建$/ }));

    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith('/admin/roles', {
        name: 'viewer', description: '',
      });
    });
  });

  it('创建角色失败:显示错误消息', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    vi.mocked(apiClient.post).mockRejectedValueOnce({
      response: { data: { message: '角色名已存在' } },
    });

    renderPage();
    const newBtn = await screen.findByRole('button', { name: /新建角色/ });
    fireEvent.click(newBtn);
    fireEvent.change(screen.getByPlaceholderText('editor'), { target: { value: 'admin' } });
    fireEvent.click(screen.getByRole('button', { name: /^创建$/ }));

    expect(await screen.findByText(/角色名已存在/)).toBeInTheDocument();
  });

  it('创建按钮在 name 为空时 disabled', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage();
    const newBtn = await screen.findByRole('button', { name: /新建角色/ });
    fireEvent.click(newBtn);

    const createBtn = screen.getByRole('button', { name: /^创建$/ });
    expect(createBtn).toBeDisabled();
  });
});
