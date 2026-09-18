import { useState, useCallback, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { handleDingTalkCallback, handleDingTalkLogin, isInDingTalk } from '@/services/dingtalk';

interface UseDingTalkAuthReturn {
  loading: boolean;
  error: string | null;
  isInDingTalk: boolean;
  login: () => Promise<void>;
  handleCallback: () => Promise<void>;
}

/**
 * 钉钉认证 Hook
 * 管理钉钉 SSO 登录的完整流程
 */
export function useDingTalkAuth(): UseDingTalkAuthReturn {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchParams] = useSearchParams();

  const inDingTalk = isInDingTalk();

  // 检查 URL 中是否有钉钉回调 code
  useEffect(() => {
    const code = searchParams.get('code');
    if (code && inDingTalk) {
      handleCallback();
    }
  }, [searchParams, inDingTalk]);

  const login = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      await handleDingTalkLogin();
    } catch (err) {
      const message = err instanceof Error ? err.message : '钉钉登录失败';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  const handleCallback = useCallback(async () => {
    const code = searchParams.get('code');
    if (!code) return;

    setLoading(true);
    setError(null);
    try {
      await handleDingTalkCallback(code);
    } catch (err) {
      const message = err instanceof Error ? err.message : '登录回调处理失败';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, [searchParams]);

  return {
    loading,
    error,
    isInDingTalk: inDingTalk,
    login,
    handleCallback,
  };
}
