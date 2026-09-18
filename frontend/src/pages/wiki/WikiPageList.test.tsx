import { describe, it, expect, beforeEach, vi } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { render, screen, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WikiPageListPage } from './WikiPageList';
import { wikiApi } from '@/api/wiki';

vi.mock('@/api/wiki', () => ({
  wikiApi: {
    getKb: vi.fn(),
    listPages: vi.fn(),
    createPage: vi.fn(),
    deletePage: vi.fn(),
  },
}));

describe('WikiPageListPage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
    });
    vi.clearAllMocks();
  });

  const renderPage = (kbId: string = 'kb1') =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={[`/wiki/kb/${kbId}/pages`]}>
          <Routes>
            <Route path="/wiki/kb/:id/pages" element={<WikiPageListPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('空列表显示"暂无文档"', async () => {
    vi.mocked(wikiApi.getKb).mockResolvedValue({ data: { id: 'kb1', name: 'KB' } } as any);
    vi.mocked(wikiApi.listPages).mockResolvedValue([] as any);
    renderPage();
    expect(await screen.findByText(/暂无文档/)).toBeInTheDocument();
  });

  it('有数据:渲染页面表格', async () => {
    vi.mocked(wikiApi.getKb).mockResolvedValue({ data: { id: 'kb1', name: 'KB' } } as any);
    vi.mocked(wikiApi.listPages).mockResolvedValue({ data: [
      { id: 'p1', title: '页面一', slug: 'page-1', status: 'PUBLISHED', version: 3, updated_at: '2026-01-01T00:00:00Z' },
      { id: 'p2', title: '页面二', slug: 'page-2', status: 'DRAFT', version: 1, updated_at: '2026-01-02T00:00:00Z' },
    ], total: 2 } as any);
    renderPage();
    expect(await screen.findByText('页面一')).toBeInTheDocument();
    expect(screen.getByText('页面二')).toBeInTheDocument();
    expect(screen.getByText('PUBLISHED')).toBeInTheDocument();
    expect(screen.getByText('DRAFT')).toBeInTheDocument();
    expect(screen.getByText('v3')).toBeInTheDocument();
  });

  it('新建文档:打开对话框', async () => {
    vi.mocked(wikiApi.getKb).mockResolvedValue({ data: { id: 'kb1', name: 'KB' } } as any);
    vi.mocked(wikiApi.listPages).mockResolvedValue([] as any);
    renderPage();
    const newBtn = await screen.findByText(/新建文档/);
    expect(newBtn).toBeInTheDocument();
    fireEvent.click(newBtn);
    expect(screen.getByLabelText(/标题/)).toBeInTheDocument();
    expect(screen.getByText(/^Slug$/)).toBeInTheDocument();
  });
});