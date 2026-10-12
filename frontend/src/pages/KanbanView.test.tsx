import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { KanbanViewPage } from './KanbanView';

// PHASE98 R6：useIsMobile 依赖 MUI useMediaQuery，测试环境下 mock 为 false（桌面端）
vi.mock('@/hooks/useIsMobile', () => ({
  useIsMobile: () => false,
}));

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn().mockImplementation((url: string) => {
      if (url.startsWith('/views/')) {
        return Promise.resolve({ collection_name: 'test_collection', config: { groupBy: 'status' } });
      }
      if (url.includes('/records?limit=500')) {
        return Promise.resolve({ data: [] });
      }
      return Promise.resolve({ data: { fields: [] } });
    }),
  },
}));

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/views/v1']}>
        <Routes>
          <Route path="/views/:id" element={<KanbanViewPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('KanbanView — PHASE98 R6 横向滚动回归防护', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('看板容器拥有 overflowX:auto（而非 visible），防止页面级横向滚动', async () => {
    renderPage();
    // 等待 query 完成、容器渲染出来
    await waitFor(() => {
      const el = document.querySelector('.kanban-view-container');
      expect(el).toBeTruthy();
    });
    const container = document.querySelector('.kanban-view-container')!;
    const style = getComputedStyle(container);
    expect(style.overflowX).toBe('auto');
  });
});