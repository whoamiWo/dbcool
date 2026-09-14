import { describe, it, expect, beforeEach, vi } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ViewsListPage } from './ViewsList';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn() },
}));

describe('ViewsListPage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0, staleTime: 0 },
      },
    });
    vi.clearAllMocks();
  });

  const renderPage = (initialPath: string = '/designer/views') =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/designer/views" element={<ViewsListPage />} />
            <Route path="/designer/views/:collection" element={<ViewsListPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('加载中:显示"加载中…"', () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('空列表无 collection:显示"还没有视图"', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage();
    expect(await screen.findByText(/还没有视图/)).toBeInTheDocument();
    expect(screen.getByText(/📊 视图\(0\)/)).toBeInTheDocument();
  });

  it('有 collection:标题含 collection 名', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage('/designer/views/orders');
    expect(await screen.findByText(/📊 视图 \(orders\)\(0\)/)).toBeInTheDocument();
  });

  it('有数据:渲染表格 + 标题 + 类型 + collection', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([
      { id: 'v1', name: 'all-orders', title: 'All Orders', type: 'TABLE', collection_name: 'orders', created_at: '2026-01-15T10:00:00Z' },
      { id: 'v2', name: 'kanban-tasks', title: 'Task Board', type: 'KANBAN', collection_name: 'tasks', created_at: '2026-02-20T14:30:00Z' },
    ] as any);

    renderPage();

    expect(await screen.findByText(/📊 视图\(2\)/)).toBeInTheDocument();
    // 表格列: title / type(badge) / collection_name(monospace)
    expect(screen.getByText('All Orders')).toBeInTheDocument();
    expect(screen.getByText('Task Board')).toBeInTheDocument();
    expect(screen.getByText('TABLE')).toBeInTheDocument();
    expect(screen.getByText('KANBAN')).toBeInTheDocument();
    expect(screen.getByText('orders')).toBeInTheDocument();
    expect(screen.getByText('tasks')).toBeInTheDocument();
  });
});
