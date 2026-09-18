import { describe, it, expect, beforeEach, vi } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WikiSearchPage } from './WikiSearch';
import { wikiApi } from '@/api/wiki';

vi.mock('@/api/wiki', () => ({
  wikiApi: {
    listKb: vi.fn(),
    search: vi.fn(),
  },
}));

describe('WikiSearchPage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
    });
    vi.clearAllMocks();
  });

  const renderPage = () =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/wiki/search']}>
          <Routes>
            <Route path="/wiki/search" element={<WikiSearchPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('空结果显示"未找到相关文档"', async () => {
    vi.mocked(wikiApi.listKb).mockResolvedValue([] as any);
    vi.mocked(wikiApi.search).mockResolvedValue({ data: [], total: 0 } as any);
    renderPage();
    const input = screen.getByPlaceholderText(/输入关键词搜索文档/);
    fireEvent.change(input, { target: { value: 'test' } });
    fireEvent.click(screen.getByText('搜索'));
    expect(await screen.findByText(/未找到与 "test" 相关的文档/)).toBeInTheDocument();
  });

  it('搜索:输入关键词后搜索', async () => {
    vi.mocked(wikiApi.listKb).mockResolvedValue([] as any);
    vi.mocked(wikiApi.search).mockResolvedValue({ data: [], total: 0 } as any);
    renderPage();
    const input = screen.getByPlaceholderText(/输入关键词搜索文档/);
    fireEvent.change(input, { target: { value: 'React' } });
    fireEvent.click(screen.getByText('搜索'));
    await waitFor(() => {
      expect(wikiApi.search).toHaveBeenCalledWith('React', expect.objectContaining({}));
    });
  });

  it('有结果:渲染搜索结果', async () => {
    vi.mocked(wikiApi.listKb).mockResolvedValue([] as any);
    vi.mocked(wikiApi.search).mockResolvedValue({
      data: [
        { id: 'p1', title: 'React 教程', slug: 'react-tutorial', kb_name: '研发知识库', status: 'PUBLISHED' },
      ],
      total: 1,
    } as any);
    renderPage();
    const input = screen.getByPlaceholderText(/输入关键词搜索文档/);
    fireEvent.change(input, { target: { value: 'React' } });
    fireEvent.click(screen.getByText('搜索'));
    expect(await screen.findByText('React 教程')).toBeInTheDocument();
  });

  it('按知识库筛选', async () => {
    vi.mocked(wikiApi.listKb).mockResolvedValue([{ id: 'kb1', name: '研发知识库' }] as any);
    vi.mocked(wikiApi.search).mockResolvedValue({ data: [], total: 0 } as any);
    renderPage();
    // 等待知识库列表加载完成
    await waitFor(() => {
      expect(wikiApi.listKb).toHaveBeenCalled();
    });
    // 展开筛选面板
    fireEvent.click(screen.getByText('筛选'));
    // 点击知识库 Select 打开菜单
    const selectBtn = screen.getAllByRole('combobox')[0];
    fireEvent.mouseDown(selectBtn);
    // 等待菜单渲染后点击 MenuItem
    await waitFor(() => {
      expect(screen.getByRole('listbox')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByRole('option', { name: '研发知识库' }));
    const input = screen.getByPlaceholderText(/输入关键词搜索文档/);
    fireEvent.change(input, { target: { value: 'test' } });
    fireEvent.click(screen.getByText('搜索'));
    await waitFor(() => {
      expect(wikiApi.search).toHaveBeenCalledWith('test', expect.objectContaining({ kbId: 'kb1' }));
    });
  });
});
