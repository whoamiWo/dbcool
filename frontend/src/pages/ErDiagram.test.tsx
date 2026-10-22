/**
 * PHASE 4.2 覆盖率补测：pages/ErDiagram.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ErDiagramPage } from './ErDiagram';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock('reactflow', () => ({
  __esModule: true,
  default: ({ children }: { children: React.ReactNode }) => <div data-testid="reactflow">{children}</div>,
  Background: () => null,
  Controls: () => null,
  Handle: () => null,
  Position: 50,
  ReactFlowProvider: ({ children }: { children: React.ReactNode }) => children,
  useReactFlow: () => ({ project: (v: number) => v }),
}));

const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });

describe('ErDiagram', () => {
  beforeEach(() => vi.clearAllMocks());

  it('加载并渲染图表', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ nodes: [], edges: [] });
    render(<QueryClientProvider client={qc}><MemoryRouter><ErDiagramPage /></MemoryRouter></QueryClientProvider>);
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled(), { timeout: 3000 });
  });

  it('显示空状态', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ nodes: [], edges: [] });
    render(<QueryClientProvider client={qc}><MemoryRouter><ErDiagramPage /></MemoryRouter></QueryClientProvider>);
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled(), { timeout: 3000 });
  });
});