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
  return client.post('/dingtalk/auth-url');
}

/**
 * 钉钉登录回调处理
 * 使用 code 换取用户信息和 JWT Token
 */
export function dingTalkLogin(code: string): Promise<{ token: string; user: DingTalkUser }> {
  return client.post('/dingtalk/login', { code });
}

/**
 * 同步钉钉组织架构
 * 拉取部门列表和用户列表并映射到本地
 */
export function syncDingTalkOrganization(): Promise<{
  departments: DingTalkDepartment[];
  users: DingTalkUser[];
}> {
  return client.post('/dingtalk/sync-org');
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
  return client.post('/dingtalk/approval-callback', data);
}

/**
 * 发送钉钉群消息
 * ⚠️ PHASE97 T5: 后端无 /api/dingtalk/group/message 端点，该函数已删除。
 * 恢复群消息功能时需先在后端 DingTalkController 补 @PostMapping("/group/message")。
 */

