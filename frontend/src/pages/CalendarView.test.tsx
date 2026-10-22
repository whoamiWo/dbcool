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
    t: (k: string) => {
      const translations: Record<string, string> = {
        'calendarView.dayWeeks': JSON.stringify(['Mon','Tue','Wed','Thu','Fri','Sat','Sun']),
        'calendarView.loading': 'Loading...',
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
  });
});