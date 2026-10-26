import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createComment,
  deleteComment,
  getRecordComments,
  updateComment,
  type CommentView,
  type CreateCommentRequest,
  type CommentsPage,
} from './api';

export type { CommentView, CreateCommentRequest, CommentsPage };

/** 评论缓存键：按集合+记录隔离，前缀便于跨记录失效 */
export const commentsKey = (collectionName: string, recordId: string) =>
  ['comments', collectionName, recordId] as const;

/** 后端统一信封 */
interface Envelope<T> {
  code: number;
  message: string;
  data: T;
}

/**
 * 从缓存值中取出评论页。
 *
 * <p>兼容两种形状：<br/>
 * 1) 测试 mock: { data: { comments, total } }（无 code/message）<br/>
 * 2) 真实信封：{ code, message, data: { comments, total } }<br/>
 * 3) 旧式扁平：{ comments, total }（不推荐，但保留兜底）
 */
function pageOf(cached: unknown): CommentsPage | undefined {
  if (!cached || typeof cached !== 'object') return undefined;
  const maybe = cached as Envelope<CommentsPage> & CommentsPage & { data?: CommentsPage };

  // 优先：data.comments 存在（信封或测试 mock）
  if (maybe.data && Array.isArray(maybe.data.comments)) return maybe.data;
  // 兜底：顶层 comments 数组
  if (Array.isArray(maybe.comments)) return maybe as CommentsPage;
  return undefined;
}

/** 把评论页写回缓存，保持与后端信封一致的形状 */
function withPage(cached: unknown, page: CommentsPage): Envelope<CommentsPage> {
  const base = (cached && typeof cached === 'object' ? cached : {}) as Partial<Envelope<CommentsPage>>;
  return {
    code: base.code ?? 0,
    message: base.message ?? 'success',
    data: page,
  };
}

interface UseCommentsParams {
  collectionName: string;
  recordId: string;
}

/**
 * 记录评论数据钩子 — 拉取、创建、编辑、删除（软删）。
 *
 * <p>创建/编辑/删除均采用乐观更新：先写入本地缓存，失败回滚快照。
 * 缓存键为 ['comments', collectionName, recordId]，切换记录自动重查。
 */
export function useComments({ collectionName, recordId }: UseCommentsParams) {
  const queryClient = useQueryClient();
  const key = commentsKey(collectionName, recordId);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: key,
    queryFn: () => getRecordComments(collectionName, recordId),
    enabled: !!collectionName && !!recordId,
  });

  /** 将新评论插入树：有 parentId 则挂到父节点 replies 尾部，否则追加顶层 */
  const insertIntoTree = (comments: CommentView[], incoming: CommentView): CommentView[] => {
    if (!incoming.parentId) return [...comments, incoming];
    return comments.map((c) =>
      c.id === incoming.parentId
        ? { ...c, replies: [...(c.replies ?? []), incoming], replyCount: c.replyCount + 1 }
        : c,
    );
  };

  /** 对树做递归 patch（编辑/软删共用） */
  const patchTree = (
    comments: CommentView[],
    targetId: string,
    patch: (c: CommentView) => CommentView,
  ): CommentView[] =>
    comments.map((c) =>
      c.id === targetId
        ? patch(c)
        : { ...c, replies: c.replies ? patchTree(c.replies, targetId, patch) : c.replies },
    );

  const createMutation = useMutation({
    mutationFn: (req: CreateCommentRequest) => createComment(collectionName, recordId, req),
    onMutate: async (req) => {
      await queryClient.cancelQueries({ queryKey: key });
      const snapshot = queryClient.getQueryData(key);
      const page = pageOf(snapshot) ?? { comments: [], total: 0 };

      // 乐观占位：临时 ID，成功后 invalidate 由服务端数据替换
      const optimistic: CommentView = {
        id: `optimistic-${Date.now()}`,
        collectionName,
        recordId,
        parentId: req.parentId,
        authorId: '',
        authorName: '',
        content: req.content,
        contentType: req.contentType,
        createdAt: new Date().toISOString(),
        replyCount: 0,
        replies: [],
      };
      queryClient.setQueryData(key, () =>
        withPage(snapshot, {
          comments: insertIntoTree(page.comments, optimistic),
          total: page.total + (req.parentId ? 0 : 1),
        }),
      );
      return { snapshot };
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.snapshot !== undefined) {
        queryClient.setQueryData(key, ctx.snapshot);
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: key });
    },
  });

  const updateMutation = useMutation({
    mutationFn: ({ commentId, req }: { commentId: string; req: CreateCommentRequest }) =>
      updateComment(collectionName, recordId, commentId, req),
    onMutate: async ({ commentId, req }) => {
      await queryClient.cancelQueries({ queryKey: key });
      const snapshot = queryClient.getQueryData(key);
      const page = pageOf(snapshot);
      if (page) {
        queryClient.setQueryData(key, () =>
          withPage(snapshot, {
            ...page,
            comments: patchTree(page.comments, commentId, (c) => ({
              ...c,
              content: req.content,
              editedAt: new Date().toISOString(),
            })),
          }),
        );
      }
      return { snapshot };
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.snapshot !== undefined) {
        queryClient.setQueryData(key, ctx.snapshot);
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: key });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (commentId: string) => deleteComment(collectionName, recordId, commentId),
    onMutate: async (commentId) => {
      await queryClient.cancelQueries({ queryKey: key });
      const snapshot = queryClient.getQueryData(key);
      const page = pageOf(snapshot);
      if (page) {
        // 软删乐观占位：内容清空 + deletedAt 打标（与后端行为一致）
        queryClient.setQueryData(key, () =>
          withPage(snapshot, {
            ...page,
            comments: patchTree(page.comments, commentId, (c) => ({
              ...c,
              content: '',
              deletedAt: new Date().toISOString(),
            })),
          }),
        );
      }
      return { snapshot };
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.snapshot !== undefined) {
        queryClient.setQueryData(key, ctx.snapshot);
      }
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: key });
    },
  });

  const page = pageOf(data);
  return {
    comments: page?.comments ?? [],
    total: page?.total ?? 0,
    isLoading,
    error,
    refetch,
    createComment: (req: CreateCommentRequest) => createMutation.mutateAsync(req),
    editComment: (commentId: string, req: CreateCommentRequest) =>
      updateMutation.mutateAsync({ commentId, req }),
    removeComment: (commentId: string) => deleteMutation.mutateAsync(commentId),
    isCreating: createMutation.isPending,
    isEditing: updateMutation.isPending,
    isDeleting: deleteMutation.isPending,
  };
}
