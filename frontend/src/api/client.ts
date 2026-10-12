import axios, { type AxiosInstance } from 'axios';
import { useAuthStore } from '@/stores/auth';

/**
 * API 客户端 — 自动注入 JWT + refresh token 静默续期.
 */
const axiosInstance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

let isRefreshing = false;
let refreshSubscribers: ((token: string | null) => void)[] = [];
let refreshRequest: Promise<string> | null = null;

/** 并发 401 排队等待：避免多个请求同时触发 refresh 导致竞态失败。 */
function subscribeTokenRefresh(cb: (token: string | null) => void) {
  refreshSubscribers.push(cb);
}

/**
 * PHASE98 R2: 登录态失效时强制登出并跳登录页。
 *
 * 用于"token 刷新成功但取不到用户信息"这类不可自愈的状态 —— 此时不能造一个
 * 假用户继续跑（见 handleRefreshToken 内的说明），必须让用户重新认证。
 */
function forceLogout() {
  try {
    useAuthStore.getState().clear();
  } catch {
    /* store 不可用时也要清 localStorage */
  }
  try {
    localStorage.removeItem('nocobase_access_token');
    localStorage.removeItem('nocobase_refresh_token');
    localStorage.removeItem('nocobase-auth');
  } catch {
    /* localStorage 不可用时忽略 */
  }
  if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
    window.location.href = '/login';
  }
}

function notifyRefreshCallbacks(token: string | null) {
  refreshSubscribers.forEach((cb) => cb(token));
  refreshSubscribers = [];
}

async function handleRefreshToken(): Promise<string> {
  if (isRefreshing) {
    // 已有刷新进行中 → 排队等待，而非直接 reject
    return new Promise<string>((resolve, reject) => {
      subscribeTokenRefresh((token) => {
        if (token) {
          resolve(token);
        } else {
          reject(new Error('Refresh failed'));
        }
      });
    });
  }

  isRefreshing = true;
  refreshRequest = (async () => {
    try {
      const refreshToken = localStorage.getItem('nocobase_refresh_token');
      if (!refreshToken) {
        throw new Error('No refresh token');
      }

      const res = await axios.post('/api/auth/refresh', { refreshToken });
      const data = res.data?.data ?? res.data;
      const access_token = data.access_token ?? data.accessToken ?? '';
      const refresh_token = data.refresh_token ?? data.refreshToken ?? '';

      localStorage.setItem('nocobase_access_token', access_token);
      if (refresh_token) {
        localStorage.setItem('nocobase_refresh_token', refresh_token);
      }

      // 刷新 token 成功后，从 localStorage 读取持久化的用户信息
      const stored = localStorage.getItem('nocobase-auth');
      let storedUser = null;
      if (stored) {
        try {
          const parsed = JSON.parse(stored);
          storedUser = parsed?.state?.user ?? null;
        } catch {
          storedUser = null;
        }
      }

      if (storedUser) {
        useAuthStore.getState().setAuth(access_token, refresh_token, storedUser);
      } else {
        // PHASE98 R2: 取不到用户信息时**不得凭空造人**。
        //
        // 原实现会写入 { id:'unknown', username:'unknown', tenant_id:'default', roles:[] }，
        // 制造一个"已登录但身份未知"的静默降级态，三处后果：
        //   1. UI 显示已登录、用户名 unknown；
        //   2. tenant_id:'default' 不是合法租户（应为 tenant_default），后续所有带租户的
        //      请求都会指向不存在的租户 —— 可能绕过 PHASE95 刚加固的租户隔离；
        //   3. roles:[] 让权限判断静默为"无权限"，页面大面积空白且无任何提示。
        // 正确做法：认定登录态已失效 → 强制登出并跳登录页，让用户重新认证。
        forceLogout();
        notifyRefreshCallbacks(null);
        throw new Error('User info missing after refresh — forcing re-login');
      }

      notifyRefreshCallbacks(access_token);
      return access_token;
    } catch (e) {
      // 排队的订阅者也失败：清空队列并 reject，避免 Promise 永久悬挂
      const failed = [...refreshSubscribers];
      refreshSubscribers = [];
      failed.forEach(cb => cb(null));
      throw e;
    } finally {
      isRefreshing = false;
      refreshRequest = null;
    }
  })();
  return refreshRequest;
}

// 包装:get/post/etc 直接返回 T(response 拦截器已解 data)
function makeApi(instance: AxiosInstance) {
  return {
    get: <T,>(url: string, config?: Parameters<AxiosInstance['get']>[1]) =>
      instance.get<T, T>(url, config) as unknown as Promise<T>,
    post: <T,>(url: string, data?: unknown, config?: Parameters<AxiosInstance['post']>[2]) =>
      instance.post<T, T>(url, data, config) as unknown as Promise<T>,
    put: <T,>(url: string, data?: unknown, config?: Parameters<AxiosInstance['put']>[2]) =>
      instance.put<T, T>(url, data, config) as unknown as Promise<T>,
    patch: <T,>(url: string, data?: unknown, config?: Parameters<AxiosInstance['patch']>[2]) =>
      instance.patch<T, T>(url, data, config) as unknown as Promise<T>,
    delete: <T,>(url: string, config?: Parameters<AxiosInstance['delete']>[1]) =>
      instance.delete<T, T>(url, config) as unknown as Promise<T>,
  };
}
const apiClient = makeApi(axiosInstance);

// 请求拦截器:自动注入 token
axiosInstance.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('nocobase_access_token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error),
);

// 响应拦截器:统一错误处理 + refresh token 静默续期
axiosInstance.interceptors.response.use(
  (response) => response.data,
  async (error) => {
    const originalRequest = error?.config;

    if (error.response?.status === 401 && !originalRequest?._retry) {
      if (!originalRequest) {
        // 无 config（如测试直接传 error 对象），透传原始 error
        return Promise.reject(error);
      }
      originalRequest._retry = true;

      try {
        const newToken = await handleRefreshToken();
        originalRequest.headers.Authorization = `Bearer ${newToken}`;
        return axiosInstance(originalRequest);
      } catch {
        // 刷新失败,清除登录态并跳转登录页
        const isLoginPage = window.location.pathname.startsWith('/login');
        if (!isLoginPage) {
          localStorage.removeItem('nocobase_access_token');
          localStorage.removeItem('nocobase_refresh_token');
          useAuthStore.getState().clear();
          window.location.href = '/login';
        }
        return Promise.reject(error);
      }
    }
    return Promise.reject(error);
  },
);

export default apiClient;
