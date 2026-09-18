import client from './client';

/**
 * 钉钉专用 API 客户端
 * 封装钉钉相关的 HTTP 请求
 */

export interface DingTalkUser {
  userId: string;
  unionid: string;
  username: string;
  departmentIds: string[];
  avatarUrl?: string;
  displayName?: string;
  roles?: string[];
  tenantId?: string;
}

export interface DingTalkDepartment {
  id: string;
  name: string;
  parentId: string;
  order: number;
}

export interface DingTalkAuthUrl {
  authUrl: string;
  expiresIn: number;
}

/**
 * 获取钉钉授权 URL
 * 用于钉钉内嵌 H5 免密登录
 */
export function getDingTalkAuthUrl(): Promise<DingTalkAuthUrl> {
  return client.post('/api/dingtalk/auth-url');
}

/**
 * 钉钉登录回调处理
 * 使用 code 换取用户信息和 JWT Token
 */
export function dingTalkLogin(code: string): Promise<{ token: string; user: DingTalkUser }> {
  return client.post('/api/dingtalk/login', { code });
}

/**
 * 同步钉钉组织架构
 * 拉取部门列表和用户列表并映射到本地
 */
export function syncDingTalkOrganization(): Promise<{
  departments: DingTalkDepartment[];
  users: DingTalkUser[];
}> {
  return client.post('/api/dingtalk/sync-org');
}

/**
 * 发送钉钉 OA 审批回调
 * 将工作流审批结果同步到钉钉 OA
 */
export function sendDingTalkApprovalCallback(data: {
  processInstanceId: string;
  approvalStatus: 'approved' | 'rejected';
  comment?: string;
}): Promise<{ processed: boolean }> {
  return client.post('/api/dingtalk/approval-callback', data);
}

/**
 * 发送钉钉群消息
 */
export function sendDingTalkGroupMessage(data: {
  groupId: string;
  content: string;
  type: 'text' | 'markdown' | 'actionCard';
}): Promise<{ sent: boolean }> {
  return client.post('/api/dingtalk/group/message', data);
}
