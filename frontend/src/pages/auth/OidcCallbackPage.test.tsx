import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { StrictMode, isValidElement } from 'react';
import apiClient from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { OidcCallbackPage } from './OidcCallbackPage';
import { router } from '@/router';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const user = {
  id: 'user-1',
  username: 'oidc-user',
  tenant_id: 'tenant_default',
  roles: ['editor'],
};
const mockedGet = vi.mocked(apiClient.get);
const mockedPost = vi.mocked(apiClient.post);

function LoginDestination() {
  const location = useLocation();
  return <div>登录页 {location.pathname}</div>;
}

function WorkbenchDestination() {
  const location = useLocation();
  return <div>工作台 {location.pathname}</div>;
}

function renderCallback() {
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={['/oidc/callback']}>
        <Routes>
          <Route path="/oidc/callback" element={<OidcCallbackPage />} />
          <Route path="/login" element={<LoginDestination />} />
          <Route path="/workbench" element={<WorkbenchDestination />} />
        </Routes>
      </MemoryRouter>
    </StrictMode>,
  );
}

describe('OidcCallbackPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.getState().clear();
    window.history.replaceState(null, '', '/oidc/callback?next=%2Fworkbench&code=one-time-code#section');
  });

  it('先清地址栏 code，StrictMode 只交换一次，成功后才持久化 JWT 和用户信息', async () => {
    mockedPost.mockImplementation(async () => {
      expect(window.location.search).toBe('?next=%2Fworkbench');
      expect(window.location.href).not.toContain('one-time-code');
      expect(localStorage.getItem('nocobase_access_token')).toBeNull();
      expect(localStorage.getItem('nocobase_refresh_token')).toBeNull();
      expect(useAuthStore.getState().accessToken).toBeNull();
      return { code: 0, data: { access_token: 'exchanged-jwt', user } };
    });

    const view = renderCallback();

    await waitFor(() => expect(view.getByText('工作台 /workbench')).toBeInTheDocument());
    expect(window.history.length).toBe(1);
    expect(mockedPost).toHaveBeenCalledWith('/auth/oidc/exchange', { code: 'one-time-code' });
    expect(mockedPost).toHaveBeenCalledTimes(1);
    expect(mockedGet).not.toHaveBeenCalled();
    expect(useAuthStore.getState().accessToken).toBe('exchanged-jwt');
    expect(useAuthStore.getState().refreshToken).toBe('');
    expect(useAuthStore.getState().user).toEqual(user);
    expect(localStorage.getItem('nocobase_access_token')).toBe('exchanged-jwt');
    expect(view.container).not.toHaveTextContent('one-time-code');
  });

  it('交换失败时清理认证状态，replace 到登录页，不展示或持久化 code/JWT', async () => {
    localStorage.setItem('nocobase_access_token', 'stale-token');
    localStorage.setItem('nocobase_refresh_token', 'stale-refresh');
    mockedPost.mockRejectedValue(new Error('request failed with code=one-time-code'));

    const view = renderCallback();

    await waitFor(() => expect(view.getByText('登录页 /login')).toBeInTheDocument());
    expect(window.history.length).toBe(1);
    expect(window.location.href).not.toContain('one-time-code');
    expect(mockedPost).toHaveBeenCalledTimes(1);
    expect(mockedGet).not.toHaveBeenCalled();
    expect(localStorage.getItem('nocobase_access_token')).toBeNull();
    expect(localStorage.getItem('nocobase_refresh_token')).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
    expect(view.container).not.toHaveTextContent('one-time-code');
  });

  it('缺失 code 时不请求 exchange，并清理后 replace 到登录页', async () => {
    window.history.replaceState(null, '', '/oidc/callback?token=legacy-token-must-not-be-used');
    const view = renderCallback();

    await waitFor(() => expect(view.getByText('登录页 /login')).toBeInTheDocument());
    expect(mockedPost).not.toHaveBeenCalled();
    expect(mockedGet).not.toHaveBeenCalled();
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(localStorage.getItem('nocobase_access_token')).toBeNull();
    expect(window.history.length).toBe(1);
  });

  it('/oidc/callback 已注册为独立公开路由', () => {
    const route = router.routes.find(item => item.path === '/oidc/callback');
    expect(route).toBeDefined();
    expect(route?.children).toBeUndefined();
    expect(isValidElement(route?.element)).toBe(true);
    if (isValidElement(route?.element)) {
      expect(route.element.type).toBe(OidcCallbackPage);
    }
  });
});
