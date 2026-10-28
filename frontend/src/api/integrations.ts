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
    client.get('/dingtalk/auth-url'),

  /** 钉钉登录回调 */
  login: (code: string): Promise<DingTalkLoginResponse> =>
    client.post('/dingtalk/login', { code }),

  /** 同步组织架构 */
  syncOrganization: (): Promise<DingTalkSyncOrgResponse> =>
    client.post('/dingtalk/sync-org'),

  /** OA 审批回调 */
  approvalCallback: (data: Record<string, unknown>): Promise<DingTalkApprovalCallbackResponse> =>
    client.post('/dingtalk/approval-callback', data),

  /** 获取钉钉用户信息 */
  getUserInfo: (accessToken: string): Promise<{ code: number; message: string; data: { nickname: string; userId: string } }> =>
    client.get('/dingtalk/user-info', { params: { accessToken } }),

  /** 钉钉退出登录 */
  logout: (): Promise<{ code: number; message: string; data: { loggedOut: boolean } }> =>
    client.post('/dingtalk/logout'),

  /** 查询审批实例状态 */
  getApprovalStatus: (instanceId: string): Promise<{ code: number; message: string; data: { instanceId: string; status: string } }> =>
    client.get('/dingtalk/approval-status', { params: { instanceId } }),

  /** 创建审批实例 */
  createApproval: (body: { processCode: string; title: string; formValues: Record<string, unknown> }): Promise<{ code: number; message: string; data: { instanceId: string } }> =>
    client.post('/dingtalk/approval', body),

  /** 获取用户映射列表 */
  getUserMappings: (tenantId?: string): Promise<{ code: number; message: string; data: { mappings: any[] } }> =>
    client.get('/dingtalk/user-mappings', { params: { tenantId } }),

  /** 获取同步状态 */
  getSyncStatus: (): Promise<{ code: number; message: string; data: { lastSyncTime: number; syncEnabled: boolean; cron: string } }> =>
    client.get('/dingtalk/sync-status'),

  /**
   * 投递钉钉 AI 卡片（B4）。
   *
   * 后端 `POST /api/dingtalk/ai-card/send`：生成 AI 摘要 → 投递互动卡片，
   * 卡片按钮回调会触发工作流任务同意/拒绝。
   * 修复前前端全仓无 ai-card 调用入口（B4 假完成的一部分）。
   */
  sendAiCard: (body: {
    title: string;
    content: string;
    targetUserId: string;
    openUrl?: string;
    workflowTaskId?: string;
    workflowAction?: 'APPROVE' | 'REJECT';
  }): Promise<{ code: number; message: string; data: { outTrackId: string } }> =>
    client.post('/dingtalk/ai-card/send', body),
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
      kind: string;
      installed: boolean;
    }>;
  };
}

export const integrationApi = {
  /** 获取集成市场列表 */
  getMarket: (): Promise<IntegrationMarketResponse> =>
    client.get('/integrations/market'),

  /** 安装集成 */
  install: (id: string): Promise<{ code: number; message: string }> =>
    client.post(`/integrations/install/${id}`),

  /** 卸载集成 */
  uninstall: (id: string): Promise<{ code: number; message: string }> =>
    client.post(`/integrations/uninstall/${id}`),
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
    client.get('/wecom/auth-url'),

  /** 企业微信登录回调 */
  login: (code: string): Promise<WeComLoginResponse> =>
    client.post('/wecom/callback', { code }),

  /** 同步通讯录 */
  syncContacts: (): Promise<WeComSyncContactsResponse> =>
    client.post('/wecom/sync-contacts'),

  /** 发送消息 */
  sendMessage: (body: Record<string, unknown>): Promise<{ code: number; message: string }> =>
    client.post('/wecom/message/send', body),
};

// ============================================================
//  微信公众号 / 小程序集成 API
// ============================================================

export interface WeChatAuthUrlResponse {
  code: number;
  message: string;
  data: {
    authUrl: string;
  };
}

export interface WeChatLoginResponse {
  code: number;
  message: string;
  data: {
    userId: string;
    username: string;
    displayName: string;
    accessToken: string;
    refreshToken: string;
  };
}

export const wechatApi = {
  /** 获取微信公众号授权 URL */
  getAuthUrl: (): Promise<WeChatAuthUrlResponse> =>
    client.get('/wechat/auth-url'),

  /** 公众号 OAuth 回调登录 */
  login: (code: string, state?: string): Promise<WeChatLoginResponse> =>
    client.post('/wechat/callback', { code, state }),

  /** 小程序 code2Session 登录 */
  miniProgramLogin: (jsCode: string): Promise<WeChatLoginResponse> =>
    client.post('/wechat/mini-program/login', { jsCode }),
};

// ============================================================
//  平台通用 API（B1 微信 JS-SDK / 企微 JS-SDK 统一入口）
// ============================================================

export interface JsSignatureResponse {
  code: number;
  message: string;
  data: {
    platform: string;
    signType: string;
    signature: string;
    timestamp: number;
    nonceStr: string;
    jsapiTicket: string;
    url: string;
  };
}

/**
 * 获取 JS-SDK 签名（B1）。
 *
 * <p>B1 返工：统一使用 {@code platform} 参数（wechat/wecom），后端同时支持
 * {@code provider} 作为别名。修复前前端传 {@code provider}、后端读 {@code platform}，
 * 参数对不上导致签名接口 400/404。
 */
export const jsSdkApi = {
  getSignature: (platform: string, url: string, provider?: string): Promise<JsSignatureResponse> =>
    client.get('/js-signature', {
      params: { platform, url, ...(provider ? { provider } : {}) },
    }),
};

// ============================================================
//  统一 API 入口
// ============================================================

export const integrationsApi = {
  dingtalk: dingtalkApi,
  wecom: wecomApi,
  wechat: wechatApi,
  integration: integrationApi,
  jsSdk: jsSdkApi,
};
