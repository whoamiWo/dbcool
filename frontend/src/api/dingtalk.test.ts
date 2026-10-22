/**
 * PHASE 4.2 覆盖率补测：api/dingtalk.ts
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('./client', () => ({
  default: {
    post: vi.fn(),
  },
}));

import apiClient from './client';
import {
  getDingTalkAuthUrl,
  dingTalkLogin,
  syncDingTalkOrganization,
  sendDingTalkApprovalCallback,
} from './dingtalk';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('api/dingtalk', () => {
  it('getDingTalkAuthUrl POST /dingtalk/auth-url', async () => {
    apiClient.post.mockResolvedValue({ authUrl: 'https://x', expiresIn: 3600 });
    await getDingTalkAuthUrl();
    expect(apiClient.post).toHaveBeenCalledWith('/dingtalk/auth-url');
  });

  it('dingTalkLogin POST 带 code', async () => {
    apiClient.post.mockResolvedValue({ token: 't', user: { userId: 'u1' } });
    await dingTalkLogin('code123');
    expect(apiClient.post).toHaveBeenCalledWith('/dingtalk/login', { code: 'code123' });
  });

  it('syncDingTalkOrganization POST /dingtalk/sync-org', async () => {
    apiClient.post.mockResolvedValue({ departments: [], users: [] });
    await syncDingTalkOrganization();
    expect(apiClient.post).toHaveBeenCalledWith('/dingtalk/sync-org');
  });

  it('sendDingTalkApprovalCallback POST 带完整参数', async () => {
    apiClient.post.mockResolvedValue({ processed: true });
    await sendDingTalkApprovalCallback({
      processInstanceId: 'inst1',
      approvalStatus: 'approved',
      comment: 'ok',
    });
    expect(apiClient.post).toHaveBeenCalledWith('/dingtalk/approval-callback', {
      processInstanceId: 'inst1',
      approvalStatus: 'approved',
      comment: 'ok',
    });
  });
});
