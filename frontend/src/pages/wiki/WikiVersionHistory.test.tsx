import { describe, it, expect, beforeEach, vi } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WikiVersionHistoryPage } from './WikiVersionHistory';
import { wikiApi } from '@/api/wiki';

vi.mock('@/api/wiki', () => ({
  wikiApi: {
    getPageBySlug: vi.fn(),
    listVersions: vi.fn(),
    restoreVersion: vi.fn(),
  },
}));

describe('WikiVersionHistoryPage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
    });
    vi.clearAllMocks();
  });

  const renderPage = (slug: string = 'my-page') =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={[`/wiki/${slug}/versions`]}>
          <Routes>
            <Route path="/wiki/:slug/versions" element={<WikiVersionHistoryPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('空版本显示"暂无版本记录"', async () => {
    vi.mocked(wikiApi.getPageBySlug).mockResolvedValue({ data: { id: 'p1', title: 'P', slug: 'my-page' } } as any);
    vi.mocked(wikiApi.listVersions).mockResolvedValue([] as any);
    renderPage();
    expect(await screen.findByText(/暂无版本记录/)).toBeInTheDocument();
  });

  it('渲染版本列表', async () => {
    vi.mocked(wikiApi.getPageBySlug).mockResolvedValue({ data: { id: 'p1', title: 'My Page', slug: 'my-page' } } as any);
    vi.mocked(wikiApi.listVersions).mockResolvedValue([
      { id: 'v1', version: 3, title: 'v3 标题', created_at: '2026-01-15T10:00:00Z', created_by: 'alice' },
      { id: 'v2', version: 2, title: 'v2 标题', created_at: '2026-01-14T10:00:00Z', created_by: 'bob' },
    ] as any);
    renderPage();
    expect(await screen.findByText('版本 3')).toBeInTheDocument();
    expect(screen.getByText('版本 2')).toBeInTheDocument();
  });

  it('恢复版本', async () => {
    vi.mocked(wikiApi.getPageBySlug).mockResolvedValue({ data: { id: 'p1', title: 'P', slug: 'my-page' } } as any);
    vi.mocked(wikiApi.listVersions).mockResolvedValue([
      { id: 'v1', version: 2, title: 'v2 标题', created_at: '2026-01-14T10:00:00Z', created_by: 'alice' },
    ] as any);
    vi.mocked(wikiApi.restoreVersion).mockResolvedValue({} as any);
    renderPage();
    const restoreBtn = await screen.findByText(/回滚/);
    fireEvent.click(restoreBtn);
    // 确认回滚按钮在对话框中 - 用 button 角色定位
    const confirmBtn = await screen.findByRole('button', { name: '确认回滚' });
    fireEvent.click(confirmBtn);
    await waitFor(() => {
      expect(wikiApi.restoreVersion).toHaveBeenCalledWith('p1', 2);
    });
  });

  it('对比两个版本', async () => {
    vi.mocked(wikiApi.getPageBySlug).mockResolvedValue({ data: { id: 'p1', title: 'P', slug: 'my-page' } } as any);
    vi.mocked(wikiApi.listVersions).mockResolvedValue([
      { id: 'v1', version: 3, title: 'v3', created_at: '2026-01-15T10:00:00Z', created_by: 'alice', content: 'v3 content' },
      { id: 'v2', version: 2, title: 'v2', created_at: '2026-01-14T10:00:00Z', created_by: 'bob', content: 'v2 content' },
    ] as any);
    renderPage();
    await screen.findByText('版本 3');
    const compareBtns = screen.getAllByText('对比');
    fireEvent.click(compareBtns[0]);
    fireEvent.click(compareBtns[1]);
    await waitFor(() => {
      expect(screen.getByText(/版本对比/)).toBeInTheDocument();
    });
  });

});
