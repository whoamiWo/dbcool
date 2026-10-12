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
      expect(apiClient.get).toHaveBeenCalledWith('/project-boards/proj-1/lists');
      expect(apiClient.get).toHaveBeenCalledWith('/project-boards/proj-1/tasks');
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
        '/project-boards/tasks',
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

  /**
   * PHASE98 R3 改写说明：
   * 原用例断言"接口失败后仍渲染看板（空看板）"——那正是被修复的缺陷：
   * catch 里只 `console.error`，用户看到的是一个静默的空看板，
   * 分不清"没有任务"和"加载失败"。现在失败应展示错误态 + 重试入口。
   */
  it('接口失败时显示错误态与重试入口，而不是静默渲染空看板', async () => {
    vi.mocked(apiClient.get).mockRejectedValue(new Error('network error'));

    render(<BoardView projectId="proj-1" />);

    // 失败后应退出 loading（CircularProgress role=progressbar 消失）
    await waitFor(() => {
      expect(screen.queryByRole('progressbar')).toBeNull();
    });

    // 错误对用户可见（此前只 console.error，页面无任何提示）
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(/看板数据加载失败/)).toBeInTheDocument();

    // 提供重试入口（此前用户只能手动按"刷新"才知道要重试）
    const retryButtons = screen.getAllByRole('button');
    expect(retryButtons.length).toBeGreaterThan(0);
  });
});