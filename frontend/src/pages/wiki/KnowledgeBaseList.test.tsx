import { describe, it, expect, beforeEach, vi } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { KnowledgeBaseListPage } from './KnowledgeBaseList';
import { wikiApi } from '@/api/wiki';

vi.mock('@/api/wiki', () => ({
  wikiApi: {
    listKb: vi.fn(),
    createKb: vi.fn(),
    deleteKb: vi.fn(),
    updateKb: vi.fn(),
  },
}));

describe('KnowledgeBaseListPage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
    });
    vi.clearAllMocks();
  });

  const renderPage = (initialPath: string = '/wiki/kb') =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/wiki/kb" element={<KnowledgeBaseListPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('空列表显示"暂无知识库"', async () => {
    vi.mocked(wikiApi.listKb).mockResolvedValue([] as any);
    renderPage();
    expect(await screen.findByText(/暂无知识库/)).toBeInTheDocument();
  });

  it('有数据:渲染知识库卡片', async () => {
    vi.mocked(wikiApi.listKb).mockResolvedValue([
      { id: 'kb1', name: '研发知识库', slug: 'rd', description: '研发文档', icon: '📚' },
      { id: 'kb2', name: '产品知识库', slug: 'product', description: '产品文档', icon: '📦' },
    ] as any);
    renderPage();
    const titles = await screen.findAllByText('研发知识库');
    expect(titles.length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('产品知识库')).toBeInTheDocument();
    expect(screen.getByText('Slug: rd')).toBeInTheDocument();
    expect(screen.getByText('Slug: product')).toBeInTheDocument();
  });

  it('新建知识库:填写名称和slug并提交', async () => {
    vi.mocked(wikiApi.listKb).mockResolvedValue([] as any);
    vi.mocked(wikiApi.createKb).mockResolvedValue({ id: 'kb3', name: '新建' } as any);
    renderPage();
    fireEvent.click(await screen.findByText(/新建知识库/));
    const nameInput = screen.getByLabelText(/名称/);
    fireEvent.change(nameInput, { target: { value: '新建知识库' } });
    const slugInput = screen.getByLabelText(/Slug/);
    fireEvent.change(slugInput, { target: { value: 'new-kb' } });
    fireEvent.click(screen.getByText('创建'));
    await waitFor(() => {
      expect(wikiApi.createKb).toHaveBeenCalledWith(expect.objectContaining({
        name: '新建知识库', slug: 'new-kb',
      }));
    });
  });
});