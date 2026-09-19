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

import {
  getJoinedChannels,
  getUnreadCount,
  uploadAttachment,
  searchCrossChannel,
  listSlashCommands,
} from './api';

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

/**
 * 断链契约测试:前端调用路径必须与后端 Controller 真实端点一致。
 *
 * 背景:uploadAttachment 曾指向不存在的 /im/messages/attachments,
 * searchCrossChannel / listSlashCommands 也曾无对应后端端点。
 * 单测 mock 掩盖了这类问题,故在此显式锁定路径。
 */
describe('IM API 与后端端点契约', () => {
  it('上传走真实端点 /attachments/upload', async () => {
    await uploadAttachment(new File(['x'], 'a.txt'));
    expect(post).toHaveBeenCalledWith(
      '/attachments/upload',
      expect.any(FormData),
      expect.objectContaining({ headers: expect.any(Object) }),
    );
  });

  it('跨频道搜索走 /im/messages/search/cross', async () => {
    await searchCrossChannel('预算');
    expect(get).toHaveBeenCalledWith(
      expect.stringContaining('/im/messages/search/cross'),
    );
  });

  it('Slash 命令列表走 /im/slash/commands', async () => {
    await listSlashCommands();
    expect(get).toHaveBeenCalledWith('/im/slash/commands');
  });
});
