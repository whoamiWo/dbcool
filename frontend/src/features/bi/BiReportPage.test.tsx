/**
 * PHASE 4.2 覆盖率补测：features/bi/BiReportPage.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import BiReportPage from './BiReportPage';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });

describe('BiReportPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('加载报告配置', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ fields: [], charts: [] });
    render(<QueryClientProvider client={qc}><MemoryRouter><BiReportPage /></MemoryRouter></QueryClientProvider>);
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled(), { timeout: 3000 });
  });
});