/**
 * PHASE 4.2 覆盖率补测：features/project/GanttView.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import GanttView from './GanttView';
import { projectApi } from './api';

vi.mock('./api', () => ({
  projectApi: { gantt: vi.fn() },
}));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const mockGantt = projectApi.gantt as unknown as ReturnType<typeof vi.fn>;

function renderView() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <GanttView projectId="p1" />
    </QueryClientProvider>,
  );
}

beforeEach(() => vi.clearAllMocks());

describe('GanttView', () => {
  it('加载中渲染进度圈', () => {
    mockGantt.mockReturnValue(new Promise(() => {}));
    renderView();
    expect(document.querySelector('.MuiCircularProgress-root')).toBeTruthy();
  });

  it('加载失败渲染错误 Alert', async () => {
    mockGantt.mockRejectedValue(new Error('gantt err'));
    renderView();
    await waitFor(() => expect(screen.getByText(/加载甘特数据失败/g)).toBeInTheDocument());
  });

  it('无任务时渲染空态提示', async () => {
    mockGantt.mockResolvedValue([]);
    renderView();
    await waitFor(() => expect(screen.getByText(/该项目暂无任务/g)).toBeInTheDocument());
  });

  it('有任务时渲染甘特图', async () => {
    mockGantt.mockResolvedValue([
      { id: 't1', title: '任务 A', start: Date.now(), end: Date.now() + 86400000, status: 'TODO' },
    ]);
    renderView();
    await waitFor(() => expect(screen.getByText('任务 A')).toBeInTheDocument());
  });

  it('有任务时渲染甘特图及时间轴表头', async () => {
    mockGantt.mockResolvedValue([
      {
        id: 't1',
        title: '任务 A',
        progress: 50,
        status: 'TODO',
        startDate: '2024-01-01',
        endDate: '2024-01-03',
      },
    ]);
    const { container } = renderView();
    await waitFor(() => expect(screen.getByText('任务 A')).toBeInTheDocument());
    expect(container.textContent).toContain('时间范围');
  });
});