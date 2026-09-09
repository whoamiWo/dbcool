import axios, { type AxiosInstance } from 'axios';

/**
 * API 客户端 — 自动注入 JWT.
 *
 * Week 3 脚手架:从 zustand store 取 token
 * Week 5+ 会改为 OpenAPI 生成的类型化 client
 */
const apiClient: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// 请求拦截器:自动注入 token
apiClient.interceptors.request.use(
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
apiClient.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('nocobase_access_token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  },
);

export default apiClient;
