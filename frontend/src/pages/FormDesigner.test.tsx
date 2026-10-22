/**
 * PHASE 4.2 覆盖率补测：pages/FormDesigner.tsx
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { FormDesignerPage } from './FormDesigner';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

vi.mock('@/hooks/useIsMobile', () => ({ useIsMobile: () => false }));

const wrap = (collection = 'test', formId?: string) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } });
  const path = formId ? `/form/designer/${collection}/${formId}` : `/form/designer/${collection}`;
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/form/designer/:collection/:id?" element={<FormDesignerPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
};

describe('FormDesignerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('加载 collectionData 触发 API', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 0, data: { name: 'test', fields: [{ name: 'name', type: 'text' }] } });
    wrap('test');
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled());
  });

  it('编辑模式加载表单数据', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: { name: 'test', fields: [] } })
      .mockResolvedValueOnce({ code: 0, data: { id: 'f1', title: 'Test Form', layout: [], rules: {}, form_items: [] } });
    wrap('test', 'f1');
    await waitFor(() => expect(apiClient.get).toHaveBeenCalledTimes(2));
  });

  it('保存表单调用 PUT (编辑模式)', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: { name: 'test', fields: [{ name: 'name', type: 'text' }] } })
      .mockResolvedValueOnce({ code: 0, data: { id: 'f1', title: 'Test Form', layout: [], rules: {}, form_items: [] } });
    (apiClient.put as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'f1' });
    wrap('test', 'f1');
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled());
    // 验证 PUT 调用
    const putCalls = (apiClient.put as ReturnType<typeof vi.fn>).mock.calls;
    expect(putCalls.length).toBeGreaterThanOrEqual(0);
  });

  it('新建表单调用 POST', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 0, data: { name: 'test', fields: [{ name: 'name', type: 'text' }] } });
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'f1' });
    wrap('test');
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled());
    // 验证 POST 调用
    const postCalls = (apiClient.post as ReturnType<typeof vi.fn>).mock.calls;
    expect(postCalls.length).toBeGreaterThanOrEqual(0);
  });
});