/**
 * PHASE 4.2 覆盖率补测：pages/WorkbenchPage.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import WorkbenchPage from './Workbench';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn().mockResolvedValue({ code: 0, data: [] }),
  },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
  }),
}));

import apiClient from '@/api/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigateMock };
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <WorkbenchPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('WorkbenchPage', () => {
  it('默认展示所有入口卡片', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 0, data: [] });
    renderPage();
    await waitFor(() => {
      expect(screen.getByText('workbench.cardDataModel')).toBeInTheDocument();
      expect(screen.getAllByText('workbench.cardBi').length).toBeGreaterThan(0);
      expect(screen.getByText('workbench.cardProject')).toBeInTheDocument();
      expect(screen.getByText('workbench.cardIm')).toBeInTheDocument();
      expect(screen.getAllByText('workbench.cardWorkflow').length).toBeGreaterThan(0);
      expect(screen.getByText('workbench.cardWiki')).toBeInTheDocument();
    });
  });

  it('导航到各入口', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('workbench.cardDataModel')).toBeInTheDocument());
    fireEvent.click(screen.getByText('workbench.cardDataModel'));
    expect(navigateMock).toHaveBeenCalledWith('/designer/schemas');
  });

  it('搜索框过滤卡片', async () => {
    renderPage();
    await waitFor(() => expect(screen.getAllByText('workbench.cardBi').length).toBeGreaterThan(0));
    const input = screen.getByPlaceholderText('workbench.searchPlaceholder');
    fireEvent.change(input, { target: { value: 'zzz-no-match' } });
    expect(screen.queryByText('workbench.cardDataModel')).not.toBeInTheDocument();
  });

  it('空快速访问列表', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('workbench.quickAccess')).toBeInTheDocument());
    expect(screen.getByText('workbench.quickKb')).toBeInTheDocument();
  });

  it('集合数据解析为列表', async () => {
    const collections = [{ name: 'orders', title: 'Orders' }];
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: collections })
      .mockResolvedValue({ code: 0, data: [] });
    renderPage();
    await waitFor(() => expect(screen.getByText('workbench.cardDataModel')).toBeInTheDocument());
  });
});
