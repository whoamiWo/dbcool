import { describe, it, expect, beforeEach, vi } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WikiCategoryManagementPage } from './WikiCategoryManagement';
import { wikiApi } from '@/api/wiki';

vi.mock('@/api/wiki', () => ({
  wikiApi: {
    listCategories: vi.fn(),
    createCategory: vi.fn(),
    updateCategory: vi.fn(),
    deleteCategory: vi.fn(),
  },
}));

describe('WikiCategoryManagementPage', () => {
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
        <MemoryRouter initialEntries={['/wiki/kb/kb1/categories']}>
          <Routes>
            <Route path="/wiki/kb/:kbId/categories" element={<WikiCategoryManagementPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('空树显示"暂无分类"', async () => {
    vi.mocked(wikiApi.listCategories).mockResolvedValue([] as any);
    renderPage();
    expect(await screen.findByText(/暂无分类/)).toBeInTheDocument();
  });

  it('渲染分类树节点', async () => {
    vi.mocked(wikiApi.listCategories).mockResolvedValue([
      { id: 'c1', name: '前端', slug: 'frontend', parent_id: null },
      { id: 'c2', name: 'React', slug: 'react', parent_id: 'c1' },
    ] as any);
    renderPage();
    expect(await screen.findByText('前端')).toBeInTheDocument();
    // 展开父节点后子节点才渲染
    fireEvent.click(screen.getByText('前端'));
    const reactEls = await screen.findAllByText(/React/);
    expect(reactEls.length).toBeGreaterThanOrEqual(1);
  });

  it('新建分类:填写名称并提交', async () => {
    vi.mocked(wikiApi.listCategories).mockResolvedValue([] as any);
    vi.mocked(wikiApi.createCategory).mockResolvedValue({ id: 'c3', name: '后端', slug: 'backend' } as any);
    renderPage();
    fireEvent.click(await screen.findByText(/新建分类/));
    const nameInput = screen.getByLabelText(/分类名称/);
    fireEvent.change(nameInput, { target: { value: '后端' } });
    fireEvent.click(screen.getByText('创建'));
    await waitFor(() => {
      expect(wikiApi.createCategory).toHaveBeenCalledWith(expect.objectContaining({
        name: '后端',
      }));
    });
  });

  it('编辑分类:修改名称后保存', async () => {
    vi.mocked(wikiApi.listCategories).mockResolvedValue([
      { id: 'c1', name: '旧名称', slug: 'old', parent_id: null },
    ] as any);
    vi.mocked(wikiApi.updateCategory).mockResolvedValue({} as any);
    renderPage();
    // 编辑按钮在 ListItemText secondary 中，用 getAllByRole 找
    const editBtns = await screen.findAllByRole('button', { name: /编辑/ });
    fireEvent.click(editBtns[0]);
    const nameInput = screen.getByDisplayValue('旧名称');
    fireEvent.change(nameInput, { target: { value: '新名称' } });
    fireEvent.click(screen.getByText('更新'));
    await waitFor(() => {
      expect(wikiApi.updateCategory).toHaveBeenCalledWith('c1', expect.objectContaining({
        name: '新名称',
      }));
    });
  });

  it('删除分类', async () => {
    vi.mocked(wikiApi.listCategories).mockResolvedValue([
      { id: 'c1', name: '待删除', slug: 'del', parent_id: null },
    ] as any);
    vi.mocked(wikiApi.deleteCategory).mockResolvedValue({} as any);
    renderPage();
    // 用 aria-label 查找删除按钮
    const delBtns = await screen.findAllByRole('button', { name: /删除待删除/ });
    fireEvent.click(delBtns[0]);
    await waitFor(() => {
      expect(wikiApi.deleteCategory).toHaveBeenCalledWith('c1');
    });
  });

});