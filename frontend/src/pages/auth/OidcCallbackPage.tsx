import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import apiClient from '@/api/client';
import { useAuthStore, type User } from '@/stores/auth';

interface ExchangeResponse {
  code: number;
  data?: {
    access_token?: string;
    accessToken?: string;
    user?: User;
  };
}

export function OidcCallbackPage() {
  const navigate = useNavigate();
  const [code] = useState(() => new URLSearchParams(window.location.search).get('code'));
  const exchanged = useRef(false);

  useEffect(() => {
    // StrictMode repeats effects; the in-flight exchange must still run exactly once.
    if (exchanged.current) return;
    exchanged.current = true;

    const params = new URLSearchParams(window.location.search);
    params.delete('code');
    const query = params.toString();
    window.history.replaceState(
      null,
      '',
      `${window.location.pathname}${query ? `?${query}` : ''}${window.location.hash}`,
    );
    const fail = () => {
      useAuthStore.getState().clear();
      navigate('/login', { replace: true });
    };

    const exchange = async () => {
      // Do not let a stale session authorize the exchange or trigger a token refresh.
      useAuthStore.getState().clear();
      if (!code) {
        fail();
        return;
      }
      try {
        const response = await apiClient.post<ExchangeResponse>('/auth/oidc/exchange', { code });
        const accessToken = response.data?.access_token ?? response.data?.accessToken;
        const user = response.data?.user;
        if (response.code !== 0 || !accessToken || !user?.id) {
          fail();
          return;
        }
        // The token exists only in memory until the one-time exchange succeeds.
        useAuthStore.getState().setAuth(accessToken, '', user);
        navigate('/workbench', { replace: true });
      } catch {
        fail();
      }
    };
    void exchange();
  }, [navigate, code]);

  return <p role="status">正在完成 OIDC 登录…</p>;
}
