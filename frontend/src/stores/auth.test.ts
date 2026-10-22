/**
 * PHASE 4.2 覆盖率补测：stores/auth.ts
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { useAuthStore } from './auth';

describe('auth store', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('默认 user=null', () => {
    const state = useAuthStore.getState();
    expect(state.user).toBeNull();
    expect(state.accessToken).toBeNull();
  });

  it('setAuth 后 user 被设置', () => {
    const mockUser = { id: 'u1', username: 'alice', tenant_id: 't1', roles: ['admin'] };
    useAuthStore.getState().setAuth('tok', 'ref', mockUser);
    const state = useAuthStore.getState();
    expect(state.user?.username).toBe('alice');
    expect(state.accessToken).toBe('tok');
    expect(localStorage.getItem('nocobase_access_token')).toBe('tok');
  });

  it('clear 清除用户状态', () => {
    const mockUser = { id: 'u1', username: 'alice', tenant_id: 't1', roles: ['admin'] };
    useAuthStore.getState().setAuth('tok', 'ref', mockUser);
    useAuthStore.getState().clear();
    const state = useAuthStore.getState();
    expect(state.user).toBeNull();
    expect(state.accessToken).toBeNull();
    expect(localStorage.getItem('nocobase_access_token')).toBeNull();
  });

  it('switchTenant 切换租户后 tenant_id 更新', () => {
    const mockUser = { id: 'u1', username: 'alice', tenant_id: 't1', roles: ['admin'] };
    useAuthStore.getState().setAuth('tok', 'ref', mockUser);
    useAuthStore.getState().switchTenant('t2');
    const state = useAuthStore.getState();
    expect(state.user?.id).toBe('u1');
    expect(state.user?.tenant_id).toBe('t2');
  });
});