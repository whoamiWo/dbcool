import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { RecordDetailPage } from './RecordDetailPage';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn() },
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/records/customer/r1']}>
      <Routes>
        <Route path="/records/:collection/:recordId" element={<RecordDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RecordDetailPage（R1a 搜索落地页）', () => {
  beforeEach(() => vi.clearAllMocks());

  it('加载集合元数据与单条记录并渲染字段', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockImplementation((url: string) => {
      if (url.endsWith('/records/r1')) {
        return Promise.resolve({ code: 0, data: { id: 'r1', name: '华东制造', amount: 100 } });
      }
      return Promise.resolve({
        code: 0,
        data: {
          name: 'customer',
          title: '客户',
          fields: [
            { name: 'name', label: '客户名称', type: 'text' },
            { name: 'amount', label: '金额', type: 'number' },
          ],
        },
      });
    });

    renderPage();

    await waitFor(() => {
      expect(screen.getAllByText('华东制造').length).toBeGreaterThan(0);
      expect(screen.getByText('客户名称')).toBeInTheDocument();
      expect(screen.getByText('金额')).toBeInTheDocument();
      expect(screen.getByText('100')).toBeInTheDocument();
    });
  });

  it('404 → 明确显示不存在/无权访问，而非空白页', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockImplementation((url: string) => {
      if (url.endsWith('/records/r1')) {
        return Promise.reject({ response: { status: 404, data: { message: '记录不存在' } } });
      }
      return Promise.resolve({ code: 0, data: { name: 'customer', fields: [] } });
    });

    renderPage();

    await waitFor(() => {
      expect(screen.getByText('记录不存在或无权访问')).toBeInTheDocument();
      expect(screen.getByText('返回搜索')).toBeInTheDocument();
    });
  });
});
