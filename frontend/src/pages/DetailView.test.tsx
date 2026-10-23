/**
 * PHASE 4.2 覆盖率补测：pages/DetailView.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useParams } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DetailViewPage } from './DetailView';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock('react-router-dom', async () => {
  const mod = await vi.importActual('react-router-dom');
  return { ...mod, useParams: vi.fn() };
});

const mockGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/views/v1/records/r1']}>
        <DetailViewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  (useParams as any).mockReturnValue({ id: 'v1', recordId: 'r1' });
  // 用不同的 mockResolvedValueFor 分别处理三个 API
  const viewFn = vi.fn().mockResolvedValue({ collection_name: 'users', title: '用户详情', config: { fields: ['name', 'email'] } });
  const collFn = vi.fn().mockResolvedValue({ fields: [{ name: 'name', label: '姓名' }, { name: 'email', label: '邮箱' }] });
  const recFn = vi.fn().mockResolvedValue([{ id: 'r1', name: 'Alice', email: 'a@x.com' }]);
  (apiClient.get as any).mockImplementation((url: string) => {
    if (url.includes('/views/')) return viewFn(url);
    if (url.includes('/records')) return recFn(url);
    return collFn(url);
  });
});

describe('DetailViewPage', () => {
  it('加载中渲染 loading', () => {
    mockGet.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText('detail.loading')).toBeInTheDocument();
  });

  it('渲染详情字段', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('用户详情')).toBeInTheDocument());
    expect(screen.getByText('姓名')).toBeInTheDocument();
    expect(screen.getByText('a@x.com')).toBeInTheDocument();
  });

  it('隐藏字段不渲染', async () => {
    const viewFn = vi.fn().mockResolvedValue({ collection_name: 'users', title: '用户详情', config: { fields: ['name'], hiddenFields: ['email'] } });
    const recFn = vi.fn().mockResolvedValue([{ id: 'r1', name: 'Alice', email: 'a@x.com' }]);
    const collFn = vi.fn().mockResolvedValue({ fields: [{ name: 'name', label: '姓名' }, { name: 'email', label: '邮箱' }] });
    (apiClient.get as any).mockImplementation((url: string) => {
      if (url.includes('/views/')) return viewFn(url);
      if (url.includes('/records')) return recFn(url);
      return collFn(url);
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('用户详情')).toBeInTheDocument());
    expect(screen.queryByText('邮箱')).not.toBeInTheDocument();
  });

  it('记录不存在显示 notFound', async () => {
    const viewFn = vi.fn().mockResolvedValue({ collection_name: 'users', title: '用户详情', config: { fields: ['name'] } });
    const collFn = vi.fn().mockResolvedValue({ fields: [{ name: 'name', label: '姓名' }] });
    const recFn = vi.fn().mockResolvedValue([{ id: 'other' }]);
    (apiClient.get as any).mockImplementation((url: string) => {
      if (url.includes('/views/')) return viewFn(url);
      if (url.includes('/records')) return recFn(url);
      return collFn(url);
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('detail.recordNotFound')).toBeInTheDocument());
  });
});
