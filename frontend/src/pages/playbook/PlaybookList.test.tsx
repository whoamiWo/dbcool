/**
 * PHASE 4.2 覆盖率补测：pages/playbook/PlaybookList.tsx (简化版 — 仅验证关键路径)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { PlaybookListPage } from './PlaybookList';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'zh-CN' } }) }));

const mockGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const mockPost = apiClient.post as unknown as ReturnType<typeof vi.fn>;

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <PlaybookListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => vi.clearAllMocks());

describe('PlaybookListPage', () => {
  it('加载中渲染 loading', () => {
    mockGet.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText('playbookList.loading')).toBeInTheDocument();
  });

  it('正常加载有剧本卡片', async () => {
    mockGet.mockResolvedValue({ code: 0, data: [{ id: 'pb1', name: '应急预案', status: 'PUBLISHED', description: 'd', updatedAt: '2024-01-01T00:00:00Z', createdAt: '2024-01-01T00:00:00Z' }] });
    renderPage();
    await waitFor(() => expect(screen.getByText('应急预案')).toBeInTheDocument());
  });

  it('点击新建打开对话框', async () => {
    mockGet.mockResolvedValue({ code: 0, data: [] });
    renderPage();
    await waitFor(() => expect(screen.getByText('playbookList.newPlaybook')).toBeInTheDocument());
    fireEvent.click(screen.getByText('playbookList.newPlaybook'));
    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());
  });

  it('提交新建后调用 post', async () => {
    mockGet.mockResolvedValue({ code: 0, data: [] });
    mockPost.mockResolvedValue({ code: 0, data: { id: 'pb3' } });
    renderPage();
    await waitFor(() => expect(screen.getByText('playbookList.newPlaybook')).toBeInTheDocument());
    fireEvent.click(screen.getByText('playbookList.newPlaybook'));
    const dialog = await screen.findByRole('dialog');
    const input = dialog.querySelector('input') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '新剧本' } });
    fireEvent.click(screen.getByText('common.create'));
    await waitFor(() => expect(mockPost).toHaveBeenCalled());
  });
});
