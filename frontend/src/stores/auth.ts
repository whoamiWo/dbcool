import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface User {
  id: string;
  username: string;
  tenant_id: string;
  roles: string[];
}

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: User | null;
  setAuth: (accessToken: string, refreshToken: string, user: User) => void;
  clear: () => void;
  /** US-504: 切换应用(租户) */
  switchTenant: (tenantId: string) => void;
}

/**
 * 认证状态管理.
 *
 * 持久化 access_token + refresh_token 到 localStorage,
 * apiClient 响应拦截器在 401 时自动静默续期.
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      setAuth: (accessToken, refreshToken, user) => {
        localStorage.setItem('nocobase_access_token', accessToken);
        if (refreshToken) {
          localStorage.setItem('nocobase_refresh_token', refreshToken);
        }
        set({ accessToken, refreshToken, user });
      },
      clear: () => {
        localStorage.removeItem('nocobase_access_token');
        localStorage.removeItem('nocobase_refresh_token');
        set({ accessToken: null, refreshToken: null, user: null });
      },
      /** US-504: 切换应用(租户) —— 仅更新 user.tenant_id, persist 中间件自动同步 localStorage */
      switchTenant: (tenantId) => {
        set((state) => ({
          user: state.user ? { ...state.user, tenant_id: tenantId } : null,
        }));
      },
    }),
    {
      name: 'nocobase-auth',
    },
  ),
);
