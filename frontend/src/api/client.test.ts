/**
 * API client 拦截器测试(Week 38,Step A).
 *
 * 用 vi.mock('axios') 重写 axios.create 以返回同一个可控 instance,
 * 直接断言拦截器行为。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';

// vi.mock 会被 hoisted,必须用 vi.hoisted 共享变量
const mocks = vi.hoisted(() => {
  const fakeInstance: any = {
    interceptors: {
      request: { use: vi.fn() },
      response: { use: vi.fn() },
    },
    defaults: { headers: { common: {} } },
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  };

  let requestSuccessFn: ((c: any) => any) | null = null;
  let requestErrorFn: ((e: any) => any) | null = null;
  let responseSuccessFn: ((r: any) => any) | null = null;
  let responseErrorFn: ((e: any) => any) | null = null;

  fakeInstance.interceptors.request.use.mockImplementation((success: any, error: any) => {
    requestSuccessFn = success;
    requestErrorFn = error;
  });
  fakeInstance.interceptors.response.use.mockImplementation((success: any, error: any) => {
    responseSuccessFn = success;
    responseErrorFn = error;
  });

  return {
    fakeInstance,
    getRequestSuccess: () => requestSuccessFn,
    getRequestError: () => requestErrorFn,
    getResponseSuccess: () => responseSuccessFn,
    getResponseError: () => responseErrorFn,
  };
});

vi.mock('axios', () => ({
  default: Object.assign(vi.fn(), {
    create: vi.fn(() => mocks.fakeInstance),
  }),
}));

// 必须在 mock 后 import
import apiClient from './client';

describe('apiClient — 拦截器', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    Object.defineProperty(window, 'location', {
      value: { href: '' },
      writable: true,
      configurable: true,
    });
  });

  describe('请求拦截器 — token 注入', () => {
    it('localStorage 有 token 时自动注入 Authorization header', () => {
      localStorage.setItem('nocobase_access_token', 'fake-jwt-abc');
      const config = { headers: {} as Record<string, string> };
      const out = mocks.getRequestSuccess()!(config);
      expect(out.headers.Authorization).toBe('Bearer fake-jwt-abc');
    });

    it('localStorage 无 token 时不注入 Authorization header', () => {
      const config = { headers: {} as Record<string, string> };
      const out = mocks.getRequestSuccess()!(config);
      expect(out.headers.Authorization).toBeUndefined();
    });

    it('请求错误时直接 reject(error)', async () => {
      const err = new Error('network down');
      await expect(mocks.getRequestError()!(err)).rejects.toBe(err);
    });
  });

  describe('响应拦截器 — 解包 + 错误处理', () => {
    it('成功响应解包为 response.data', () => {
      const r = { data: { id: 'u1', name: 'alice' } };
      expect(mocks.getResponseSuccess()!(r)).toEqual({ id: 'u1', name: 'alice' });
    });

    it('401 错误(非 login 页):清 token + 重定向 /login', async () => {
      localStorage.setItem('nocobase_access_token', 'old-token');
      // 模拟非 login 页(window.location.pathname = '/admin/users')
      Object.defineProperty(window, 'location', {
        value: { pathname: '/admin/users', href: '' },
        writable: true,
      });
      const err = { response: { status: 401 } };
      await expect(mocks.getResponseError()!(err)).rejects.toBe(err);
      expect(localStorage.getItem('nocobase_access_token')).toBeNull();
      expect(window.location.href).toBe('/login');
    });

    it('401 错误(已在 login 页):不清 token + 不重定向', async () => {
      localStorage.setItem('nocobase_access_token', 'keep-me');
      // 模拟在 login 页
      Object.defineProperty(window, 'location', {
        value: { pathname: '/login', href: '' },
        writable: true,
      });
      const err = { response: { status: 401 } };
      await expect(mocks.getResponseError()!(err)).rejects.toBe(err);
      // 已在 login 页,不清 token(让 Login page 自己处理),不重定向
      expect(localStorage.getItem('nocobase_access_token')).toBe('keep-me');
      expect(window.location.href).toBe('');
    });

    it('非 401 错误(500):透传 + 保留 token', async () => {
      localStorage.setItem('nocobase_access_token', 'keep-me');
      const err = { response: { status: 500, data: { msg: 'boom' } } };
      await expect(mocks.getResponseError()!(err)).rejects.toBe(err);
      expect(localStorage.getItem('nocobase_access_token')).toBe('keep-me');
      expect(window.location.href).toBe('');
    });

    it('无 response 对象(网络错误):透传 + 保留 token', async () => {
      localStorage.setItem('nocobase_access_token', 'keep-me');
      const err = new Error('Network Error');
      await expect(mocks.getResponseError()!(err)).rejects.toBe(err);
      expect(localStorage.getItem('nocobase_access_token')).toBe('keep-me');
    });
  });

  describe('apiClient 方法委托给 axios instance', () => {
    it('get 调用 instance.get', () => {
      mocks.fakeInstance.get.mockResolvedValueOnce({ id: 'x' });
      apiClient.get('/test');
      expect(mocks.fakeInstance.get).toHaveBeenCalledWith('/test', undefined);
    });

    it('post 调用 instance.post 带 data + config', () => {
      mocks.fakeInstance.post.mockResolvedValueOnce({ ok: true });
      apiClient.post('/test', { foo: 1 }, { headers: { 'X-A': 'b' } });
      expect(mocks.fakeInstance.post).toHaveBeenCalledWith(
        '/test', { foo: 1 }, { headers: { 'X-A': 'b' } },
      );
    });

    it('put / patch / delete 同样委托', () => {
      mocks.fakeInstance.put.mockResolvedValue({ ok: true });
      mocks.fakeInstance.patch.mockResolvedValue({ ok: true });
      mocks.fakeInstance.delete.mockResolvedValue({ ok: true });
      apiClient.put('/r', { a: 1 });
      apiClient.patch('/r', { a: 2 });
      apiClient.delete('/r');
      expect(mocks.fakeInstance.put).toHaveBeenCalled();
      expect(mocks.fakeInstance.patch).toHaveBeenCalled();
      expect(mocks.fakeInstance.delete).toHaveBeenCalled();
    });
  });
});
