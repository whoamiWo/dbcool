/**
 * PHASE 4.2 覆盖率补测：pages/CalendarView.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CalendarViewPage } from './CalendarView';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
  },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ 
    t: (k: string, opts?: Record<string, unknown>) => {
      const translations: Record<string, string> = {
        'calendarView.dayWeeks': JSON.stringify(['Mon','Tue','Wed','Thu','Fri','Sat','Sun']),
        'calendarView.loading': 'Loading...',
        'calendarView.prevMonth': '上月',
        'calendarView.nextMonth': '下月',
        'calendarView.today': '今天',
        'calendarView.noData': '暂无数据',
        'calendarView.recordCount': `共 ${opts?.count ?? 0} 条`,
        'calendarView.sortedBy': `按 ${opts?.field ?? ''} 排序`,
        'calendarView.moreRecords': `还有 ${opts?.count ?? 0} 条`,
      };
      return translations[k] ?? k;
    },
  }),
}));

import apiClient from '@/api/client';

function renderView(id = 'v1') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/views/${id}`]}>
        <Routes>
          <Route path="/views/:id" element={<CalendarViewPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('CalendarViewPage', () => {
  it('加载视图后显示日历', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({
        id: 'v1',
        title: 'Calendar',
        collection_name: 'events',
        config: { dateField: 'date' },
      })
      .mockResolvedValueOnce({
        name: 'events',
        fields: [{ name: 'date', type: 'date' }, { name: 'title', type: 'text' }],
      })
      .mockResolvedValueOnce([]);

    renderView();
    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledTimes(3);
    });
  });

  it('无 dateField 时使用类型为 date 的字段', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({
        id: 'v1',
        title: 'Calendar',
        collection_name: 'events',
        config: {},
      })
      .mockResolvedValueOnce({
        name: 'events',
        fields: [{ name: 'created_at', type: 'datetime' }, { name: 'name', type: 'text' }],
      })
      .mockResolvedValueOnce([]);

    renderView();
    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledTimes(3);
    });
  });

  it('无 collectionData 时显示加载态', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'v1',
      title: 'Calendar',
      collection_name: 'events',
      config: {},
    });
    renderView();
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('无记录时日历为空', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({
        id: 'v1',
        title: 'Calendar',
        collection_name: 'events',
        config: { dateField: 'date' },
      })
      .mockResolvedValueOnce({
        name: 'events',
        fields: [{ name: 'date', type: 'date' }],
      })
      .mockResolvedValueOnce([]);

    renderView();
    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledTimes(3);
    });
    // buildCalendar 始终生成整月网格（含空白占位格），因此渲染的是空网格而非「暂无数据」
    expect(await screen.findByText(/共 0 条/)).toBeInTheDocument();
    expect(screen.getByText('Mon')).toBeInTheDocument();
  });

  it('有记录时渲染日历网格和记录标题', async () => {
    const mockRecords = [
      { id: 'r1', date: '2026-10-05', title: '会议 1' },
      { id: 'r2', date: '2026-10-12', title: '任务 2' },
      { id: 'r3', date: '2026-10-12', title: '任务 3' },
    ];
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({
        id: 'v1',
        title: 'Calendar',
        collection_name: 'events',
        config: { dateField: 'date' },
      })
      .mockResolvedValueOnce({
        name: 'events',
        fields: [{ name: 'date', type: 'date' }, { name: 'title', type: 'text' }],
      })
      .mockResolvedValueOnce(mockRecords);

    renderView();
    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledTimes(3);
    });
    expect(await screen.findByText('会议 1')).toBeInTheDocument();
    expect(await screen.findByText('任务 2')).toBeInTheDocument();
    expect(await screen.findByText('任务 3')).toBeInTheDocument();
  });

  it('翻月按钮点击切换月份', async () => {
    const mockRecords = [
      { id: 'r1', date: '2026-10-05', title: '会议 1' },
    ];
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({
        id: 'v1',
        title: 'Calendar',
        collection_name: 'events',
        config: { dateField: 'date' },
      })
      .mockResolvedValueOnce({
        name: 'events',
        fields: [{ name: 'date', type: 'date' }, { name: 'title', type: 'text' }],
      })
      .mockResolvedValueOnce(mockRecords);

    renderView();
    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledTimes(3);
    });

    const prevBtn = await screen.findByText('上月');
    const nextBtn = screen.getByText('下月');
    const todayBtn = screen.getByText('今天');

    expect(prevBtn).toBeInTheDocument();
    expect(nextBtn).toBeInTheDocument();
    expect(todayBtn).toBeInTheDocument();
  });

  it('单列超过 3 条记录时显示"还有 N 条"', async () => {
    const mockRecords = Array.from({ length: 5 }).map((_, i) => ({
      id: `r${i}`,
      date: '2026-10-12',
      title: `任务${i + 1}`,
    }));
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({
        id: 'v1',
        title: 'Calendar',
        collection_name: 'events',
        config: { dateField: 'date' },
      })
      .mockResolvedValueOnce({
        name: 'events',
        fields: [{ name: 'date', type: 'date' }, { name: 'title', type: 'text' }],
      })
      .mockResolvedValueOnce(mockRecords);

    renderView();
    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledTimes(3);
    });
    expect(await screen.findByText('还有 2 条')).toBeInTheDocument();
  });
});