import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { CollectionsListPage } from './CollectionsList';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn() },
}));

describe('CollectionsListPage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  const renderPage = () =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <CollectionsListPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('加载中:显示"加载中…"', () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('加载失败:显示"加载失败"', async () => {
    vi.mocked(apiClient.get).mockRejectedValue(new Error('网络错误'));
    renderPage();
    expect(await screen.findByText(/加载失败/)).toBeInTheDocument();
  });

  it('空列表:显示"还没有 Collection" + 引导', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage();
    expect(await screen.findByText(/还没有 Collection/)).toBeInTheDocument();
    // 标题计数为 0
    expect(screen.getByText(/📚 Collections\(0\)/)).toBeInTheDocument();
  });

  it('有数据:渲染表格 + 名称 + 标题 + 字段数 + 打开链接', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([
      { id: 'c1', name: 'posts', title: 'Posts', fields_json: '[{"name":"x"},{"name":"y"}]', created_at: '2026-01-15T10:00:00Z' },
      { id: 'c2', name: 'comments', title: 'Comments', fields_json: '[]', created_at: '2026-02-20T14:30:00Z' },
    ] as any);

    renderPage();

    // 标题计数为 2
    expect(await screen.findByText(/📚 Collections\(2\)/)).toBeInTheDocument();
    // 名称
    expect(screen.getByText('posts')).toBeInTheDocument();
    expect(screen.getByText('comments')).toBeInTheDocument();
    // 标题
    expect(screen.getByText('Posts')).toBeInTheDocument();
    expect(screen.getByText('Comments')).toBeInTheDocument();
    // 字段数
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('0')).toBeInTheDocument();
    // 打开链接
    const openLinks = screen.getAllByText(/打开/);
    expect(openLinks.length).toBe(2);
  });

  it('"+ 新建 Collection" 按钮跳转到 /designer/schemas', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage();

    const newLink = await screen.findByRole('link', { name: /新建 Collection/ });
    expect(newLink).toHaveAttribute('href', '/designer/schemas');
  });
});
