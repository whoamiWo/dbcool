import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { FormTemplatesListPage } from './FormTemplatesList';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

describe('FormTemplatesListPage', () => {
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
        <FormTemplatesListPage />
      </QueryClientProvider>,
    );

  it('加载中:显示"加载中..."', () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('加载成功:渲染模板列表', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 0,
      message: '',
      data: [
        { id: 't1', title: '客户登记表', collection_name: 'customers', created_at: '2026-01-01T00:00:00Z' },
      ],
    });

    renderPage();

    expect(await screen.findByText(/客户登记表/)).toBeInTheDocument();
    expect(screen.getByText('customers')).toBeInTheDocument();
  });

  it('空列表:显示空状态', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ code: 0, message: '', data: [] });

    renderPage();

    expect(await screen.findByText(/暂无表单模板/)).toBeInTheDocument();
  });

  it('创建成功:表单重置', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ code: 0, message: '', data: [] });
    vi.mocked(apiClient.post).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { id: 't2', title: '新模板', collection_name: 'orders' },
    });

    renderPage();

    const createBtn = await screen.findByRole('button', { name: /新建模板/ });
    expect(createBtn).toBeInTheDocument();
    createBtn.click();

    expect(await screen.findByPlaceholderText(/例如：客户登记表/)).toBeInTheDocument();
  });
});