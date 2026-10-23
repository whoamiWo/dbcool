/**
 * PHASE 4.2 覆盖率补测：components/RecordVersionHistory.tsx
 * 覆盖：open 时才查询、版本列表渲染（recordVersion.version N）、选中查看变更 diff、
 * 恢复成功/失败、关闭重置、envelope 兼容。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RecordVersionHistory } from './RecordVersionHistory';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

const mockedGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const mockedPost = apiClient.post as unknown as ReturnType<typeof vi.fn>;

// changedFields 在后端为 JSON 字符串（组件内 parseDiffs 会 JSON.parse）
const versions = [
  { version: 2, operation: 'UPDATE', changedFields: JSON.stringify([{ field: 'status', oldValue: 'TODO', newValue: 'DONE' }]), changedAt: '2024-01-02T00:00:00Z' },
  { version: 1, operation: 'CREATE', changedFields: JSON.stringify([{ field: 'title', oldValue: 'a', newValue: 'b' }]), changedAt: '2024-01-01T00:00:00Z' },
];

function renderPanel(open = true) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <RecordVersionHistory collectionName="orders" recordId="r1" open={open} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGet.mockResolvedValue({ versions });
});

describe('RecordVersionHistory', () => {
  it('open=false 时不发起查询', () => {
    renderPanel(false);
    expect(mockedGet).not.toHaveBeenCalled();
  });

  it('打开后加载版本列表', async () => {
    renderPanel();
    await waitFor(() =>
      expect(mockedGet).toHaveBeenCalledWith('/collections/orders/records/r1/versions'),
    );
  });

  it('渲染标题与版本号', async () => {
    renderPanel();
    await waitFor(() => expect(screen.getByText(/recordVersion.title/)).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText(/recordVersion.version 2/)).toBeInTheDocument());
    expect(screen.getByText(/recordVersion.version 1/)).toBeInTheDocument();
  });

  it('渲染操作类型 Chip', async () => {
    renderPanel();
    await waitFor(() => expect(screen.getByText('UPDATE')).toBeInTheDocument());
    expect(screen.getByText('CREATE')).toBeInTheDocument();
  });

  it('未选中时展示选择提示', async () => {
    renderPanel();
    await waitFor(() => expect(screen.getByText('recordVersion.selectHint')).toBeInTheDocument());
  });

  it('选中版本后渲染字段变更表', async () => {
    renderPanel();
    await waitFor(() => expect(screen.getByText(/recordVersion.version 2/)).toBeInTheDocument());
    fireEvent.click(screen.getByText(/recordVersion.version 2/));
    await waitFor(() => expect(screen.getByText('status')).toBeInTheDocument());
    expect(screen.getByText('TODO')).toBeInTheDocument();
    expect(screen.getByText('DONE')).toBeInTheDocument();
    expect(screen.getByText('recordVersion.field')).toBeInTheDocument();
  });

  it('恢复成功调用 POST 恢复接口', async () => {
    mockedPost.mockResolvedValue({});
    renderPanel();
    await waitFor(() => expect(screen.getByText(/recordVersion.version 2/)).toBeInTheDocument());
    fireEvent.click(screen.getByText(/recordVersion.version 2/));
    await waitFor(() => expect(screen.getByText('recordVersion.restore')).toBeInTheDocument());
    fireEvent.click(screen.getByText('recordVersion.restore'));
    await waitFor(() =>
      expect(mockedPost).toHaveBeenCalledWith(
        '/collections/orders/records/r1/versions/2/restore',
      ),
    );
  });

  it('恢复失败展示错误信息', async () => {
    mockedPost.mockRejectedValue({ response: { data: { message: 'restore boom' } } });
    renderPanel();
    await waitFor(() => expect(screen.getByText(/recordVersion.version 2/)).toBeInTheDocument());
    fireEvent.click(screen.getByText(/recordVersion.version 2/));
    await waitFor(() => expect(screen.getByText('recordVersion.restore')).toBeInTheDocument());
    fireEvent.click(screen.getByText('recordVersion.restore'));
    await waitFor(() => expect(screen.getByText('restore boom')).toBeInTheDocument());
  });

  it('恢复失败且无后端 message 时展示默认文案', async () => {
    mockedPost.mockRejectedValue(new Error('plain'));
    renderPanel();
    await waitFor(() => expect(screen.getByText(/recordVersion.version 2/)).toBeInTheDocument());
    fireEvent.click(screen.getByText(/recordVersion.version 2/));
    await waitFor(() => expect(screen.getByText('recordVersion.restore')).toBeInTheDocument());
    fireEvent.click(screen.getByText('recordVersion.restore'));
    await waitFor(() => expect(screen.getByText('recordVersion.restoreFailed')).toBeInTheDocument());
  });

  it('接口返回 envelope 结构也能解析', async () => {
    mockedGet.mockResolvedValue({ code: 0, data: { versions } });
    renderPanel();
    await waitFor(() => expect(screen.getByText(/recordVersion.version 2/)).toBeInTheDocument());
  });

  it('无版本数据时渲染空态', async () => {
    mockedGet.mockResolvedValue({ versions: [] });
    renderPanel();
    await waitFor(() => expect(screen.getByText('recordVersion.empty')).toBeInTheDocument());
  });

  it('点击取消触发 onClose', async () => {
    const onClose = vi.fn();
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    render(
      <QueryClientProvider client={qc}>
        <RecordVersionHistory collectionName="orders" recordId="r1" open onClose={onClose} />
      </QueryClientProvider>,
    );
    await waitFor(() => expect(screen.getByText('common.cancel')).toBeInTheDocument());
    fireEvent.click(screen.getByText('common.cancel'));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
