import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { MyTasksPage } from './MyTasks';
import apiClient from '@/api/client';
import { useAuthStore } from '@/stores/auth';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

const sampleTasks = [
  {
    id: 't1', instance_id: 'i1', node_id: 'approve-step',
    node_type: 'approval', status: 'PENDING',
    comment: '', created_at: '2026-01-15T10:00:00Z', finished_at: '',
    trigger_data_json: '{"amount":1000,"requester":"alice"}',
    record_id: 'r1', instance_status: 'RUNNING',
    workflow_id: 'w1', workflow_name: 'leave-approval',
    workflow_title: '请假审批',
  },
  {
    id: 't2', instance_id: 'i2', node_id: 'review-step',
    node_type: 'review', status: 'APPROVED',
    comment: 'ok', created_at: '2026-01-10T08:00:00Z', finished_at: '2026-01-11T09:00:00Z',
    trigger_data_json: '{}',
    record_id: 'r2', instance_status: 'APPROVED',
    workflow_id: 'w2', workflow_name: 'expense-approval',
    workflow_title: '报销审批',
  },
];

const wrap = () => {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <MyTasksPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

describe('MyTasksPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // 重置 auth store
    useAuthStore.setState({ user: { id: 'u1', username: 'me' } as any, accessToken: 'tk' });
  });

  it('加载中:显示"加载中..."', async () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    wrap();
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('加载成功:渲染待办列表(只显示 PENDING)', async () => {
    vi.mocked(apiClient.get).mockResolvedValue(sampleTasks as any);

    wrap();

    expect(await screen.findByText('请假审批')).toBeInTheDocument();
    expect(screen.getByText('approve-step')).toBeInTheDocument();
    // 已审批的不显示
    expect(screen.queryByText('报销审批')).not.toBeInTheDocument();
    // 状态徽章
    expect(screen.getByText('PENDING')).toBeInTheDocument();
    // 待审批计数
    expect(screen.getByText(/待审批 1 项/)).toBeInTheDocument();
  });

  it('空待办:显示"🎉 当前没有待办任务"', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);

    wrap();

    expect(await screen.findByText(/当前没有待办任务/)).toBeInTheDocument();
    expect(screen.getByText(/待审批 0 项/)).toBeInTheDocument();
  });

  it('点击"通过":调 POST approve', async () => {
    vi.mocked(apiClient.get).mockResolvedValue(sampleTasks as any);
    vi.mocked(apiClient.post).mockResolvedValue({} as any);

    wrap();
    await screen.findByText('请假审批');

    fireEvent.click(screen.getByRole('button', { name: /通过/ }));

    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith(
        '/workflows/tasks/t1/approve', { comment: '' },
      );
    });
  });

  it('点击"拒绝":调 POST reject', async () => {
    vi.mocked(apiClient.get).mockResolvedValue(sampleTasks as any);
    vi.mocked(apiClient.post).mockResolvedValue({} as any);

    wrap();
    await screen.findByText('请假审批');

    fireEvent.click(screen.getByRole('button', { name: /拒绝/ }));

    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith(
        '/workflows/tasks/t1/reject', { comment: '' },
      );
    });
  });

  it('待办数 = 所有任务中的 PENDING 数', async () => {
    const mixedTasks = [
      { ...sampleTasks[0], id: 't1', status: 'PENDING' },
      { ...sampleTasks[0], id: 't2', status: 'PENDING' },
      { ...sampleTasks[0], id: 't3', status: 'APPROVED' },
    ];
    vi.mocked(apiClient.get).mockResolvedValue(mixedTasks as any);

    wrap();

    expect(await screen.findByText(/待审批 2 项/)).toBeInTheDocument();
  });
});
