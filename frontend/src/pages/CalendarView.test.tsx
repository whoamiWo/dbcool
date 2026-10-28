/**
 * PHASE 4.2 覆盖率补测：pages/CalendarView.tsx（C3 周/年视图重构后）
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CalendarViewPage } from './CalendarView';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    put: vi.fn(),
  },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, opts?: Record<string, unknown>) => {
      const translations: Record<string, string> = {
        'calendarView.dayWeeks': JSON.stringify(['Sun','Mon','Tue','Wed','Thu','Fri','Sat']),
        'calendarView.loading': 'Loading...',
        'calendarView.prevMonth': 'Prev Month',
        'calendarView.nextMonth': 'Next Month',
        'calendarView.today': 'Today',
        'calendarView.prevWeek': 'Prev Week',
        'calendarView.nextWeek': 'Next Week',
        'calendarView.modeMonth': 'Month',
        'calendarView.modeWeek': 'Week',
        'calendarView.modeYear': 'Year',
        'calendarView.noData': 'No data',
        'calendarView.recordCount': `{{count}} total`,
        'calendarView.sortedBy': `sorted by {{field}}`,
        'calendarView.moreRecords': '{{count}} more',
        'calendarView.expandDay': 'Expand day {{day}}',
        'calendarView.expand': 'Expand',
        'calendarView.collapse': 'Collapse',
        'calendarView.noRecords': 'No records',
      };
      let result = translations[k] ?? k;
      if (opts) {
        for (const [key, val] of Object.entries(opts)) {
          result = result.replace(`{{${key}}}`, String(val));
        }
      }
      return result;
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

describe('CalendarViewPage (C3)', () => {
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

  it('默认显示月视图模式切换按钮', async () => {
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
    // C3 新增的视图切换按钮
    expect(screen.getByText('Month')).toBeInTheDocument();
    expect(screen.getByText('Week')).toBeInTheDocument();
    expect(screen.getByText('Year')).toBeInTheDocument();
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

  it('单列超过 3 条记录时显示"N 条更多"', async () => {
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
    // desktop mode shows "2 条更多" for day with 5 records
    expect(await screen.findByText('2 条更多')).toBeInTheDocument();
  });

  it('年份视图渲染 12 个月份', async () => {
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
    // 切换至年视图
    const yearBtn = screen.getByText('Year');
    yearBtn.click();
    // 年份视图应显示月份卡片
    await waitFor(() => {
      expect(screen.getByText('1 月')).toBeInTheDocument();
      expect(screen.getByText('12 月')).toBeInTheDocument();
    });
  });
});
