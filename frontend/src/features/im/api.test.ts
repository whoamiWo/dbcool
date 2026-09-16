import { describe, expect, it, vi } from 'vitest';

/**
 * 路径正确性回归:防止 /api 双重前缀复发。
 *
 * 背景:client.ts 的 baseURL 已是 '/api',此处若再写 '/api/im/...'
 * 会拼成 /api/api/im/... 导致全部 404(曾经的阻断缺陷)。
 */

const get = vi.fn().mockResolvedValue({ code: 0, data: [] });
const post = vi.fn().mockResolvedValue({ code: 0, data: {} });

vi.mock('@/api/client', () => ({
  default: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
  },
}));

import { getJoinedChannels, getUnreadCount } from './api';

describe('IM API 路径', () => {
  it('频道列表路径不带 /api 前缀', async () => {
    await getJoinedChannels();
    expect(get).toHaveBeenCalledWith('/im/channels');
  });

  it('未读路径不带 /api 前缀', async () => {
    await getUnreadCount('c1');
    expect(get).toHaveBeenCalledWith('/im/messages/unread?channelId=c1');
  });

  it('所有调用路径均不应出现 /api/api', async () => {
    await getJoinedChannels();
    await getUnreadCount('c1');
    for (const call of [...get.mock.calls, ...post.mock.calls]) {
      expect(String(call[0])).not.toContain('/api/');
    }
  });
});
