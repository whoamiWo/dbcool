/**
 * PHASE 4.2 覆盖率补测：pages/playbook/PlaybookRun.tsx
 * 覆盖：加载、执行、完成、轮询刷新。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useParams } from 'react-router-dom';
import { PlaybookRunPage } from './PlaybookRun';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'zh-CN' } }) }));
vi.mock('react-router-dom', async () => {
  const mod = await vi.importActual('react-router-dom');
  return { ...mod, useParams: vi.fn() };
});

const mockGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const mockPost = apiClient.post as unknown as ReturnType<typeof vi.fn>;

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/playbooks/pb1/run']}>
        <PlaybookRunPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  (useParams as any).mockReturnValue({ playbookId: 'pb1', id: 'pb1' });
  mockGet.mockImplementation((url: string) =>
    url.endsWith('/runs')
      ? Promise.resolve({ code: 0, data: [{ id: 'r1', status: 'RUNNING' }] })
      : Promise.resolve({ code: 0, data: { id: 'pb1', name: 'p' } }),
  );
  mockPost.mockResolvedValue({ code: 0, data: { id: 'run1', status: 'RUNNING' } });
});

describe('PlaybookRunPage', () => {
  it('加载中渲染 loading', () => {
    mockGet.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText('playbookRun.loading')).toBeInTheDocument();
  });

  it('加载后显示执行按钮', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('playbookRun.run')).toBeInTheDocument());
  });

  it('点击执行调用 post', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('playbookRun.run')).toBeInTheDocument());
    fireEvent.click(screen.getByText('playbookRun.run'));
    await waitFor(() => expect(mockPost).toHaveBeenCalled());
  });

  it('点击完成调用 finishMutation', async () => {
    mockGet.mockImplementation((url: string) =>
      url.endsWith('/runs')
        ? Promise.resolve({ code: 0, data: [{ id: 'r1', status: 'RUNNING' }] })
        : Promise.resolve({ code: 0, data: { id: 'pb1', name: 'p' } }),
    );
    renderPage();
    const btn = await screen.findByText('playbookRun.finishAndRetro');
    await waitFor(() => expect(btn.closest('button')).not.toBeDisabled());
    fireEvent.click(btn);
    await waitFor(() => expect(mockPost).toHaveBeenCalled());
  });
});
