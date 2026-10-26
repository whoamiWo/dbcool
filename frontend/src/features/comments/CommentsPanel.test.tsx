/**
 * PHASE 4.2 覆盖率补测：features/comments/CommentsPanel.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CommentsPanel } from './CommentsPanel';
import * as api from './api';

vi.mock('./api', () => ({
  getRecordComments: vi.fn(),
  createComment: vi.fn(),
  updateComment: vi.fn(),
  deleteComment: vi.fn(),
}));

const mockGet = api.getRecordComments as unknown as ReturnType<typeof vi.fn>;
const mockPost = api.createComment as unknown as ReturnType<typeof vi.fn>;
const mockDel = api.deleteComment as unknown as ReturnType<typeof vi.fn>;

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <CommentsPanel collectionName="posts" recordId="p1" />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockGet.mockResolvedValue({ data: { comments: [], total: 0 } });
});

describe('CommentsPanel', () => {
  it('空状态渲染提示', async () => {
    renderPanel();
    await waitFor(() => expect(screen.getByText(/暂无评论/)).toBeInTheDocument());
  });

  it('渲染评论列表', async () => {
    mockGet.mockResolvedValue({
      data: {
        comments: [
          { id: 'c1', content: '第一条评论', parentId: null, replyCount: 0, replies: [], createdAt: '2026-01-01T00:00:00Z', authorId: 'u1', authorName: 'Alice', collectionName: 'posts', recordId: 'p1', contentType: 'TEXT' },
        ],
        total: 1,
      },
    });
    renderPanel();
    await waitFor(() => expect(screen.getByText('第一条评论')).toBeInTheDocument());
    expect(screen.getByText('Alice')).toBeInTheDocument();
  });

  it('发表评论', async () => {
    mockPost.mockResolvedValue({ data: { id: 'c2', content: '新评论', parentId: null, replyCount: 0, replies: [], createdAt: '2026-01-01T00:00:00Z', authorId: 'u2', authorName: 'Bob', collectionName: 'posts', recordId: 'p1', contentType: 'TEXT' } });
    renderPanel();
    await waitFor(() => expect(screen.getByPlaceholderText('写下你的评论...')).toBeInTheDocument());

    const textarea = screen.getByPlaceholderText('写下你的评论...');
    fireEvent.change(textarea, { target: { value: '新评论' } });
    fireEvent.click(screen.getByText('发表评论'));

    await waitFor(() => expect(mockPost).toHaveBeenCalledWith('posts', 'p1', { content: '新评论', contentType: 'TEXT' }));
  });

  it('已删除评论显示占位', async () => {
    mockGet.mockResolvedValue({
      data: {
        comments: [
          { id: 'c1', content: '', parentId: null, replyCount: 0, replies: [], createdAt: '2026-01-01T00:00:00Z', deletedAt: '2026-01-02T00:00:00Z', authorId: 'u1', authorName: 'Alice', collectionName: 'posts', recordId: 'p1', contentType: 'TEXT' },
        ],
        total: 1,
      },
    });
    renderPanel();
    await waitFor(() => expect(screen.getByText(/该评论已被删除/)).toBeInTheDocument());
  });

  it('删除评论', async () => {
    mockGet.mockResolvedValue({
      data: {
        comments: [
          { id: 'c1', content: '待删除', parentId: null, replyCount: 0, replies: [], createdAt: '2026-01-01T00:00:00Z', authorId: 'u1', authorName: 'Alice', collectionName: 'posts', recordId: 'p1', contentType: 'TEXT' },
        ],
        total: 1,
      },
    });
    mockDel.mockResolvedValue({ data: {} });
    renderPanel();
    await waitFor(() => expect(screen.getByText('待删除')).toBeInTheDocument());

    fireEvent.click(screen.getByText('删除'));
    await waitFor(() => expect(mockDel).toHaveBeenCalledWith('posts', 'p1', 'c1'));
  });
});
