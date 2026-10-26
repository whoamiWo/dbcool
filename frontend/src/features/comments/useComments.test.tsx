/**
 * PHASE 4.2 覆盖率补测：features/comments/useComments.ts
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useComments } from './useComments';
import * as api from './api';

const mockGet = api.getRecordComments as unknown as ReturnType<typeof vi.fn>;
const mockPost = api.createComment as unknown as ReturnType<typeof vi.fn>;
const mockPut = api.updateComment as unknown as ReturnType<typeof vi.fn>;
const mockDel = api.deleteComment as unknown as ReturnType<typeof vi.fn>;

vi.mock('./api', () => ({
  getRecordComments: vi.fn(),
  createComment: vi.fn(),
  updateComment: vi.fn(),
  deleteComment: vi.fn(),
}));

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return {
    wrapper: ({ children }: any) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>,
    qc,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockGet.mockResolvedValue({ data: { comments: [], total: 0 } });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('useComments', () => {
  it('加载评论列表', async () => {
    const comments = [
      { id: 'c1', content: 'Hello', parentId: null, replyCount: 0, replies: [], createdAt: '2026-01-01T00:00:00Z', authorId: 'u1', authorName: 'Alice', collectionName: 'posts', recordId: 'p1', contentType: 'TEXT' },
    ];
    mockGet.mockResolvedValue({ data: { comments, total: 1 } });

    const { result } = renderHook(
      () => useComments({ collectionName: 'posts', recordId: 'p1' }),
      { wrapper: wrapper().wrapper }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.comments).toHaveLength(1);
    expect(result.current.total).toBe(1);
    expect(mockGet).toHaveBeenCalledWith('posts', 'p1');
  });

  it('创建评论（乐观更新）', async () => {
    const created = { id: 'c2', content: 'New!', parentId: null, replyCount: 0, replies: [], createdAt: '2026-01-01T00:00:00Z', authorId: 'u2', authorName: 'Bob', collectionName: 'posts', recordId: 'p1', contentType: 'TEXT' };
    mockGet.mockResolvedValue({ data: { comments: [], total: 0 } });
    // 模拟服务端：创建成功后，列表查询应返回包含新评论的数据
    mockPost.mockImplementation(async () => {
      mockGet.mockResolvedValue({ data: { comments: [created], total: 1 } });
      return { data: created };
    });

    const { result } = renderHook(
      () => useComments({ collectionName: 'posts', recordId: 'p1' }),
      { wrapper: wrapper().wrapper }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.comments).toHaveLength(0);

    await result.current.createComment({ content: 'New!', contentType: 'TEXT' });

    await waitFor(() => expect(result.current.comments).toHaveLength(1));
    expect(result.current.comments[0].content).toBe('New!');
    expect(mockPost).toHaveBeenCalledWith('posts', 'p1', { content: 'New!', contentType: 'TEXT' });
  });

  it('创建评论失败回滚', async () => {
    mockGet.mockResolvedValue({ data: { comments: [], total: 0 } });
    mockPost.mockRejectedValue(new Error('network'));

    const { result } = renderHook(
      () => useComments({ collectionName: 'posts', recordId: 'p1' }),
      { wrapper: wrapper().wrapper }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await expect(result.current.createComment({ content: 'Fail', contentType: 'TEXT' })).rejects.toThrow('network');
    await waitFor(() => expect(result.current.comments).toHaveLength(0));
  });

  it('编辑评论（乐观更新）', async () => {
    const initial = [{ id: 'c1', content: 'Old', parentId: null, replyCount: 0, replies: [], createdAt: '2026-01-01T00:00:00Z', authorId: 'u1', authorName: 'Alice', collectionName: 'posts', recordId: 'p1', contentType: 'TEXT' }];
    const updated = { ...initial[0], content: 'Updated' };
    mockGet.mockResolvedValue({ data: { comments: initial, total: 1 } });
    // 模拟服务端：编辑成功后，列表查询应返回更新后的评论
    mockPut.mockImplementation(async () => {
      mockGet.mockResolvedValue({ data: { comments: [updated], total: 1 } });
      return { data: updated };
    });

    const { result } = renderHook(
      () => useComments({ collectionName: 'posts', recordId: 'p1' }),
      { wrapper: wrapper().wrapper }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.comments[0].content).toBe('Old');

    await result.current.editComment('c1', { content: 'Updated', contentType: 'TEXT' });

    await waitFor(() => expect(result.current.comments[0].content).toBe('Updated'));
  });

  it('删除评论（软删乐观占位）', async () => {
    const initial = [{ id: 'c1', content: 'ToDelete', parentId: null, replyCount: 0, replies: [], createdAt: '2026-01-01T00:00:00Z', authorId: 'u1', authorName: 'Alice', collectionName: 'posts', recordId: 'p1', contentType: 'TEXT' }];
    mockGet.mockResolvedValue({ data: { comments: initial, total: 1 } });
    // 模拟服务端软删：删除成功后，列表查询返回内容已清空且带 deletedAt 的评论
    mockDel.mockImplementation(async () => {
      const softDeleted = { ...initial[0], content: '', deletedAt: '2026-01-02T00:00:00Z' };
      mockGet.mockResolvedValue({ data: { comments: [softDeleted], total: 1 } });
      return { data: {} };
    });

    const { result } = renderHook(
      () => useComments({ collectionName: 'posts', recordId: 'p1' }),
      { wrapper: wrapper().wrapper }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.comments[0].content).toBe('ToDelete');

    await result.current.removeComment('c1');

    await waitFor(() => expect(result.current.comments[0].content).toBe(''));
    expect(result.current.comments[0].deletedAt).toBeDefined();
  });

  it('评论树嵌套插入', async () => {
    const parent = { id: 'p1', content: 'Parent', parentId: null, replyCount: 0, replies: [], createdAt: '2026-01-01T00:00:00Z', authorId: 'u1', authorName: 'Alice', collectionName: 'posts', recordId: 'p1', contentType: 'TEXT' };
    mockGet.mockResolvedValue({ data: { comments: [parent], total: 1 } });
    const child = { id: 'c1', content: 'Child', parentId: 'p1', replyCount: 0, replies: [], createdAt: '2026-01-01T00:00:00Z', authorId: 'u2', authorName: 'Bob', collectionName: 'posts', recordId: 'p1', contentType: 'TEXT' };
    // 模拟服务端：回复成功后，列表查询返回父评论带 replies 的数据
    mockPost.mockImplementation(async () => {
      const parentWithReply = { ...parent, replies: [child], replyCount: 1 };
      mockGet.mockResolvedValue({ data: { comments: [parentWithReply], total: 1 } });
      return { data: child };
    });

    const { result } = renderHook(
      () => useComments({ collectionName: 'posts', recordId: 'p1' }),
      { wrapper: wrapper().wrapper }
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.comments[0].replies).toHaveLength(0);

    await result.current.createComment({ content: 'Child', contentType: 'TEXT', parentId: 'p1' });

    await waitFor(() => expect(result.current.comments[0].replies).toHaveLength(1));
    expect(result.current.comments[0].replyCount).toBe(1);
  });
});
