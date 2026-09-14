import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { FormsListPage } from './FormsList';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn() },
}));

describe('FormsListPage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.clearAllMocks();
  });

  const renderPage = (initialPath: string = '/designer/forms') =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/designer/forms" element={<FormsListPage />} />
            <Route path="/designer/forms/:collection" element={<FormsListPage />} />
          </Routes>
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

  it('无 collection:列表标题不带后缀 + 没有"新建表单"按钮', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage();

    expect(await screen.findByText(/📋 表单\(0\)/)).toBeInTheDocument();
    // 没有 collection 时不显示"新建表单"按钮
    expect(screen.queryByText(/新建表单/)).not.toBeInTheDocument();
  });

  it('有 collection:标题含 collection 名 + 显示"新建表单"按钮', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage('/designer/forms/posts');

    expect(await screen.findByText(/📋 表单 \(posts\)\(0\)/)).toBeInTheDocument();
    // "新建表单" 出现在 button text + 空状态描述,至少 1 个
    expect(screen.getAllByText(/新建表单/).length).toBeGreaterThanOrEqual(1);
  });

  it('有数据:渲染表格 + 表单信息', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([
      { id: 'f1', title: '订单表单', collection_name: 'orders', created_at: '2026-01-15T10:00:00Z' },
      { id: 'f2', title: '评论表单', collection_name: 'comments', created_at: '2026-02-20T14:30:00Z' },
    ] as any);

    renderPage();

    expect(await screen.findByText(/📋 表单\(2\)/)).toBeInTheDocument();
    expect(screen.getByText('订单表单')).toBeInTheDocument();
    expect(screen.getByText('评论表单')).toBeInTheDocument();
    expect(screen.getByText('orders')).toBeInTheDocument();
    expect(screen.getByText('comments')).toBeInTheDocument();
  });

  it('空列表 + 有 collection:显示 collection 特定提示', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([] as any);
    renderPage('/designer/forms/posts');

    expect(await screen.findByText(/该 collection 还没有表单/)).toBeInTheDocument();
  });
});
