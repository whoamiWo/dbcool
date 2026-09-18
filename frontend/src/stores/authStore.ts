import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface User {
  userId: string;
  username: string;
  displayName: string;
  avatarUrl?: string;
  roles: string[];
  tenantId: string;
}

interface AuthState {
  // 状态
  token: string | null;
  user: User | null;
  departments: Array<{ id: string; name: string; parentId: string }>;
  users: User[];
  isAuthenticated: boolean;
  isLoading: boolean;

  // 动作
  setToken: (token: string) => void;
  setUser: (user: User) => void;
  setDepartments: (departments: AuthState['departments']) => void;
  setUsers: (users: User[]) => void;
  logout: () => void;
  setLoading: (loading: boolean) => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      departments: [],
      users: [],
      isAuthenticated: false,
      isLoading: false,

      setToken: (token) => set({ token, isAuthenticated: !!token }),

      setUser: (user) => set({ user, isAuthenticated: !!user }),

      setDepartments: (departments) => set({ departments }),

      setUsers: (users) => set({ users }),

      logout: () => set({ token: null, user: null, departments: [], users: [], isAuthenticated: false }),

      setLoading: (isLoading) => set({ isLoading }),
    }),
    {
      name: 'auth-storage',
      partialize: (state) => ({
        token: state.token,
        isAuthenticated: state.isAuthenticated,
      }),
    }
  )
);
