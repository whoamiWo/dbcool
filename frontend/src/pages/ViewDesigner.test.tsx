/**
 * PHASE 4.2 覆盖率补测：pages/ViewDesigner.tsx
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { ViewDesignerPage } from './ViewDesigner';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

vi.mock('@/hooks/useIsMobile', () => ({ useIsMobile: () => false }));

vi.mock('@/components/SharePanel', () => ({
  SharePanel: () => <div data-testid="share-panel" />,
  parseShares: () => [],
  serializeShares: () => '',
}));

const wrap = (path: string) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/views/designer/:collection/:id?" element={<ViewDesignerPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
};

describe('ViewDesignerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('加载 collection 数据', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 0, data: { name: 'test', fields: [{ name: 'name', type: 'text' }] } });
    wrap('/views/designer/test');
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled(), { timeout: 3000 });
  });

  it('编辑模式加载 view', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: { name: 'test', fields: [{ name: 'name', type: 'text' }] } })
      .mockResolvedValueOnce({ code: 0, data: { id: 'v1', title: 'Test View', type: 'table', config: { pageSize: 10, columns: [{ field: 'name', label: 'Name', width: 160, visible: true }] } } });
    wrap('/views/designer/test/v1');
    await waitFor(() => expect(apiClient.get).toHaveBeenCalledTimes(2), { timeout: 3000 });
  });
});