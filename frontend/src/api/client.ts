import axios, { type AxiosInstance } from 'axios';

/**
 * API 客户端 — 自动注入 JWT.
 *
 * Week 3 脚手架:从 zustand store 取 token
 * Week 5+ 会改为 OpenAPI 生成的类型化 client
 */
const axiosInstance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

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

// 响应拦截器:统一错误处理
axiosInstance.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      // 已在登录页时不要强制 reload(让 Login page 自己处理错误消息)
      const isLoginPage = window.location.pathname.startsWith('/login');
      if (!isLoginPage) {
        localStorage.removeItem('nocobase_access_token');
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  },
);

export default apiClient;
