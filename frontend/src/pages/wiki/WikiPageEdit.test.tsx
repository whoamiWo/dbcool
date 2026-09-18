import { describe, it, expect, beforeEach, vi } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WikiPageEditPage } from './WikiPageEdit';
import { wikiApi } from '@/api/wiki';

vi.mock('@/api/wiki', () => ({
  wikiApi: {
    listPages: vi.fn(),
    updatePage: vi.fn(),
  },
}));

describe('WikiPageEditPage', () => {
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
        <MemoryRouter initialEntries={[`/wiki/${slug}/edit`]}>
          <Routes>
            <Route path="/wiki/:slug/edit" element={<WikiPageEditPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('加载页面数据:回显标题和内容', async () => {
    vi.mocked(wikiApi.listPages).mockResolvedValue({
      data: [
        { id: 'p1', title: '我的页面', slug: 'my-page', content: '# Hello', status: 'DRAFT' },
      ],
    } as any);
    renderPage();
    expect(await screen.findByDisplayValue('我的页面')).toBeInTheDocument();
    expect(await screen.findByDisplayValue('# Hello')).toBeInTheDocument();
  });

  it('修改标题和内容后保存', async () => {
    vi.mocked(wikiApi.listPages).mockResolvedValue({
      data: [
        { id: 'p1', title: '旧标题', slug: 'my-page', content: 'old content', status: 'DRAFT' },
      ],
    } as any);
    vi.mocked(wikiApi.updatePage).mockResolvedValue({} as any);
    renderPage();
    const titleInput = await screen.findByDisplayValue('旧标题');
    fireEvent.change(titleInput, { target: { value: '新标题' } });
    const contentTextarea = await screen.findByDisplayValue('old content');
    fireEvent.change(contentTextarea, { target: { value: 'new content' } });
    fireEvent.click(screen.getByText('保存'));
    await waitFor(() => {
      expect(wikiApi.updatePage).toHaveBeenCalledWith('p1', expect.objectContaining({
        title: '新标题', content: 'new content',
      }));
    });
  });

  it('保存时缺少标题:显示错误提示', async () => {
    vi.mocked(wikiApi.listPages).mockResolvedValue({
      data: [{ id: 'p1', title: '', slug: 'my-page', content: '', status: 'DRAFT' }],
    } as any);
    renderPage();
    // 等待加载完成，找到标题输入
    const titleInputs = await screen.findAllByLabelText(/标题/);
    expect(titleInputs.length).toBeGreaterThanOrEqual(1);
    // 清空内容
    const contentInput = await screen.findAllByLabelText(/内容|Markdown/);
    if (contentInput.length > 0) {
      fireEvent.change(contentInput[0], { target: { value: '' } });
    }
    // 点击保存
    fireEvent.click(screen.getByText('保存'));
    expect(await screen.findByText('标题和内容必填')).toBeInTheDocument();
  });
});
