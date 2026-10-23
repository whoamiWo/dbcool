/**
 * PHASE 4.2 覆盖率补测：api/client.ts
 * 真正覆盖 axios 拦截器链路：
 *  - 请求拦截器注入 Authorization、错误分支
 *  - 响应拦截器解包 data、非 401 透传
 *  - 401 → refresh token 静默续期（成功重试 / 无 refresh token / 无用户信息 → 强制登出）
 *  - makeApi 的 get/post/put/patch/delete 转发
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';

const h = vi.hoisted(() => {
  const reqHandlers: Array<{ ok: (c: any) => any; err: (e: any) => any }> = [];
  const resHandlers: Array<{ ok: (r: any) => any; err: (e: any) => any }> = [];
  // axios 实例是可调用的（client.ts 中重试走 axiosInstance(config)）
  const instance: any = vi.fn();
  instance.get = vi.fn();
  instance.post = vi.fn();
  instance.put = vi.fn();
  instance.patch = vi.fn();
  instance.delete = vi.fn();
  instance.interceptors = {
    request: { use: (ok: any, err: any) => { reqHandlers.push({ ok, err }); } },
    response: { use: (ok: any, err: any) => { resHandlers.push({ ok, err }); } },
  };
  return {
    reqHandlers,
    resHandlers,
    instance,
    axiosPost: vi.fn(),
    clearSpy: vi.fn(),
    setAuthSpy: vi.fn(),
  };
});

vi.mock('axios', () => ({
  default: { create: () => h.instance, post: h.axiosPost },
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: {
    getState: () => ({ clear: h.clearSpy, setAuth: h.setAuthSpy }),
  },
}));

// 避免 jsdom 对 window.location.href 赋值触发 "Not implemented: navigation"
Object.defineProperty(window, 'location', {
  value: { pathname: '/', href: '', assign: vi.fn(), replace: vi.fn() },
  writable: true,
  configurable: true,
});

async function loadClient() {
  vi.resetModules();
  h.reqHandlers.length = 0;
  h.resHandlers.length = 0;
  const mod = await import('./client');
  return mod.default;
}

const user = { id: 'u1', username: 'alice', tenant_id: 'tenant_default', roles: [] };

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  (window.location as any).pathname = '/';
  (window.location as any).href = '';
});

describe('api/client 拦截器', () => {
  it('请求拦截器注入 Authorization（localStorage 有 token）', async () => {
    localStorage.setItem('nocobase_access_token', 'tok-123');
    await loadClient();
    const out = h.reqHandlers[0].ok({ headers: {} });
    expect(out.headers.Authorization).toBe('Bearer tok-123');
  });

  it('无 token 时不注入 Authorization', async () => {
    await loadClient();
    const out = h.reqHandlers[0].ok({ headers: {} });
    expect(out.headers.Authorization).toBeUndefined();
  });

  it('请求拦截器错误分支 reject', async () => {
    await loadClient();
    await expect(h.reqHandlers[0].err(new Error('req-boom'))).rejects.toThrow('req-boom');
  });

  it('响应拦截器解包 data', async () => {
    await loadClient();
    expect(h.resHandlers[0].ok({ data: { code: 0, id: 7 } })).toEqual({ code: 0, id: 7 });
  });

  it('非 401 错误直接透传', async () => {
    await loadClient();
    const err = { response: { status: 500 }, config: {} };
    await expect(h.resHandlers[0].err(err)).rejects.toBe(err);
  });

  it('401 但无 originalRequest 时透传原始 error', async () => {
    await loadClient();
    const err = { response: { status: 401 } };
    await expect(h.resHandlers[0].err(err)).rejects.toBe(err);
  });

  it('401 且无 refresh token → 拒绝并清理登录态', async () => {
    await loadClient();
    const err = { response: { status: 401 }, config: { headers: {} } };
    await expect(h.resHandlers[0].err(err)).rejects.toBeTruthy();
    expect(h.clearSpy).toHaveBeenCalled();
  });

  it('401 刷新成功且有用户信息 → 写入新 token 并重试原请求', async () => {
    localStorage.setItem('nocobase_refresh_token', 'old-rt');
    localStorage.setItem('nocobase-auth', JSON.stringify({ state: { user } }));
    h.axiosPost.mockResolvedValue({
      data: { data: { access_token: 'new-at', refresh_token: 'new-rt' } },
    });
    const client = await loadClient();
    const err = { response: { status: 401 }, config: { headers: {} } };
    await h.resHandlers[0].err(err);
    expect(localStorage.getItem('nocobase_access_token')).toBe('new-at');
    expect(localStorage.getItem('nocobase_refresh_token')).toBe('new-rt');
    expect(h.setAuthSpy).toHaveBeenCalledWith('new-at', 'new-rt', user);
    // 重试：实例被当函数调用并带上 _retry 标记
    expect(h.instance).toHaveBeenCalled();
    expect((err.config as any)._retry).toBe(true);
    expect(client).toBeDefined();
  });

  it('401 刷新成功但无用户信息 → 强制登出并拒绝（不得凭空造人）', async () => {
    localStorage.setItem('nocobase_refresh_token', 'old-rt');
    localStorage.removeItem('nocobase-auth');
    h.axiosPost.mockResolvedValue({ data: { data: { access_token: 'at2', refresh_token: 'rt2' } } });
    await loadClient();
    const err = { response: { status: 401 }, config: { headers: {} } };
    // 注：catch 块 reject 的是**原始 401 error**（不是 refresh 的内部错误），故断言透传
    await expect(h.resHandlers[0].err(err)).rejects.toBe(err);
    expect(h.clearSpy).toHaveBeenCalled();
    expect((window.location as any).href).toBe('/login');
  });

  it('401 刷新接口失败 → 拒绝并清理', async () => {
    localStorage.setItem('nocobase_refresh_token', 'old-rt');
    h.axiosPost.mockRejectedValue(new Error('refresh down'));
    await loadClient();
    const err = { response: { status: 401 }, config: { headers: {} } };
    await expect(h.resHandlers[0].err(err)).rejects.toBeTruthy();
    expect(h.clearSpy).toHaveBeenCalled();
  });

  it('刷新响应无 data 包装时也能取到 token', async () => {
    localStorage.setItem('nocobase_refresh_token', 'old-rt');
    localStorage.setItem('nocobase-auth', JSON.stringify({ state: { user } }));
    h.axiosPost.mockResolvedValue({ data: { accessToken: 'camel-at' } });
    await loadClient();
    const err = { response: { status: 401 }, config: { headers: {} } };
    await h.resHandlers[0].err(err);
    expect(localStorage.getItem('nocobase_access_token')).toBe('camel-at');
  });

  it('nocobase-auth 为非法 JSON 时按无用户信息处理', async () => {
    localStorage.setItem('nocobase_refresh_token', 'old-rt');
    localStorage.setItem('nocobase-auth', '{not-json');
    h.axiosPost.mockResolvedValue({ data: { data: { access_token: 'at3', refresh_token: 'rt3' } } });
    await loadClient();
    const err = { response: { status: 401 }, config: { headers: {} } };
    await expect(h.resHandlers[0].err(err)).rejects.toBe(err);
    expect(h.clearSpy).toHaveBeenCalled();
  });
});

describe('api/client makeApi 转发', () => {
  it('get/post/put/patch/delete 均转发到 axios 实例', async () => {
    const client = await loadClient();
    h.instance.get.mockResolvedValue({ ok: 1 });
    h.instance.post.mockResolvedValue({ ok: 2 });
    h.instance.put.mockResolvedValue({ ok: 3 });
    h.instance.patch.mockResolvedValue({ ok: 4 });
    h.instance.delete.mockResolvedValue({ ok: 5 });

    await expect(client.get('/a')).resolves.toEqual({ ok: 1 });
    await expect(client.post('/b', { x: 1 })).resolves.toEqual({ ok: 2 });
    await expect(client.put('/c', { x: 1 })).resolves.toEqual({ ok: 3 });
    await expect(client.patch('/d', { x: 1 })).resolves.toEqual({ ok: 4 });
    await expect(client.delete('/e')).resolves.toEqual({ ok: 5 });

    expect(h.instance.get).toHaveBeenCalledWith('/a', undefined);
    expect(h.instance.post).toHaveBeenCalledWith('/b', { x: 1 }, undefined);
    expect(h.instance.put).toHaveBeenCalledWith('/c', { x: 1 }, undefined);
    expect(h.instance.patch).toHaveBeenCalledWith('/d', { x: 1 }, undefined);
    expect(h.instance.delete).toHaveBeenCalledWith('/e', undefined);
  });

  it('透传 config', async () => {
    const client = await loadClient();
    h.instance.get.mockResolvedValue({});
    await client.get('/f', { params: { p: 1 } });
    expect(h.instance.get).toHaveBeenCalledWith('/f', { params: { p: 1 } });
  });
});
