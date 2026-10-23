/**
 * PHASE 4.2 覆盖率补测：components/GlobalSearchPanel.tsx
 * 覆盖：打开/关闭面板、输入触发搜索（debounce 由组件内 effect 驱动）、
 * 各类型结果点击后的路由跳转（wiki/record/im/project/automation/default）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { GlobalSearchPanel } from './GlobalSearchPanel';
import { searchApi } from '@/api/search';

vi.mock('@/api/search', () => ({
  searchApi: { search: vi.fn() },
  SEARCH_FACET_COLORS: {},
}));

const navigateMock = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

const mockedSearch = searchApi.search as unknown as ReturnType<typeof vi.fn>;

const result = (type: string, extra: Record<string, unknown> = {}) => ({
  id: `${type}-1`,
  type,
  title: `${type} hit`,
  snippet: 'snip',
  ...extra,
});

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <GlobalSearchPanel />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedSearch.mockResolvedValue({
    code: 0,
    data: { keyword: 'x', total: 0, results: [], facets: {} },
  });
});

describe('GlobalSearchPanel', () => {
  it('渲染触发按钮', () => {
    renderPanel();
    expect(screen.getAllByRole('button').length).toBeGreaterThan(0);
  });

  it('点击后打开搜索面板', async () => {
    renderPanel();
    const trigger = screen.getAllByRole('button')[0];
    fireEvent.click(trigger);
    await waitFor(() => expect(screen.getByRole('textbox')).toBeInTheDocument());
  });

  it('输入关键词触发搜索', async () => {
    renderPanel();
    fireEvent.click(screen.getAllByRole('button')[0]);
    const input = await screen.findByRole('textbox');
    fireEvent.change(input, { target: { value: 'hello' } });
    await waitFor(() => expect(mockedSearch).toHaveBeenCalled());
    expect(mockedSearch).toHaveBeenCalledWith('hello', undefined, 10);
  });

  it('搜索结果按类型渲染', async () => {
    mockedSearch.mockResolvedValue({
      code: 0,
      data: { keyword: 'x', total: 1, results: [result('wiki', { slug: 'my-page' })], facets: {} },
    });
    renderPanel();
    fireEvent.click(screen.getAllByRole('button')[0]);
    const input = await screen.findByRole('textbox');
    fireEvent.change(input, { target: { value: 'x' } });
    await waitFor(() => expect(screen.getByText(/wiki hit/)).toBeInTheDocument());
  });

  it('点击 wiki 结果跳转 /wiki/:slug', async () => {
    mockedSearch.mockResolvedValue({
      code: 0,
      data: { keyword: 'x', total: 1, results: [result('wiki', { slug: 'my-page' })], facets: {} },
    });
    renderPanel();
    fireEvent.click(screen.getAllByRole('button')[0]);
    const input = await screen.findByRole('textbox');
    fireEvent.change(input, { target: { value: 'x' } });
    await waitFor(() => expect(screen.getByText(/wiki hit/)).toBeInTheDocument());
    fireEvent.click(screen.getByText(/wiki hit/));
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/wiki/my-page'));
  });

  it('点击 record 结果跳转 /views/:id/run', async () => {
    mockedSearch.mockResolvedValue({
      code: 0,
      data: { keyword: 'x', total: 1, results: [result('record')], facets: {} },
    });
    renderPanel();
    fireEvent.click(screen.getAllByRole('button')[0]);
    const input = await screen.findByRole('textbox');
    fireEvent.change(input, { target: { value: 'x' } });
    await waitFor(() => expect(screen.getByText(/record hit/)).toBeInTheDocument());
    fireEvent.click(screen.getByText(/record hit/));
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/views/record-1/run'));
  });

  it('点击 im / project / automation 结果分别跳转对应路由', async () => {
    for (const [type, expected] of [
      ['im', '/im'],
      ['project', '/projects'],
      ['automation', '/designer/workflows'],
    ] as const) {
      navigateMock.mockClear();
      mockedSearch.mockResolvedValue({
        code: 0,
        data: { keyword: type, total: 1, results: [result(type)], facets: {} },
      });
      const { unmount } = renderPanel();
      fireEvent.click(screen.getAllByRole('button')[0]);
      const input = await screen.findByRole('textbox');
      fireEvent.change(input, { target: { value: type } });
      await waitFor(() => expect(screen.getByText(new RegExp(`${type} hit`))).toBeInTheDocument());
      fireEvent.click(screen.getByText(new RegExp(`${type} hit`)));
      await waitFor(() => expect(navigateMock).toHaveBeenCalledWith(expected));
      unmount();
    }
  });

  it('未知类型跳转首页', async () => {
    mockedSearch.mockResolvedValue({
      code: 0,
      data: { keyword: 'x', total: 1, results: [result('weird')], facets: {} },
    });
    renderPanel();
    fireEvent.click(screen.getAllByRole('button')[0]);
    const input = await screen.findByRole('textbox');
    fireEvent.change(input, { target: { value: 'x' } });
    await waitFor(() => expect(screen.getByText(/weird hit/)).toBeInTheDocument());
    fireEvent.click(screen.getByText(/weird hit/));
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/'));
  });
});
