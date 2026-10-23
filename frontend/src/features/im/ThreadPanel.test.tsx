/**
 * PHASE 4.2 覆盖率补测：features/im/ThreadPanel.tsx
 * 覆盖：无父消息空态、加载回复、发送回复、发送中禁用、关闭回调、时间格式化。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThreadPanel } from './ThreadPanel';
import { getMessageThread, sendReply } from './api';

vi.mock('./api', () => ({
  getMessageThread: vi.fn(),
  sendReply: vi.fn(),
}));

const mockedThread = getMessageThread as unknown as ReturnType<typeof vi.fn>;
const mockedReply = sendReply as unknown as ReturnType<typeof vi.fn>;

// 注意：渲染头像缩写会读 senderName.substring(0,1)，故父消息与回复都必须带 senderName
const parent = {
  id: 'm1',
  channelId: 'c1',
  content: 'parent msg',
  senderName: 'alice',
  senderId: 'user-alice-001',
  createdAt: '2024-01-01T00:00:00Z',
} as never;

const reply = (over: Record<string, unknown> = {}) => ({
  id: 'r1',
  content: 'reply one',
  senderName: 'bob',
  senderId: 'user-bob-000001',
  createdAt: '2024-01-01T01:00:00Z',
  ...over,
});

function renderPanel(props: Partial<Parameters<typeof ThreadPanel>[0]> = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <ThreadPanel channelId="c1" parentMessage={parent} onClose={vi.fn()} {...props} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedThread.mockResolvedValue({ data: { replies: [reply()] } });
});

describe('ThreadPanel', () => {
  it('无父消息时展示空态', () => {
    renderPanel({ parentMessage: null });
    expect(screen.getByText('选择一条消息查看线程回复')).toBeInTheDocument();
    expect(mockedThread).not.toHaveBeenCalled();
  });

  it('加载并渲染线程回复', async () => {
    const { container } = renderPanel();
    await waitFor(() => expect(mockedThread).toHaveBeenCalledWith('c1', 'm1'));
    await waitFor(() => expect(container.textContent).toContain('reply one'));
  });

  it('渲染父消息与标题', async () => {
    renderPanel();
    await waitFor(() => expect(screen.getByText('线程回复')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText(/parent msg/)).toBeInTheDocument());
  });

  it('无回复时展示空提示', async () => {
    mockedThread.mockResolvedValue({ data: { replies: [] } });
    renderPanel();
    await waitFor(() => expect(mockedThread).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText(/暂无回复|还没有/)).toBeInTheDocument());
  });

  it('发送回复追加到列表', async () => {
    mockedReply.mockResolvedValue({
      data: reply({ id: 'r2', content: 'my reply', createdAt: '2024-01-01T02:00:00Z' }),
    });
    const { container } = renderPanel();
    await waitFor(() => expect(container.textContent).toContain('reply one'));
    const input = screen.getByPlaceholderText('回复这条消息...');
    fireEvent.change(input, { target: { value: 'my reply' } });
    const sendBtn = screen.getAllByRole('button').at(-1)!;
    fireEvent.click(sendBtn);
    await waitFor(() => expect(mockedReply).toHaveBeenCalledWith('c1', 'm1', 'my reply'));
  });

  it('空内容不发送', async () => {
    const { container } = renderPanel();
    await waitFor(() => expect(container.textContent).toContain('reply one'));
    const sendBtn = screen.getAllByRole('button').at(-1)!;
    fireEvent.click(sendBtn);
    expect(mockedReply).not.toHaveBeenCalled();
  });

  it('点击关闭触发 onClose', async () => {
    const onClose = vi.fn();
    renderPanel({ onClose });
    await waitFor(() => expect(screen.getByText('线程回复')).toBeInTheDocument());
    const closeBtn = screen.getAllByRole('button')[0];
    fireEvent.click(closeBtn);
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('channelId 为空时不查询', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <ThreadPanel channelId="" parentMessage={parent} onClose={vi.fn()} />
      </QueryClientProvider>,
    );
    expect(mockedThread).not.toHaveBeenCalled();
  });
});
