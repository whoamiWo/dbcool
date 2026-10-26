import { useState } from 'react';
import {
  Box,
  Button,
  Typography,
  CircularProgress,
  Alert,
  Card,
  CardContent,
} from '@mui/material';
import { Login } from '@mui/icons-material';
import { wechatApi } from '@/api/integrations';

interface WeChatLoginPageProps {
  onError?: (error: string) => void;
}

/**
 * 微信公众号扫码 / 授权登录页。
 *
 * <p>点击后调用 {@code GET /api/wechat/auth-url} 拿到公众号网页授权链接并跳转；
 * 用户授权后微信回调到本服务 {@code POST /api/wechat/callback}，
 * 后端以 openid 建立 {@code ExternalUserMapping} 并签发 JWT。
 */
export function WeChatLoginPage({ onError }: WeChatLoginPageProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleWeChatLogin = async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await wechatApi.getAuthUrl();

      if (response.code === 0 && response.data?.authUrl) {
        // 跳转到微信公众号网页授权页面
        window.location.href = response.data.authUrl;
      } else {
        throw new Error(response.message || '获取微信授权链接失败');
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : '微信登录失败';
      setError(message);
      onError?.(message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box
      sx={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '100vh',
        backgroundColor: 'var(--color-bg-primary)',
      }}
    >
      <Card
        sx={{
          maxWidth: 400,
          width: '100%',
          mx: 2,
          background: 'var(--color-bg-secondary)',
          border: '1px solid var(--color-border-light)',
        }}
      >
        <CardContent sx={{ p: 4 }}>
          <Box sx={{ textAlign: 'center', mb: 3 }}>
            <Login sx={{ fontSize: 48, color: 'var(--color-success)', mb: 1 }} />
            <Typography variant="h5" gutterBottom sx={{ color: 'var(--color-text-primary)' }}>
              微信登录
            </Typography>
            <Typography variant="body2" sx={{ color: 'var(--color-text-muted)' }}>
              使用微信扫码快速登录 DBCool 平台
            </Typography>
          </Box>

          {error && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          )}

          <Button
            fullWidth
            variant="contained"
            size="large"
            onClick={handleWeChatLogin}
            disabled={loading}
            startIcon={loading ? <CircularProgress size={20} /> : <Login />}
            sx={{
              py: 1.5,
              bgcolor: 'var(--color-success)',
              '&:hover': { bgcolor: 'var(--color-success)' },
            }}
          >
            {loading ? '正在跳转微信...' : '微信扫码登录'}
          </Button>

          <Typography
            variant="caption"
            sx={{ mt: 2, display: 'block', textAlign: 'center', color: 'var(--color-text-muted)' }}
          >
            点击按钮后将跳转到微信公众号授权页面，确认后自动返回
          </Typography>
        </CardContent>
      </Card>
    </Box>
  );
}
