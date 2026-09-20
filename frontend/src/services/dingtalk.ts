import { dingtalkApi } from '@/api/integrations';
import { getDingTalkAuthUrl, dingTalkLogin, syncDingTalkOrganization } from '@/api/dingtalk';
import { useAuthStore } from '@/stores/auth';

/**
 * 钉钉服务层
 * 封装钉钉相关的业务逻辑，包括 SSO 登录、组织架构同步等
 */

interface DingTalkConfig {
  appKey: string;
  appSecret: string;
  redirectUri: string;
}

/**
 * 初始化钉钉配置
 */
export function initDingTalk(config: DingTalkConfig): void {
  // 存储钉钉配置到 sessionStorage
  sessionStorage.setItem('dingtalk_config', JSON.stringify(config));
}

/**
 * 获取钉钉登录 URL 并重定向
 */
export async function handleDingTalkLogin(): Promise<void> {
  try {
    const result = await getDingTalkAuthUrl();
    if (result.authUrl) {
      window.location.href = result.authUrl;
    }
  } catch (error) {
    console.error('获取钉钉授权链接失败:', error);
    throw error;
  }
}

/**
 * 处理钉钉登录回调
 * @param code 钉钉回调携带的 code
 */
export async function handleDingTalkCallback(code: string): Promise<void> {
  try {
    const result = await dingTalkLogin(code);
    const res = result as unknown as {
      token?: string;
      refreshToken?: string;
      user?: { id?: string; userId?: string; username?: string; tenantId?: string; roles?: string[] };
    };
    useAuthStore.getState().setAuth(
      res.token || '',
      res.refreshToken || '',
      {
        id: res.user?.id || res.user?.userId || '',
        username: res.user?.username || '',
        tenant_id: res.user?.tenantId || '',
        roles: res.user?.roles || [],
      },
    );
  } catch (error) {
    console.error('钉钉登录回调处理失败:', error);
    throw error;
  }
}

/**
 * 同步钉钉组织架构到本地
 */
export async function handleSyncOrganization(): Promise<void> {
  try {
    await syncDingTalkOrganization();
    const authStore = useAuthStore.getState();
    // 同步组织数据到 user 实体(通过 setAuth 更新 tenant_id)
    if (authStore.user) {
      useAuthStore.getState().switchTenant(authStore.user.tenant_id);
    }
  } catch (error) {
    console.error('组织架构同步失败:', error);
    throw error;
  }
}

/**
 * 检查是否在钉钉环境中
 */
export function isInDingTalk(): boolean {
  const userAgent = navigator.userAgent.toLowerCase();
  return userAgent.includes('dingtalk');
}

/**
 * 获取钉钉用户信息（如果已在钉钉环境中）
 */
export async function getDingTalkUserInfo(): Promise<{ userId: string; username: string } | null> {
  if (!isInDingTalk()) return null;
  try {
    const result = await dingtalkApi.login(window.location.search.replace('?code=', ''));
    const res = result as unknown as { userId?: string; username?: string };
    return { userId: res.userId || '', username: res.username || '' };
  } catch {
    return null;
  }
}
