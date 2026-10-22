import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PermissionPreview } from './PermissionPreview';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn() },
}));

describe('PermissionPreview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  const renderComponent = (userId: string) =>
    render(<PermissionPreview userId={userId} />);

  it('加载中:显示"加载中…"', () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    renderComponent('u1');
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('加载成功:渲染角色列表和策略摘要', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 0,
      message: '',
      data: {
        user_id: 'u1',
        tenant_id: 't1',
        roles: [
          { id: 'r1', name: 'Admin', description: '管理员', tenant_id: 't1' },
        ],
        policies_summary: [{ role_id: 'r1', role_name: 'Admin' }],
      },
    });

    renderComponent('u1');

    expect(await screen.findByText('Admin')).toBeInTheDocument();
    expect(screen.getByText(/管理员/)).toBeInTheDocument();
    // 策略摘要显示角色名称
    expect(screen.getByText(/Admin/)).toBeInTheDocument();
  });

  it('加载失败(code!=0):显示错误消息', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 404,
      message: '用户不存在',
      data: null,
    });

    renderComponent('u1');

    expect(await screen.findByText(/用户不存在/)).toBeInTheDocument();
  });

  it('网络异常:显示网络错误提示', async () => {
    vi.mocked(apiClient.get).mockRejectedValue(new Error('Network error'));

    renderComponent('u1');

    expect(await screen.findByText(/网络/)).toBeInTheDocument();
  });

  it('无角色:显示空状态', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 0,
      message: '',
      data: { user_id: 'u1', tenant_id: 't1', roles: [], policies_summary: [] },
    });

    renderComponent('u1');

    expect(await screen.findByText(/该用户暂无角色分配/)).toBeInTheDocument();
  });

  it('有角色但无策略:显示"暂无具体权限策略"', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 0,
      message: '',
      data: {
        user_id: 'u1',
        tenant_id: 't1',
        roles: [{ id: 'r1', name: 'Viewer', description: '', tenant_id: 't1' }],
        policies_summary: [],
      },
    });

    renderComponent('u1');

    expect(await screen.findByText(/暂无具体权限策略/)).toBeInTheDocument();
  });

  it('userId 为空:不发请求', () => {
    renderComponent('');
    expect(apiClient.get).not.toHaveBeenCalled();
  });
});
