/**
 * PHASE 4.2 覆盖率补测：features/im/api.ts
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

import apiClient from '@/api/client';

/** TS: apiClient.post 为泛型函数，mock 后需断言为 vi.fn 才能调 mockResolvedValue。 */
const mockedPost = apiClient.post as unknown as ReturnType<typeof vi.fn>;
/** Wave0.5 A-3: listPins 信封解包测试需单独控制 get 的返回 */
const mockedGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;

import {
  getJoinedChannels,
  createChannel,
  getChannelDetail,
  joinChannel,
  leaveChannel,
  setPresenceHeartbeat,
  getChannelMessages,
  sendMessage,
  editMessage,
  deleteMessage,
  getMessageThread,
  sendReply,
  searchMessages,
  getUnreadCount,
  markMessagesRead,
  addReaction,
  removeReaction,
  getReactions,
  listPins,
  pinMessage,
  unpinMessage,
  searchCrossChannel,
  uploadAttachment,
  listSlashCommands,
  markBurnRead,
} from './api';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('频道 API', () => {
  it('getJoinedChannels / getChannelDetail', async () => {
    await getJoinedChannels();
    expect(apiClient.get).toHaveBeenCalledWith('/im/channels');

    await getChannelDetail('ch1');
    expect(apiClient.get).toHaveBeenCalledWith('/im/channels/ch1');
  });

  it('createChannel / joinChannel / leaveChannel', async () => {
    await createChannel({ name: 'C', type: 'PUBLIC' });
    expect(apiClient.post).toHaveBeenCalledWith('/im/channels', { name: 'C', type: 'PUBLIC' });

    await joinChannel('ch1', 'u1', 'MEMBER');
    expect(apiClient.post).toHaveBeenCalledWith('/im/channels/ch1/members', { userId: 'u1', role: 'MEMBER' });

    await leaveChannel('ch1');
    expect(apiClient.delete).toHaveBeenCalledWith('/im/channels/ch1/members/me');
  });

  it('setPresenceHeartbeat', async () => {
    await setPresenceHeartbeat();
    expect(apiClient.post).toHaveBeenCalledWith('/im/channels/presence/heartbeat');
  });
});

describe('消息 API', () => {
  it('getChannelMessages 支持 cursor 分页', async () => {
    await getChannelMessages('ch1', 'cursor123', 30);
    expect(apiClient.get).toHaveBeenCalledWith('/im/messages?limit=30&channelId=ch1&cursor=cursor123');
  });

  it('sendMessage / editMessage / deleteMessage', async () => {
    await sendMessage('ch1', 'Hello', 'TEXT');
    expect(apiClient.post).toHaveBeenCalledWith('/im/messages', {
      channelId: 'ch1',
      content: 'Hello',
      contentType: 'TEXT',
    });

    await editMessage('msg1', 'Edited');
    expect(apiClient.put).toHaveBeenCalledWith('/im/messages/msg1', { content: 'Edited' });

    await deleteMessage('msg1');
    expect(apiClient.delete).toHaveBeenCalledWith('/im/messages/msg1');
  });

  it('getMessageThread / sendReply', async () => {
    await getMessageThread('ch1', 'msg1');
    expect(apiClient.get).toHaveBeenCalledWith('/im/messages/msg1/thread');

    await sendReply('ch1', 'msg1', 'Reply', 'TEXT');
    expect(apiClient.post).toHaveBeenCalledWith('/im/messages', {
      channelId: 'ch1',
      parentId: 'msg1',
      content: 'Reply',
      contentType: 'TEXT',
    });
  });

  it('searchMessages / getUnreadCount / markMessagesRead', async () => {
    await searchMessages('ch1', 'keyword');
    expect(apiClient.get).toHaveBeenCalledWith('/im/messages/search?keyword=keyword&limit=50&channelId=ch1');

    await getUnreadCount('ch1');
    expect(apiClient.get).toHaveBeenCalledWith('/im/messages/unread?channelId=ch1');

    await markMessagesRead('ch1', 'lastMsgId');
    expect(apiClient.post).toHaveBeenCalledWith('/im/messages/read', {
      channelId: 'ch1',
      lastMessageId: 'lastMsgId',
    });
  });
});

describe('表情与置顶', () => {
  it('addReaction / removeReaction / getReactions', async () => {
    await addReaction('msg1', '👍');
    expect(apiClient.post).toHaveBeenCalledWith('/im/messages/msg1/reactions', { emoji: '👍' });

    await removeReaction('msg1', '👍');
    expect(apiClient.delete).toHaveBeenCalledWith('/im/messages/msg1/reactions?emoji=%F0%9F%91%8D');

    await getReactions('msg1');
    expect(apiClient.get).toHaveBeenCalledWith('/im/messages/msg1/reactions');
  });

  it('listPins / pinMessage / unpinMessage', async () => {
    await listPins('ch1');
    expect(apiClient.get).toHaveBeenCalledWith('/im/messages/pins?channelId=ch1');

    // Wave0.5 A-3: 端点对齐后端（POST/DELETE /im/messages/pins，body 带 channelId + messageId）
    await pinMessage('ch1', 'msg1');
    expect(apiClient.post).toHaveBeenCalledWith('/im/messages/pins', {
      channelId: 'ch1',
      messageId: 'msg1',
    });

    await unpinMessage('ch1', 'msg1');
    expect(apiClient.delete).toHaveBeenCalledWith('/im/messages/pins', {
      data: { channelId: 'ch1', messageId: 'msg1' },
    });
  });

  it('listPins 解包信封 data.pins 为数组（Wave0.5 A-3）', async () => {
    mockedGet.mockResolvedValueOnce({ code: 0, data: { pins: [{ id: 'p1', messageId: 'm1' }] } });
    const res = await listPins('ch1');
    expect(res.data).toEqual([{ id: 'p1', messageId: 'm1' }]);
  });

  it('listPins 在后端未返回 pins 时回落空数组（Wave0.5 A-3）', async () => {
    mockedGet.mockResolvedValueOnce({ code: 0, data: {} });
    const res = await listPins('ch1');
    expect(res.data).toEqual([]);
  });
});

describe('高级功能', () => {
  it('searchCrossChannel', async () => {
    await searchCrossChannel('query', 30);
    expect(apiClient.get).toHaveBeenCalledWith('/im/messages/search/cross?keyword=query&limit=30');
  });

  it('uploadAttachment 构造 FormData', async () => {
    const file = new File(['content'], 'test.txt', { type: 'text/plain' });
    mockedPost.mockResolvedValue({
      code: 0,
      message: 'ok',
      data: { storageKey: 'key1', originalName: 'test.txt', contentType: 'text/plain', size: 100 },
    });
    const out = await uploadAttachment(file);
    expect(apiClient.post).toHaveBeenCalledWith('/attachments/upload', expect.any(FormData), {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    expect(out.data.url).toBe('/api/attachments/key1/download');
    expect(out.data.filename).toBe('test.txt');
  });

  it('listSlashCommands / markBurnRead', async () => {
    await listSlashCommands();
    expect(apiClient.get).toHaveBeenCalledWith('/im/slash/commands');

    await markBurnRead('msg1');
    expect(apiClient.post).toHaveBeenCalledWith('/im/messages/msg1/burn/read');
  });
});
