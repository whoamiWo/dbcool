/**
 * PHASE 4.2 覆盖率补测：pages/RecycleBin.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RecycleBinPage } from './RecycleBin';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

import apiClient from '@/api/client';

function renderPage(name = 'orders') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/recycle/${name}`]}>
        <Routes>
          <Route path="/recycle/:name" element={<RecycleBinPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('RecycleBinPage', () => {
  it('加载时显示进度条', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { records: [] },
    });
    renderPage();
    expect(screen.getByRole('progressbar')).toBeInTheDocument();
  });

  it('加载成功后显示记录列表', async () => {
    const records = [
      { id: 'r1', name: 'Alice', age: 30 },
      { id: 'r2', name: 'Bob', status: 'active' },
    ];
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { records },
    });
    renderPage();
    await waitFor(() => {
      expect(screen.getByText('r1')).toBeInTheDocument();
      expect(screen.getByText('r2')).toBeInTheDocument();
    });
  });

  it('点击恢复按钮调用 restore', async () => {
    const records = [{ id: 'r1', name: 'Alice' }];
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { records },
    });
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 0 });
    renderPage();
    await waitFor(() => expect(screen.getByText('r1')).toBeInTheDocument());
    fireEvent.click(screen.getByText('recycleBin.restore'));
    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith('/collections/orders/recycle/r1/restore');
    });
  });

  it('点击彻底删除调用 delete', async () => {
    const records = [{ id: 'r1', name: 'Alice' }];
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { records },
    });
    (apiClient.delete as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 0 });
    renderPage();
    await waitFor(() => expect(screen.getByText('r1')).toBeInTheDocument());
    fireEvent.click(screen.getByText('recycleBin.purge'));
    await waitFor(() => {
      expect(apiClient.delete).toHaveBeenCalledWith('/collections/orders/recycle/r1');
    });
  });

  it('点击清空按钮打开二次确认弹窗', async () => {
    const records = [{ id: 'r1', name: 'Alice' }];
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { records },
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('r1')).toBeInTheDocument());
    fireEvent.click(screen.getByText('recycleBin.clear'));
    expect(screen.getByText('recycleBin.clearConfirmTitle')).toBeInTheDocument();
  });

  it('确认清空调用 delete 清空接口', async () => {
    const records = [{ id: 'r1', name: 'Alice' }];
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { records },
    });
    (apiClient.delete as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 0 });
    renderPage();
    await waitFor(() => expect(screen.getByText('r1')).toBeInTheDocument());
    const clearBtns = screen.getAllByText('recycleBin.clear');
    fireEvent.click(clearBtns[0]); // 打开确认弹窗
    expect(screen.getByText('recycleBin.clearConfirmTitle')).toBeInTheDocument();
    // 点击对话框内的确认按钮（第二个 clear 按钮）
    const dialogButtons = screen.getAllByRole('button');
    const confirmBtn = dialogButtons.find(btn => btn.textContent === 'recycleBin.clear');
    expect(confirmBtn).toBeInTheDocument();
    fireEvent.click(confirmBtn!);
    await waitFor(() => {
      expect(apiClient.delete).toHaveBeenCalledWith('/collections/orders/recycle');
    });
  });

  it('空记录显示空状态', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { records: [] },
    });
    renderPage();
    await waitFor(() => {
      expect(screen.getByText('recycleBin.empty')).toBeInTheDocument();
    });
  });

  it('恢复失败显示错误提示', async () => {
    const records = [{ id: 'r1', name: 'Alice' }];
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { records },
    });
    (apiClient.post as ReturnType<typeof vi.fn>).mockRejectedValue({
      response: { data: { message: 'Restore failed' } },
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('r1')).toBeInTheDocument());
    fireEvent.click(screen.getByText('recycleBin.restore'));
    await waitFor(() => {
      expect(screen.getByText('Restore failed')).toBeInTheDocument();
    });
  });
});
