import client from './client';

/**
 * 集成平台 API 客户端
 * 提供钉钉、企微、Slack、飞书等第三方集成的 API 调用
 */

// ============================================================
//  钉钉集成 API
// ============================================================

export interface DingTalkAuthUrlResponse {
  code: number;
  message: string;
  data: {
    authUrl: string;
  };
}

export interface DingTalkLoginResponse {
  code: number;
  message: string;
  data: {
    token: string;
    userId: string;
    username: string;
  };
}

export interface DingTalkSyncOrgResponse {
  code: number;
  message: string;
  data: {
    synced: boolean;
    departments: number;
    users: number;
  };
}

export interface DingTalkApprovalCallbackResponse {
  code: number;
  message: string;
  data: {
    processed: boolean;
  };
}

export const dingtalkApi = {
  /** 获取钉钉授权 URL */
  getAuthUrl: (): Promise<DingTalkAuthUrlResponse> =>
    client.post('/api/dingtalk/auth-url'),

  /** 钉钉登录回调 */
  login: (code: string): Promise<DingTalkLoginResponse> =>
    client.post('/api/dingtalk/login', { code }),

  /** 同步组织架构 */
  syncOrganization: (): Promise<DingTalkSyncOrgResponse> =>
    client.post('/api/dingtalk/sync-org'),

  /** OA 审批回调 */
  approvalCallback: (data: Record<string, unknown>): Promise<DingTalkApprovalCallbackResponse> =>
    client.post('/api/dingtalk/approval-callback', data),

  /** 获取钉钉用户信息 */
  getUserInfo: (accessToken: string): Promise<{ code: number; message: string; data: { nickname: string; userId: string } }> =>
    client.get('/api/dingtalk/user-info', { params: { accessToken } }),

  /** 钉钉退出登录 */
  logout: (): Promise<{ code: number; message: string; data: { loggedOut: boolean } }> =>
    client.post('/api/dingtalk/logout'),

  /** 查询审批实例状态 */
  getApprovalStatus: (instanceId: string): Promise<{ code: number; message: string; data: { instanceId: string; status: string } }> =>
    client.get('/api/dingtalk/approval-status', { params: { instanceId } }),

  /** 创建审批实例 */
  createApproval: (body: { processCode: string; title: string; formValues: Record<string, unknown> }): Promise<{ code: number; message: string; data: { instanceId: string } }> =>
    client.post('/api/dingtalk/approval', body),

  /** 获取用户映射列表 */
  getUserMappings: (tenantId?: string): Promise<{ code: number; message: string; data: { mappings: any[] } }> =>
    client.get('/api/dingtalk/user-mappings', { params: { tenantId } }),

  /** 获取同步状态 */
  getSyncStatus: (): Promise<{ code: number; message: string; data: { lastSyncTime: number; syncEnabled: boolean; cron: string } }> =>
    client.get('/api/dingtalk/sync-status'),
};

// ============================================================
//  集成市场 API
// ============================================================

export interface IntegrationMarketResponse {
  code: number;
  message: string;
  data: {
    integrations: Array<{
      id: string;
      name: string;
      description: string;
      icon: string;
      installed: boolean;
    }>;
  };
}

export const integrationApi = {
  /** 获取集成市场列表 */
  getMarket: (): Promise<IntegrationMarketResponse> =>
    client.get('/api/integrations/market'),

  /** 安装集成 */
  install: (id: string): Promise<{ code: number; message: string }> =>
    client.post(`/api/integrations/install/${id}`),

  /** 卸载集成 */
  uninstall: (id: string): Promise<{ code: number; message: string }> =>
    client.post(`/api/integrations/uninstall/${id}`),
};

// ============================================================
//  企业微信集成 API
// ============================================================

export interface WeComAuthUrlResponse {
  code: number;
  message: string;
  data: {
    authUrl: string;
  };
}

export interface WeComLoginResponse {
  code: number;
  message: string;
  data: {
    token: string;
    userId: string;
    username: string;
  };
}

export interface WeComSyncContactsResponse {
  code: number;
  message: string;
  data: {
    synced: boolean;
    users: number;
  };
}

export const wecomApi = {
  /** 获取企业微信授权 URL */
  getAuthUrl: (): Promise<WeComAuthUrlResponse> =>
    client.post('/api/wecom/auth-url'),

  /** 企业微信登录回调 */
  login: (code: string): Promise<WeComLoginResponse> =>
    client.post('/api/wecom/callback', { code }),

  /** 同步通讯录 */
  syncContacts: (): Promise<WeComSyncContactsResponse> =>
    client.post('/api/wecom/sync-contacts'),

  /** 发送消息 */
  sendMessage: (body: Record<string, unknown>): Promise<{ code: number; message: string }> =>
    client.post('/api/wecom/message/send', body),

  /** 获取配置状态 */
  getConfig: (): Promise<{ code: number; message: string; data: { configured: boolean } }> =>
    client.get('/api/wecom/config'),
};

// ============================================================
//  统一 API 入口
// ============================================================

export const integrationsApi = {
  dingtalk: dingtalkApi,
  wecom: wecomApi,
  integration: integrationApi,
};
