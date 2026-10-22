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

/** TS: apiClient.post 为泛型函数，mock 后需断言为 vi.fn 才能调 mockResolvedValue。 */
const mockedPost = apiClient.post as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.clearAllMocks();
});

describe('api/dingtalk', () => {
  it('getDingTalkAuthUrl POST /dingtalk/auth-url', async () => {
    mockedPost.mockResolvedValue({ authUrl: 'https://x', expiresIn: 3600 });
    await getDingTalkAuthUrl();
    expect(mockedPost).toHaveBeenCalledWith('/dingtalk/auth-url');
  });

  it('dingTalkLogin POST 带 code', async () => {
    mockedPost.mockResolvedValue({ token: 't', user: { userId: 'u1' } });
    await dingTalkLogin('code123');
    expect(mockedPost).toHaveBeenCalledWith('/dingtalk/login', { code: 'code123' });
  });

  it('syncDingTalkOrganization POST /dingtalk/sync-org', async () => {
    mockedPost.mockResolvedValue({ departments: [], users: [] });
    await syncDingTalkOrganization();
    expect(mockedPost).toHaveBeenCalledWith('/dingtalk/sync-org');
  });

  it('sendDingTalkApprovalCallback POST 带完整参数', async () => {
    mockedPost.mockResolvedValue({ processed: true });
    await sendDingTalkApprovalCallback({
      processInstanceId: 'inst1',
      approvalStatus: 'approved',
      comment: 'ok',
    });
    expect(mockedPost).toHaveBeenCalledWith('/dingtalk/approval-callback', {
      processInstanceId: 'inst1',
      approvalStatus: 'approved',
      comment: 'ok',
    });
  });
});
