import apiClient from '@/api/client';

/** 评论相关 API */
export interface CommentMention {
  displayName: string;
  userId: string;
}

export interface CommentView {
  id: string;
  collectionName: string;
  recordId: string;
  parentId?: string;
  authorId: string;
  authorName: string;
  content: string;
  contentType: string;
  mentions?: CommentMention[];
  attachmentId?: string;
  editedAt?: string;
  deletedAt?: string;
  createdAt: string;
  replies?: CommentView[];
  replyCount: number;
}

export interface CreateCommentRequest {
  content: string;
  contentType: string;
  parentId?: string;
  attachmentId?: string;
}

export interface CommentsPage {
  comments: CommentView[];
  total: number;
}

/**
 * 获取记录评论列表 - 游标分页
 * @param collectionName 集合名
 * @param recordId 记录 ID
 * @param beforeId 游标：返回该评论之前的记录
 * @param limit 单页条数
 */
export async function getRecordComments(
  collectionName: string,
  recordId: string,
  beforeId?: string,
  limit = 20,
) {
  const params = new URLSearchParams();
  params.set('limit', String(limit));
  if (beforeId) {
    params.set('beforeId', beforeId);
  }
  return apiClient.get<{ code: number; message: string; data: CommentsPage }>(
    `/collections/${collectionName}/records/${recordId}/comments?${params.toString()}`,
  );
}

/** 创建评论 */
export async function createComment(
  collectionName: string,
  recordId: string,
  request: CreateCommentRequest,
) {
  return apiClient.post<{ code: number; message: string; data: CommentView }>(
    `/collections/${collectionName}/records/${recordId}/comments`,
    request,
  );
}

/** 更新评论 */
export async function updateComment(
  collectionName: string,
  recordId: string,
  commentId: string,
  request: CreateCommentRequest,
) {
  return apiClient.put<{ code: number; message: string; data: CommentView }>(
    `/collections/${collectionName}/records/${recordId}/comments/${commentId}`,
    request,
  );
}

/** 删除评论（软删除） */
export async function deleteComment(
  collectionName: string,
  recordId: string,
  commentId: string,
) {
  return apiClient.delete<{ code: number; message: string; data: any }>(
    `/collections/${collectionName}/records/${recordId}/comments/${commentId}`,
  );
}
