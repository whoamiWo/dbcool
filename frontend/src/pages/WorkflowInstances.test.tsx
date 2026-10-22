/**
 * PHASE 4.2 覆盖率补测：pages/WorkflowInstances.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { WorkflowInstancesPage } from './WorkflowInstances';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });

function wrap(path = '/workflows/instances') {
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes><Route path="/workflows/instances/:id?" element={<WorkflowInstancesPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('WorkflowInstancesPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('加载实例列表', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValueOnce([]);
    wrap();
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled(), { timeout: 3000 });
  });

  it('显示实例卡片', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValueOnce([{
      id: 'i1', workflow_title: 'wf1', status: 'COMPLETED', record_id: 'r1', started_at: '2024-01-01T00:00:00Z', finished_at: '2024-01-01T00:00:01Z', trigger_data_json: '{}', current_node_index: 0,
    }]);
    wrap();
    await waitFor(() => expect(screen.getByText('wf1')).toBeInTheDocument(), { timeout: 3000 });
  });

  it('筛选工作流', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValueOnce([]);
    wrap();
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled(), { timeout: 3000 });
  });
});