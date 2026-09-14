import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { MessagesInboxPage } from './MessagesInbox';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

const sampleMessages = [
  {
    id: 'm1', type: 'workflow', title: '订单待审批',
    body: '订单 #123 等待您审批', related_id: '123',
    read: false, created_at: '2026-01-15T10:00:00Z',
  },
  {
    id: 'm2', type: 'system', title: '系统通知',
    body: '欢迎使用', related_id: '',
    read: true, created_at: '2026-01-10T08:00:00Z',
  },
];

const wrap = () => {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <MessagesInboxPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
};

describe('MessagesInboxPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('加载中:显示"加载中…"', async () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    wrap();
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('加载成功:渲染消息列表 + 未读数', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      unread_count: 5,
      messages: sampleMessages,
      limit: 20,
      next_cursor: '',
      has_more: false,
    } as any);

    wrap();

    expect(await screen.findByText('订单待审批')).toBeInTheDocument();
    expect(screen.getByText('系统通知')).toBeInTheDocument();
    expect(screen.getByText('订单 #123 等待您审批')).toBeInTheDocument();
    // 未读数
    expect(screen.getByText('5')).toBeInTheDocument();
    // 未读徽章
    expect(screen.getByText(/● 未读/)).toBeInTheDocument();
  });

  it('空消息:显示"暂无消息"', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      unread_count: 0,
      messages: [],
      limit: 20,
      next_cursor: '',
      has_more: false,
    } as any);

    wrap();

    expect(await screen.findByText(/暂无消息/)).toBeInTheDocument();
  });

  it('has_more=true:显示"加载更多"按钮', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      unread_count: 1,
      messages: [sampleMessages[0]],
      limit: 20,
      next_cursor: 'cursor-abc',
      has_more: true,
    } as any);

    wrap();

    const btn = await screen.findByRole('button', { name: /加载更多/ });
    expect(btn).toBeInTheDocument();
  });

  it('点击未读消息:调 markRead POST', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      unread_count: 1,
      messages: [sampleMessages[0]],
      limit: 20,
      next_cursor: '',
      has_more: false,
    } as any);
    vi.mocked(apiClient.post).mockResolvedValue({} as any);

    wrap();
    await screen.findByText('订单待审批');

    // 点击消息卡片
    fireEvent.click(screen.getByText('订单待审批'));

    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith('/messages/m1/read');
    });
  });

  it('已读消息点击:不调 markRead', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      unread_count: 0,
      messages: [sampleMessages[1]], // m2 是已读
      limit: 20,
      next_cursor: '',
      has_more: false,
    } as any);

    wrap();
    await screen.findByText('系统通知');

    fireEvent.click(screen.getByText('系统通知'));

    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it('勾选"只看未读":调 API 带 unreadOnly=true', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      unread_count: 0,
      messages: [],
      limit: 20,
      next_cursor: '',
      has_more: false,
    } as any);

    wrap();
    await screen.findByText(/暂无消息/);

    // 勾选 checkbox
    const checkbox = screen.getByRole('checkbox');
    fireEvent.click(checkbox);

    await waitFor(() => {
      const calls = vi.mocked(apiClient.get).mock.calls;
      const lastUrl = calls[calls.length - 1][0];
      expect(lastUrl).toMatch(/unreadOnly=true/);
    });
  });
});
