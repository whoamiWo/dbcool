/**
 * PHASE 4.2 覆盖率补测：features/project/TaskBoard.tsx
 * 覆盖：loading / error / 正常渲染 / 各列分组 / 新建对话框。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import TaskBoard from './TaskBoard';
import { projectApi } from './api';

vi.mock('./api', () => ({
  projectApi: { listTasks: vi.fn(), createTask: vi.fn(), updateTask: vi.fn() },
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const mockList = projectApi.listTasks as unknown as ReturnType<typeof vi.fn>;
const mockCreate = projectApi.createTask as unknown as ReturnType<typeof vi.fn>;

const task = (id = 't1', status = 'TODO', title = '任务 A') => ({
  id, status, title, assignee: 'alice', priority: 'HIGH', endDate: '2024-03-01',
});

function renderView() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <TaskBoard projectId="p1" />
    </QueryClientProvider>,
  );
}

beforeEach(() => vi.clearAllMocks());

describe('TaskBoard', () => {
  it('加载中渲染进度圈', () => {
    mockList.mockReturnValue(new Promise(() => {}));
    renderView();
    expect(document.querySelector('.MuiCircularProgress-root')).toBeTruthy();
  });

  it('加载失败渲染错误 Alert', async () => {
    mockList.mockRejectedValue(new Error('fail'));
    renderView();
    await waitFor(() => expect(screen.getByText('common.loadFailed')).toBeInTheDocument());
  });

  it('正常加载渲染四列看板', async () => {
    mockList.mockResolvedValue([]);
    renderView();
    await waitFor(() => expect(screen.getByText('board.todo')).toBeInTheDocument());
    expect(screen.getByText('board.inProgress')).toBeInTheDocument();
    expect(screen.getByText('board.blocked')).toBeInTheDocument();
    expect(screen.getByText('board.done')).toBeInTheDocument();
  });

  it('任务按状态渲染到对应列', async () => {
    mockList.mockResolvedValue([task('t1', 'TODO', '待办任务'), task('t2', 'DONE', '完成任务')]);
    renderView();
    await waitFor(() => expect(screen.getByText('待办任务')).toBeInTheDocument());
    expect(screen.getByText('完成任务')).toBeInTheDocument();
  });

  it('点击新建任务打开对话框并可输入标题', async () => {
    mockList.mockResolvedValue([]);
    renderView();
    await waitFor(() => expect(screen.getByText('task.newTask')).toBeInTheDocument());
    fireEvent.click(screen.getByText('task.newTask'));
    await waitFor(() => expect(screen.getByLabelText('task.title')).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('task.title'), { target: { value: '新任务' } });
    expect((screen.getByLabelText('task.title') as HTMLInputElement).value).toBe('新任务');
  });

  it('填写表单后提交触发 createTask', async () => {
    mockList.mockResolvedValue([]);
    mockCreate.mockResolvedValue({ code: 0, data: task() });
    renderView();
    await waitFor(() => expect(screen.getByText('task.newTask')).toBeInTheDocument());
    fireEvent.click(screen.getByText('task.newTask'));
    await waitFor(() => expect(screen.getByLabelText('task.title')).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('task.title'), { target: { value: '新任务' } });
    fireEvent.click(screen.getByText('common.create'));
    await waitFor(() => expect(mockCreate).toHaveBeenCalled());
    expect(mockCreate.mock.calls[0][0]).toMatchObject({ projectId: 'p1', title: '新任务', status: 'TODO' });
  });
});