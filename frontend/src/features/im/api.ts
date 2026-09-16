import apiClient from '@/api/client';

/** 频道相关 API */
export interface ImChannel {
  id: string;
  name: string;
  type: 'PUBLIC' | 'PRIVATE';
  topic: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  archived: boolean;
}

/** 获取我加入的频道列表 */
export async function getJoinedChannels() {
  return apiClient.get<{ code: number; data: ImChannel[] }>('/im/channels');
}

/** 创建频道 */
export async function createChannel(channelData: {
  name: string;
  type: 'PUBLIC' | 'PRIVATE';
  topic?: string;
  memberIds?: string[];
}) {
  return apiClient.post<{ code: number; data: ImChannel }>('/im/channels', channelData);
}

/** 获取指定频道详情 */
export async function getChannelDetail(channelId: string) {
  return apiClient.get<{ code: number; data: ImChannel }>(`/im/channels/${channelId}`);
}

/** 加入/邀请成员到频道 */
export async function joinChannel(channelId: string, userId: string, role: 'MEMBER' | 'ADMIN') {
  return apiClient.post<{ code: number; data: any }>(`/im/channels/${channelId}/members`, { userId, role });
}

/** 退出频道 */
export async function leaveChannel(channelId: string) {
  return apiClient.delete<{ code: number; data: any }>(`/im/channels/${channelId}/members/me`);
}

/** 设置在线心跳（后端端点无需参数） */
export async function setPresenceHeartbeat() {
  return apiClient.post<{ code: number; data: any }>(`/im/channels/presence/heartbeat`);
}

/** 消息相关 API */
export interface ImMessage {
  id: string;
  channelId: string;
  senderId: string;
  parentId?: string;
  content: string;
  contentType: string;
  createdAt: string;
  editedAt?: string;
  deletedAt?: string;
}

/** 消息分页响应 */
export interface MessagePage {
  messages: ImMessage[];
  limit: number;
  next_cursor: string;
  has_more: boolean;
}

/** 获取频道消息 - cursor 分页 */
export async function getChannelMessages(
  channelId: string,
  cursor?: string,
  limit = 50,
) {
  const params = new URLSearchParams();
  params.set('limit', String(limit));
  params.set('channelId', channelId);
  if (cursor) {
    params.set('cursor', cursor);
  }
  return apiClient.get<{ code: number; data: MessagePage }>(`/im/messages?${params.toString()}`);
}

/** 发送消息 */
export async function sendMessage(channelId: string, content: string, contentType: 'TEXT' | 'EMOTION' | 'FILE') {
  return apiClient.post<{ code: number; data: ImMessage }>('/im/messages', {
    channelId,
    content,
    contentType,
  });
}

/** 编辑消息 */
export async function editMessage(messageId: string, content: string) {
  return apiClient.put<{ code: number; data: ImMessage }>(`/im/messages/${messageId}`, { content });
}

/** 删除消息(软删除) */
export async function deleteMessage(messageId: string) {
  return apiClient.delete<{ code: number; data: any }>(`/im/messages/${messageId}`);
}

/** 获取线程回复列表 */
export async function getMessageThread(_channelId: string, messageId: string) {
  return apiClient.get<{ code: number; data: { replies: ImMessage[] } }>(`/im/messages/${messageId}/thread`);
}

/** 发送线程回复（parentId 指向父消息） */
export async function sendReply(
  channelId: string,
  parentId: string,
  content: string,
  contentType: 'TEXT' | 'EMOTION' | 'FILE' = 'TEXT',
) {
  return apiClient.post<{ code: number; data: ImMessage }>('/im/messages', {
    channelId,
    parentId,
    content,
    contentType,
  });
}

/** 搜索消息 */
export async function searchMessages(
  channelId: string,
  keyword: string,
  limit = 50,
) {
  const params = new URLSearchParams();
  params.set('keyword', keyword);
  params.set('limit', String(limit));
  return apiClient.get<{ code: number; data: MessagePage }>(`/im/messages/search?${params.toString()}&channelId=${channelId}`);
}

/** 获取未读数 */
export async function getUnreadCount(channelId: string) {
  return apiClient.get<{ code: number; data: { channelId: string; unread_count: number } }>(`/im/messages/unread?channelId=${channelId}`);
}

/** 标记已读 */
export async function markMessagesRead(channelId: string, lastMessageId: string) {
  return apiClient.post<{ code: number; data: any }>('/im/messages/read', {
    channelId,
    lastMessageId,
  });
}

/** 表情相关 API */
export interface MessageReaction {
  id: string;
  messageId: string;
  emoji: string;
  userId: string;
  createdAt: string;
}

/** 添加表情 */
export async function addReaction(messageId: string, emoji: string) {
  return apiClient.post<{ code: number; data: MessageReaction }>(`/im/messages/${messageId}/reactions`, { emoji });
}

/** 删除表情 */
export async function removeReaction(messageId: string, emoji: string) {
  return apiClient.delete<{ code: number; data: any }>(`/im/messages/${messageId}/reactions?emoji=${encodeURIComponent(emoji)}`);
}

/** 获取表情列表 */
export async function getReactions(messageId: string) {
  return apiClient.get<{ code: number; data: MessageReaction[] }>(`/im/messages/${messageId}/reactions`);
}