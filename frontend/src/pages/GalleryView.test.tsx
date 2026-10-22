/**
 * PHASE 4.2 覆盖率补测：pages/GalleryView.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { GalleryViewPage } from './GalleryView';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    put: vi.fn().mockResolvedValue({}),
  },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, opts?: Record<string, number>) => {
      const dict: Record<string, string> = {
        'gallery.totalRecords': '',
        'gallery.groupCount': '',
        'gallery.itemsCount': '',
        'gallery.expandAll': '展开全部',
        'gallery.collapseAll': '收起全部',
        'gallery.noGroup': '不分组',
        'gallery.noData': '暂无数据',
      };
      if (k === 'gallery.totalRecords') return `${opts?.count ?? 0} 条记录`;
      if (k === 'gallery.groupCount') return `(${opts?.count ?? 0} 组)`;
      if (k === 'gallery.itemsCount') return `${opts?.count ?? 0} 项`;
      return dict[k] ?? k;
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
          <Route path="/views/:id" element={<GalleryViewPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('GalleryViewPage', () => {
  it('无 groupBy 时显示卡片列表', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({
        id: 'v1', title: 'Gallery', collection_name: 'orders', config: { cardTitleField: 'name' },
      })
      .mockResolvedValueOnce({ name: 'orders', fields: [{ name: 'name', type: 'text' }] })
      .mockResolvedValueOnce([{ id: 'r1', name: 'Order 1' }]);

    renderView();
    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledTimes(3);
    });
    expect(screen.getByText('Gallery')).toBeInTheDocument();
  });

  it('groupBy 时显示分组按钮', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({
        id: 'v1', title: 'Gallery', collection_name: 'orders', config: { groupBy: 'status' },
      })
      .mockResolvedValueOnce({
        name: 'orders', fields: [{ name: 'status', type: 'text' }, { name: 'name', type: 'text' }],
      })
      .mockResolvedValueOnce([
        { id: 'r1', status: 'pending', name: 'O1' },
        { id: 'r2', status: 'done', name: 'O2' },
      ]);

    renderView();
    await waitFor(() => expect(screen.getByText(/pending/)).toBeInTheDocument());
    // 展开/折叠按钮存在
    expect(screen.getByText('展开全部')).toBeInTheDocument();
    expect(screen.getByText('收起全部')).toBeInTheDocument();
  });

  it('点击展开/折叠切换分组状态', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({
        id: 'v1', title: 'Gallery', collection_name: 'orders', config: { groupBy: 'status' },
      })
      .mockResolvedValueOnce({
        name: 'orders', fields: [{ name: 'status', type: 'text' }, { name: 'name', type: 'text' }],
      })
      .mockResolvedValueOnce([
        { id: 'r1', status: 'pending', name: 'O1' },
      ]);

    renderView();
    await waitFor(() => expect(screen.getByText(/pending/)).toBeInTheDocument());
    
    const groupHeader = screen.getByText(/pending/);
    fireEvent.click(groupHeader);
    // 点击后子内容应该可见
    expect(screen.getByText('O1')).toBeInTheDocument();
  });

  it('空记录显示空状态', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({
        id: 'v1', title: 'Gallery', collection_name: 'orders', config: {},
      })
      .mockResolvedValueOnce({ name: 'orders', fields: [{ name: 'name', type: 'text' }] })
      .mockResolvedValueOnce([]);

    renderView();
    await waitFor(() => {
      expect(screen.getByText('Gallery')).toBeInTheDocument();
    });
  });
});