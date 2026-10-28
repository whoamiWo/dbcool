import { describe, expect, test, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { GanttViewPage } from './GanttView';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
  },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, opts?: Record<string, unknown>) => {
      const translations: Record<string, string> = {
        'calendarView.loading': '加载中...',
        'calendarView.noData': '暂无数据',
        'calendarView.recordCount': `共 ${opts?.count ?? 0} 条`,
        'calendarView.sortedBy': `按 ${opts?.field ?? ''} 排序`,
      };
      return translations[k] ?? k;
    },
  }),
}));

vi.mock('@/hooks/useIsMobile', () => ({
  useIsMobile: vi.fn(() => false),
}));

import apiClient from '@/api/client';

function renderView(id = 'v1') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/views/${id}/gantt`]}>
        <Routes>
          <Route path="/views/:id/gantt" element={<GanttViewPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const VIEW = {
  id: 'v1',
  collection_name: 'projects',
  name: 'gantt',
  title: '甘特图',
  type: 'gantt',
  config: { startDateField: 'start_date', endDateField: 'end_date', titleField: 'title' },
};

const COLLECTION = {
  name: 'projects',
  fields: [
    { name: 'title', type: 'text' },
    { name: 'start_date', type: 'datetime' },
    { name: 'end_date', type: 'datetime' },
    { name: 'progress', type: 'number' },
    { name: 'depends_on', type: 'text' },
  ],
};

const RECORDS = [
  { id: 'r1', title: '设计', start_date: '2026-01-01T00:00:00Z', end_date: '2026-01-10T00:00:00Z', progress: 60 },
  { id: 'r2', title: '开发', start_date: '2026-01-05T00:00:00Z', end_date: '2026-02-05T00:00:00Z', progress: 30, depends_on: 'r1' },
];

beforeEach(() => {
  vi.clearAllMocks();
  (apiClient.get as ReturnType<typeof vi.fn>).mockImplementation(async (path: string) => {
    if (path.startsWith('/views/')) return VIEW;
    if (path.includes('/records')) return RECORDS;
    return COLLECTION;
  });
});

describe('GanttViewPage', () => {
  test('渲染视图标题', async () => {
    renderView();
    expect(await screen.findByText('甘特图')).toBeTruthy();
  });

  test('渲染所有记录行（任务标题）', async () => {
    renderView();
    expect(await screen.findByText('设计')).toBeTruthy();
    expect(await screen.findByText('开发')).toBeTruthy();
  });

  test('空记录时显示占位', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockImplementation(async (path: string) => {
      if (path.startsWith('/views/')) return VIEW;
      if (path.includes('/records')) return [];
      return COLLECTION;
    });
    renderView();
    // 空记录时行区显示"暂无数据"占位文本
    expect(await screen.findByText('暂无数据')).toBeTruthy();
  });

  test('未加载时显示 loading', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockImplementation(
      () => new Promise(() => {}),
    );
    renderView();
    expect(await screen.findByText('加载中...')).toBeTruthy();
  });

  // B5 返工：新增测试覆盖 progress 进度条与依赖线
  test('渲染 progress 进度条（绿色填充）', async () => {
    renderView();
    await screen.findByText('设计');
    // 区间条内应包含绿色 progress 填充元素
    const progressBars = document.querySelectorAll('[aria-label*="progress"]');
    expect(progressBars.length).toBeGreaterThan(0);
  });

  test('渲染依赖线 SVG 覆盖层', async () => {
    renderView();
    await screen.findByText('开发');
    // 存在 SVG 箭头标记（依赖线 marker）
    const arrows = document.querySelectorAll('marker[id*="gantt-arrow"]');
    expect(arrows.length).toBeGreaterThan(0);
  });
});
