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

interface DingTalkLoginPageProps {
  onLoginSuccess?: (token: string) => void;
  onError?: (error: string) => void;
}

export function DingTalkLoginPage({ onError }: DingTalkLoginPageProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleDingTalkLogin = async () => {
    setLoading(true);
    setError(null);
    try {
      // 调用后端钉钉授权 URL
      const response = await fetch('/api/dingtalk/auth-url', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      });
      const data = await response.json();

      if (data.code === 0 && data.data?.authUrl) {
        // 重定向到钉钉授权页面
        window.location.href = data.data.authUrl;
      } else {
        throw new Error(data.message || '获取钉钉授权链接失败');
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : '钉钉登录失败';
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
        backgroundColor: '#f5f5f5',
      }}
    >
      <Card sx={{ maxWidth: 400, width: '100%', mx: 2 }}>
        <CardContent sx={{ p: 4 }}>
          <Box sx={{ textAlign: 'center', mb: 3 }}>
            <Login sx={{ fontSize: 48, color: '#1976D2', mb: 1 }} />
            <Typography variant="h5" gutterBottom>
              钉钉登录
            </Typography>
            <Typography variant="body2" color="text.secondary">
              使用钉钉扫码快速登录 DBCool 平台
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
            onClick={handleDingTalkLogin}
            disabled={loading}
            startIcon={loading ? <CircularProgress size={20} /> : <Login />}
            sx={{ py: 1.5 }}
          >
            {loading ? '正在跳转钉钉...' : '钉钉扫码登录'}
          </Button>

          <Typography variant="caption" color="text.secondary" sx={{ mt: 2, display: 'block', textAlign: 'center' }}>
            点击按钮后将跳转到钉钉授权页面，确认后自动返回
          </Typography>
        </CardContent>
      </Card>
    </Box>
  );
}
