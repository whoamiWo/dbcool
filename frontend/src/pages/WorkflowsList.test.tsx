/**
 * PHASE 4.2 覆盖率补测：pages/WorkflowsList.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { WorkflowsListPage } from './WorkflowsList';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });

describe('WorkflowsList', () => {
  beforeEach(() => vi.clearAllMocks());

  it('加载工作流列表', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValueOnce([]);
    render(<QueryClientProvider client={qc}><MemoryRouter><WorkflowsListPage /></MemoryRouter></QueryClientProvider>);
    await waitFor(() => expect(apiClient.get).toHaveBeenCalledWith('/workflows'), { timeout: 3000 });
  });

  it('显示工作流卡片', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValueOnce([{ id: 'w1', name: 'wf1', title: 'Workflow 1' }]);
    render(<QueryClientProvider client={qc}><MemoryRouter><WorkflowsListPage /></MemoryRouter></QueryClientProvider>);
    await waitFor(() => expect(screen.getByText('Workflow 1')).toBeInTheDocument(), { timeout: 3000 });
  });
});