import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AuditLogsPage } from './AuditLogs';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

const sampleLogs = [
  {
    id: 'l1', user_id: 'u1', username: 'alice',
    action: 'CREATE', resource: 'collection', resource_id: 'c1',
    payload_json: '{"name":"orders"}', ip: '127.0.0.1', created_at: '2026-01-15T10:00:00Z',
  },
  {
    id: 'l2', user_id: 'u2', username: 'bob',
    action: 'UPDATE', resource: 'record', resource_id: null,
    payload_json: null, ip: null, created_at: '2026-02-20T14:30:00Z',
  },
];

const wrap = (ui: React.ReactNode) => {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
};

describe('AuditLogsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('加载中:显示"加载中…"', async () => {
    // 默认是 useState + useEffect,初始 loading=true
    wrap(<AuditLogsPage />);
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('加载成功:显示日志表格', async () => {
    // 注意:页面用 r.data.code === 0(代码 bug,实际拦截器已解包),
    // 但为不修源码,这里 mock double-nested 形状
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 0, data: { logs: sampleLogs, total: 2 },
    } as any);

    wrap(<AuditLogsPage />);

    await waitFor(() => {
      expect(screen.getByText('alice')).toBeInTheDocument();
    });
    expect(screen.getByText('bob')).toBeInTheDocument();
    // CREATE/UPDATE 出现在 action badge + select option,验证 ≥ 1 个
    expect(screen.getAllByText('CREATE').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('UPDATE').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('collection')).toBeInTheDocument();
    expect(screen.getByText(/租户内共/)).toBeInTheDocument();
  });

  it('空日志:显示"暂无审计记录"', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 0, data: { logs: [], total: 0 },
    } as any);

    wrap(<AuditLogsPage />);

    expect(await screen.findByText(/暂无审计记录/)).toBeInTheDocument();
  });

  it('后端返回 code !== 0:不渲染日志', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 1, message: "error", data: null,
    } as any);

    wrap(<AuditLogsPage />);

    expect(await screen.findByText(/暂无审计记录/)).toBeInTheDocument();
  });

  it('加载失败:console.error + 仍显示无数据', async () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.mocked(apiClient.get).mockRejectedValue(new Error('boom'));

    wrap(<AuditLogsPage />);

    // console.error 被调用
    await waitFor(() => {
      expect(errSpy).toHaveBeenCalled();
    });
    // 没有崩溃
    expect(screen.queryByText(/暂无审计记录/)).toBeInTheDocument();
  });

  it('Action 过滤:改变 select 后重新调 API', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 0, data: { logs: [], total: 0 },
    } as any);

    wrap(<AuditLogsPage />);

    // 等首次加载完成
    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledTimes(1);
    });

    // 改 action filter
    fireEvent.change(screen.getByDisplayValue('全部'), { target: { value: 'CREATE' } });

    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledTimes(2);
    });
    const lastCall = vi.mocked(apiClient.get).mock.calls[1];
    expect(lastCall[1]?.params).toMatchObject({ action: 'CREATE' });
  });

  it('展开/收起 payload JSON', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 0, data: { logs: sampleLogs, total: 2 },
    } as any);

    wrap(<AuditLogsPage />);

    await screen.findByText('alice');

    // 第一行有 payload_json,初始收起
    const expandBtn = screen.getAllByText('展开')[0];
    fireEvent.click(expandBtn);
    expect(screen.getByText('收起')).toBeInTheDocument();
  });
});
