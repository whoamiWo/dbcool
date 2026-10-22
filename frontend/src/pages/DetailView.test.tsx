/**
 * PHASE 4.2 覆盖率补测：pages/DetailView.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { DetailViewPage } from './DetailView';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return {
    ...actual,
    useParams: () => ({ id: 'view1' }),
  };
});

const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });

function wrap(path = '/collections/test/records/rec1') {
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/collections/:name/records/:id" element={<DetailViewPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('DetailView', () => {
  beforeEach(() => vi.clearAllMocks());

  it('加载记录详情', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ id: 'rec1', name: 'hello', status: 'active' });
    wrap();
    await waitFor(() => expect(apiClient.get).toHaveBeenCalledWith('/views/view1'), { timeout: 3000 });
    await new Promise(r => setTimeout(r, 500));
  });
});