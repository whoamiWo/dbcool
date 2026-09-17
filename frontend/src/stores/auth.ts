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
  user: User | null;
  setAuth: (token: string, user: User) => void;
  clear: () => void;
  /** US-504: 切换应用(租户) */
  switchTenant: (tenantId: string) => void;
}

/**
 * 认证状态管理.
 *
 * Week 3 脚手架:仅持久化 token 到 localStorage
 * Week 4+ 会用 HttpOnly Cookie + refresh token 机制
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      user: null,
      setAuth: (accessToken, user) => {
        localStorage.setItem('nocobase_access_token', accessToken);
        set({ accessToken, user });
      },
      clear: () => {
        localStorage.removeItem('nocobase_access_token');
        set({ accessToken: null, user: null });
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
