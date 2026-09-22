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
import { wecomApi } from '@/api/integrations';

interface WeComLoginPageProps {
  onError?: (error: string) => void;
}

export function WeComLoginPage({ onError }: WeComLoginPageProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleWeComLogin = async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await wecomApi.getAuthUrl();

      if (response.code === 0 && response.data?.authUrl) {
        // 重定向到企业微信授权页面
        window.location.href = response.data.authUrl;
      } else {
        throw new Error(response.message || '获取企微授权链接失败');
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : '企业微信登录失败';
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
              企业微信登录
            </Typography>
            <Typography variant="body2" sx={{ color: 'var(--color-text-muted)' }}>
              使用企业微信扫码快速登录 DBCool 平台
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
            onClick={handleWeComLogin}
            disabled={loading}
            startIcon={loading ? <CircularProgress size={20} /> : <Login />}
            sx={{
              py: 1.5,
              bgcolor: 'var(--color-success)',
              '&:hover': { bgcolor: 'var(--color-success)' },
            }}
          >
            {loading ? '正在跳转企微...' : '企业微信扫码登录'}
          </Button>

          <Typography
            variant="caption"
            sx={{ mt: 2, display: 'block', textAlign: 'center', color: 'var(--color-text-muted)' }}
          >
            点击按钮后将跳转到企业微信授权页面，确认后自动返回
          </Typography>
        </CardContent>
      </Card>
    </Box>
  );
}
