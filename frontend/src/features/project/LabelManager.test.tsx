import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { LabelManager } from './LabelManager';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string, o?: any) => (o?.defaultValue ? `${k}:${o.defaultValue}` : k) }),
}));

describe('LabelManager（Wave4 #15 标签管理 UI）', () => {
  let qc: QueryClient;

  beforeEach(() => {
    vi.clearAllMocks();
    qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } });
    vi.mocked(apiClient.get).mockResolvedValue({
      code: 0,
      data: [
        { id: 'l1', name: '紧急', color: '#eb5757' },
        { id: 'l2', name: '常规', color: '#2f80ed' },
      ],
    } as any);
    vi.mocked(apiClient.post).mockResolvedValue({ code: 0, data: { id: 'l3' } } as any);
    vi.mocked(apiClient.put).mockResolvedValue({ code: 0, data: { id: 'l1' } } as any);
    vi.mocked(apiClient.delete).mockResolvedValue({ code: 0, data: { id: 'l1' } } as any);
  });

  const renderManager = () =>
    render(
      <QueryClientProvider client={qc}>
        <LabelManager projectId="proj-1" />
      </QueryClientProvider>,
    );

  it('加载并渲染项目标签列表', async () => {
    renderManager();
    await waitFor(() => {
      expect(apiClient.get).toHaveBeenCalledWith('/project-boards/labels/by-project/proj-1');
    });
    expect(await screen.findByText('紧急')).toBeInTheDocument();
    expect(screen.getByText('常规')).toBeInTheDocument();
  });

  it('新建标签调用 create 端点', async () => {
    renderManager();
    const input = await screen.findByLabelText('board.labelNamePlaceholder');
    fireEvent.change(input, { target: { value: '新标签' } });
    fireEvent.click(screen.getByText('board.labelCreate'));
    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith('/project-boards/labels', {
        projectId: 'proj-1', name: '新标签', color: expect.any(String),
      });
    });
  });

  it('删除标签调用 delete 端点', async () => {
    renderManager();
    const btn = await screen.findByLabelText('board.labelDelete 紧急');
    fireEvent.click(btn);
    await waitFor(() => {
      expect(apiClient.delete).toHaveBeenCalledWith('/project-boards/labels/l1');
    });
  });

  it('重命名标签调用 put 端点', async () => {
    renderManager();
    const btn = await screen.findByLabelText('board.labelRename 紧急');
    fireEvent.click(btn);
    const input = await screen.findByLabelText('board.labelRename');
    fireEvent.change(input, { target: { value: '非常紧急' } });
    fireEvent.click(screen.getByText('board.labelSave'));
    await waitFor(() => {
      expect(apiClient.put).toHaveBeenCalledWith('/project-boards/labels/l1', { name: '非常紧急' });
    });
  });
});
