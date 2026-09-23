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
          // 解析失败，使用默认用户
        }
      }
      if (storedUser) {
        useAuthStore.getState().setAuth(access_token, refresh_token, storedUser);
      } else {
        useAuthStore.getState().setAuth(access_token, refresh_token, {
          id: 'unknown',
          username: 'unknown',
          tenant_id: 'default',
          roles: []
        });
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
