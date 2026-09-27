import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { BoardView } from './BoardView';
import apiClient from '@/api/client';

// Mock apiClient —— 隔离后端，只验证前端调用与渲染
vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const COLUMNS = [
  { id: 'list-1', title: '待办', type: 'TODO', sortOrder: 0, wipLimit: null },
  { id: 'list-2', title: '已完成', type: 'DONE', sortOrder: 1, wipLimit: null },
];

const CARDS = [
  { id: 'task-1', title: '修复登录缺陷', status: 'TODO', priority: 'HIGH', progress: 0 },
  { id: 'task-2', title: '编写部署文档', status: 'DONE', priority: 'LOW', progress: 100 },
];

describe('BoardView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(apiClient.get).mockImplementation((url: string) => {
      if (url.includes('/lists')) return Promise.resolve({ data: COLUMNS }) as any;
      if (url.includes('/tasks')) return Promise.resolve({ data: CARDS }) as any;
      return Promise.resolve({ data: [] }) as any;
    });
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} } as any);
  });

  it('挂载时拉取看板列与任务（修复前 loadData 从未被调用，永远卡在 loading）', async () => {
    render(<BoardView projectId="proj-1" />);

    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledWith('/api/project-boards/proj-1/lists');
      expect(apiClient.get).toHaveBeenCalledWith('/api/project-boards/proj-1/tasks');
    });
  });

  it('加载后渲染列标题与卡片标题', async () => {
    render(<BoardView projectId="proj-1" />);

    expect(await screen.findByText('待办')).toBeInTheDocument();
    expect(await screen.findByText('已完成')).toBeInTheDocument();
    expect(await screen.findByText('修复登录缺陷')).toBeInTheDocument();
    expect(await screen.findByText('编写部署文档')).toBeInTheDocument();
  });

  it('卡片按 status 归入对应列', async () => {
    render(<BoardView projectId="proj-1" />);

    await screen.findByText('修复登录缺陷');
    // 列头旁的 Chip 显示该列卡片数：待办 1 / 已完成 1
    const counts = screen.getAllByText('1');
    expect(counts.length).toBeGreaterThanOrEqual(2);
  });

  it('点击「添加卡片」调用创建任务端点（标题来自 prompt）', async () => {
    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('新任务标题');
    render(<BoardView projectId="proj-1" />);

    const addButtons = await screen.findAllByRole('button', { name: /添加卡片/ });
    fireEvent.click(addButtons[0]);

    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith(
        '/api/project-boards/tasks',
        expect.objectContaining({ projectId: 'proj-1', title: '新任务标题' }),
      );
    });
    promptSpy.mockRestore();
  });

  it('取消 prompt 时不调用创建接口', async () => {
    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue(null);
    render(<BoardView projectId="proj-1" />);

    const addButtons = await screen.findAllByRole('button', { name: /添加卡片/ });
    fireEvent.click(addButtons[0]);

    expect(apiClient.post).not.toHaveBeenCalled();
    promptSpy.mockRestore();
  });

  it('接口失败时不崩溃（loading 结束、不抛未捕获异常）', async () => {
    vi.mocked(apiClient.get).mockRejectedValue(new Error('network error'));
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(<BoardView projectId="proj-1" />);

    // 失败后应退出 loading（CircularProgress role=progressbar 消失）
    await waitFor(() => {
      expect(screen.queryByRole('progressbar')).toBeNull();
    });
    expect(await screen.findByText('看板')).toBeInTheDocument();
    consoleSpy.mockRestore();
  });
});