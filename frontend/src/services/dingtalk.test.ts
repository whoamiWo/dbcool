/**
 * PHASE 4.2 覆盖率补测：services/dingtalk.ts
 * 服务层：配置存储、SSO 跳转、回调写入 auth store、组织架构同步、环境判断。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('@/api/dingtalk', () => ({
  getDingTalkAuthUrl: vi.fn(),
  dingTalkLogin: vi.fn(),
  syncDingTalkOrganization: vi.fn(),
}));
vi.mock('@/api/integrations', () => ({
  dingtalkApi: { login: vi.fn() },
}));
vi.mock('@/stores/auth', () => {
  const setAuth = vi.fn();
  const switchTenant = vi.fn();
  return {
    useAuthStore: {
      getState: () => ({ setAuth, switchTenant, user: { tenant_id: 'tenant_default' } }),
    },
  };
});

import {
  initDingTalk,
  handleDingTalkLogin,
  handleDingTalkCallback,
  handleSyncOrganization,
  isInDingTalk,
  getDingTalkUserInfo,
} from './dingtalk';
import { getDingTalkAuthUrl, dingTalkLogin, syncDingTalkOrganization } from '@/api/dingtalk';
import { dingtalkApi } from '@/api/integrations';
import { useAuthStore } from '@/stores/auth';

const mockedAuthUrl = getDingTalkAuthUrl as unknown as ReturnType<typeof vi.fn>;
const mockedLogin = dingTalkLogin as unknown as ReturnType<typeof vi.fn>;
const mockedSync = syncDingTalkOrganization as unknown as ReturnType<typeof vi.fn>;
const mockedApiLogin = dingtalkApi.login as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
});

describe('initDingTalk', () => {
  it('把配置写入 sessionStorage', () => {
    initDingTalk({ appKey: 'k', appSecret: 's', redirectUri: 'http://x' });
    const raw = sessionStorage.getItem('dingtalk_config');
    expect(raw).toBeTruthy();
    expect(JSON.parse(raw!)).toEqual({ appKey: 'k', appSecret: 's', redirectUri: 'http://x' });
  });
});

describe('handleDingTalkLogin', () => {
  it('拿到 authUrl 后跳转', async () => {
    mockedAuthUrl.mockResolvedValue({ authUrl: 'https://sso' });
    delete (window as { location?: Location }).location;
    (window as { location: unknown }).location = { href: '', search: '' };
    await handleDingTalkLogin();
    expect((window.location as unknown as { href: string }).href).toBe('https://sso');
  });

  it('无 authUrl 时不跳转', async () => {
    mockedAuthUrl.mockResolvedValue({});
    (window.location as unknown as { href: string }).href = '';
    await handleDingTalkLogin();
    expect((window.location as unknown as { href: string }).href).toBe('');
  });

  it('失败时透传错误', async () => {
    mockedAuthUrl.mockRejectedValue(new Error('boom'));
    await expect(handleDingTalkLogin()).rejects.toThrow('boom');
  });
});

describe('handleDingTalkCallback', () => {
  it('登录后写入 auth store', async () => {
    mockedLogin.mockResolvedValue({
      token: 't',
      refreshToken: 'r',
      user: { userId: 'u1', username: 'alice', tenantId: 'tenant_default', roles: ['admin'] },
    });
    await handleDingTalkCallback('code123');
    expect(mockedLogin).toHaveBeenCalledWith('code123');
    expect(useAuthStore.getState().setAuth).toHaveBeenCalledWith('t', 'r', {
      id: 'u1',
      username: 'alice',
      tenant_id: 'tenant_default',
      roles: ['admin'],
    });
  });

  it('字段缺失时回落为空值', async () => {
    mockedLogin.mockResolvedValue({});
    await handleDingTalkCallback('c');
    expect(useAuthStore.getState().setAuth).toHaveBeenCalledWith('', '', {
      id: '',
      username: '',
      tenant_id: '',
      roles: [],
    });
  });

  it('失败时抛错', async () => {
    mockedLogin.mockRejectedValue(new Error('login fail'));
    await expect(handleDingTalkCallback('c')).rejects.toThrow('login fail');
  });
});

describe('handleSyncOrganization', () => {
  it('同步成功后切换租户', async () => {
    mockedSync.mockResolvedValue({});
    await handleSyncOrganization();
    expect(mockedSync).toHaveBeenCalled();
    expect(useAuthStore.getState().switchTenant).toHaveBeenCalledWith('tenant_default');
  });

  it('失败时抛错', async () => {
    mockedSync.mockRejectedValue(new Error('sync fail'));
    await expect(handleSyncOrganization()).rejects.toThrow('sync fail');
  });
});

describe('isInDingTalk', () => {
  it('UA 含 dingtalk 返回 true', () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Mozilla DingTalk/1.0');
    expect(isInDingTalk()).toBe(true);
  });

  it('普通浏览器返回 false', () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Mozilla Chrome/120');
    expect(isInDingTalk()).toBe(false);
  });
});

describe('getDingTalkUserInfo', () => {
  it('非钉钉环境返回 null', async () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Chrome');
    await expect(getDingTalkUserInfo()).resolves.toBeNull();
  });

  it('钉钉环境返回用户信息', async () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('DingTalk');
    mockedApiLogin.mockResolvedValue({ userId: 'u9', username: 'bob' });
    await expect(getDingTalkUserInfo()).resolves.toEqual({ userId: 'u9', username: 'bob' });
  });

  it('接口异常返回 null', async () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('DingTalk');
    mockedApiLogin.mockRejectedValue(new Error('x'));
    await expect(getDingTalkUserInfo()).resolves.toBeNull();
  });
});
