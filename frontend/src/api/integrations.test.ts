/**
 * PHASE 4.2 覆盖率补测：api/integrations.ts
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('./client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

import apiClient from './client';
import { dingtalkApi, integrationApi, wecomApi, integrationsApi } from './integrations';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('dingtalkApi', () => {
  it('getAuthUrl / login / syncOrganization', async () => {
    await dingtalkApi.getAuthUrl();
    expect(apiClient.get).toHaveBeenCalledWith('/dingtalk/auth-url');

    await dingtalkApi.login('code1');
    expect(apiClient.post).toHaveBeenCalledWith('/dingtalk/login', { code: 'code1' });

    await dingtalkApi.syncOrganization();
    expect(apiClient.post).toHaveBeenCalledWith('/dingtalk/sync-org');
  });

  it('approvalCallback / getUserInfo / logout', async () => {
    await dingtalkApi.approvalCallback({ processInstanceId: 'p1' });
    expect(apiClient.post).toHaveBeenCalledWith('/dingtalk/approval-callback', { processInstanceId: 'p1' });

    await dingtalkApi.getUserInfo('token');
    expect(apiClient.get).toHaveBeenCalledWith('/dingtalk/user-info', { params: { accessToken: 'token' } });

    await dingtalkApi.logout();
    expect(apiClient.post).toHaveBeenCalledWith('/dingtalk/logout');
  });

  it('getApprovalStatus / createApproval', async () => {
    await dingtalkApi.getApprovalStatus('inst1');
    expect(apiClient.get).toHaveBeenCalledWith('/dingtalk/approval-status', {
      params: { instanceId: 'inst1' },
    });

    await dingtalkApi.createApproval({ processCode: 'pc', title: 'T', formValues: {} });
    expect(apiClient.post).toHaveBeenCalledWith('/dingtalk/approval', {
      processCode: 'pc',
      title: 'T',
      formValues: {},
    });
  });

  it('sendAiCard 投递钉钉 AI 卡片（B4 前端调用入口）', async () => {
    await dingtalkApi.sendAiCard({
      title: '审批提醒',
      content: '有一笔报销待审批',
      targetUserId: 'user1',
      workflowTaskId: 'task-1',
      workflowAction: 'APPROVE',
    });
    expect(apiClient.post).toHaveBeenCalledWith('/dingtalk/ai-card/send', {
      title: '审批提醒',
      content: '有一笔报销待审批',
      targetUserId: 'user1',
      workflowTaskId: 'task-1',
      workflowAction: 'APPROVE',
    });
  });

  it('getUserMappings / getSyncStatus', async () => {
    await dingtalkApi.getUserMappings('t1');
    expect(apiClient.get).toHaveBeenCalledWith('/dingtalk/user-mappings', { params: { tenantId: 't1' } });

    await dingtalkApi.getSyncStatus();
    expect(apiClient.get).toHaveBeenCalledWith('/dingtalk/sync-status');
  });
});

describe('integrationApi', () => {
  it('getMarket / install / uninstall', async () => {
    await integrationApi.getMarket();
    expect(apiClient.get).toHaveBeenCalledWith('/integrations/market');

    await integrationApi.install('int1');
    expect(apiClient.post).toHaveBeenCalledWith('/integrations/install/int1');

    await integrationApi.uninstall('int1');
    expect(apiClient.post).toHaveBeenCalledWith('/integrations/uninstall/int1');
  });
});

describe('wecomApi', () => {
  it('getAuthUrl / login / syncContacts / sendMessage', async () => {
    await wecomApi.getAuthUrl();
    expect(apiClient.get).toHaveBeenCalledWith('/wecom/auth-url');

    await wecomApi.login('code');
    expect(apiClient.post).toHaveBeenCalledWith('/wecom/callback', { code: 'code' });

    await wecomApi.syncContacts();
    expect(apiClient.post).toHaveBeenCalledWith('/wecom/sync-contacts');

    await wecomApi.sendMessage({ toUser: 'u1' });
    expect(apiClient.post).toHaveBeenCalledWith('/wecom/message/send', { toUser: 'u1' });
  });
});

describe('integrationsApi 统一入口', () => {
  it('聚合三个子 API', () => {
    expect(integrationsApi.dingtalk).toBe(dingtalkApi);
    expect(integrationsApi.wecom).toBe(wecomApi);
    expect(integrationsApi.integration).toBe(integrationApi);
  });

  // B1 返工：JS-SDK 签名统一使用 platform 参数
  it('jsSdk.getSignature 使用 platform 参数（B1 参数对齐）', async () => {
    await integrationsApi.jsSdk.getSignature('wechat', 'https://app.example.com/page');
    expect(apiClient.get).toHaveBeenCalledWith('/js-signature', {
      params: { platform: 'wechat', url: 'https://app.example.com/page' },
    });
  });

  it('jsSdk.getSignature 兼容 provider 别名', async () => {
    await integrationsApi.jsSdk.getSignature('wecom', 'https://app.example.com/x', 'wecom');
    expect(apiClient.get).toHaveBeenCalledWith('/js-signature', {
      params: { platform: 'wecom', url: 'https://app.example.com/x', provider: 'wecom' },
    });
  });
});
