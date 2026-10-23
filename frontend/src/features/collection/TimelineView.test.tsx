/**
 * PHASE 4.2 覆盖率补测：features/collection/TimelineView.tsx
 * 覆盖：加载中、无记录空态、按配置日期字段排序（asc/desc）、区间过滤参数透传。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { TimelineViewPage } from './TimelineView';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const mockedGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;

const view = (config: Record<string, unknown> = {}) => ({
  id: 'v1',
  name: 'timeline',
  collection_name: 'orders',
  config,
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/views/v1/timeline']}>
        <Routes>
          <Route path="/views/:id/timeline" element={<TimelineViewPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('TimelineViewPage', () => {
  it('加载中展示进度圈', () => {
    mockedGet.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(document.querySelector('.MuiCircularProgress-root')).toBeTruthy();
  });

  it('加载视图与记录', async () => {
    mockedGet
      .mockResolvedValueOnce(view({ dateField: 'created_at' }))
      .mockResolvedValueOnce({ code: 0, data: [{ id: 'r1', created_at: '2024-01-02' }] });
    renderPage();
    await waitFor(() => expect(mockedGet).toHaveBeenCalledWith('/views/v1'));
    await waitFor(() =>
      expect(mockedGet).toHaveBeenCalledWith(expect.stringContaining('/views/v1/timeline-records')),
    );
  });

  it('无记录时展示空态', async () => {
    mockedGet
      .mockResolvedValueOnce(view())
      .mockResolvedValueOnce({ code: 0, data: [] });
    renderPage();
    await waitFor(() => expect(screen.getByText(/暂无记录/)).toBeInTheDocument());
  });

  it('按日期字段升序渲染记录（标题回落为 id）', async () => {
    mockedGet
      .mockResolvedValueOnce(view({ dateField: 'created_at', sortDirection: 'asc' }))
      .mockResolvedValueOnce({
        code: 0,
        data: [
          { id: 'r2', created_at: '2024-03-01' },
          { id: 'r1', created_at: '2024-01-01' },
        ],
      });
    renderPage();
    await waitFor(() => expect(screen.getByText('r1')).toBeInTheDocument());
    const items = screen.getAllByText(/^r\d$/);
    expect(items[0].textContent).toBe('r1');
  });

  it('sortDirection=desc 时倒序', async () => {
    mockedGet
      .mockResolvedValueOnce(view({ dateField: 'created_at', sortDirection: 'desc' }))
      .mockResolvedValueOnce({
        code: 0,
        data: [
          { id: 'r1', created_at: '2024-01-01' },
          { id: 'r2', created_at: '2024-03-01' },
        ],
      });
    renderPage();
    await waitFor(() => expect(screen.getAllByText(/^r\d$/).length).toBe(2));
    const items = screen.getAllByText(/^r\d$/);
    expect(items[0].textContent).toBe('r2');
  });

  it('记录含 title 字段时优先展示 title', async () => {
    mockedGet
      .mockResolvedValueOnce(view({ dateField: 'created_at' }))
      .mockResolvedValueOnce({
        code: 0,
        data: [{ id: 'r1', title: '订单A', created_at: '2024-01-01' }],
      });
    renderPage();
    await waitFor(() => expect(screen.getByText('订单A')).toBeInTheDocument());
  });

  it('配置区间过滤时透传 filterStart/filterEnd', async () => {
    mockedGet
      .mockResolvedValueOnce(view({ filterStart: '2024-01-01', filterEnd: '2024-02-01' }))
      .mockResolvedValueOnce({ code: 0, data: [{ id: 'r1', created_at: '2024-01-15' }] });
    renderPage();
    await waitFor(() =>
      expect(mockedGet).toHaveBeenCalledWith(
        expect.stringContaining('filterStart=2024-01-01'),
      ),
    );
    expect(mockedGet).toHaveBeenCalledWith(expect.stringContaining('filterEnd=2024-02-01'));
  });

  it('记录接口直接返回数组也能渲染', async () => {
    mockedGet
      .mockResolvedValueOnce(view())
      .mockResolvedValueOnce({ data: [{ id: 'r1', created_at: '2024-05-01' }] });
    renderPage();
    await waitFor(() => expect(screen.getByText('r1')).toBeInTheDocument());
  });
});
