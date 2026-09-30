import { useState, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  CardHeader,
  Button,
  Typography,
  Grid,
  Chip,
  Alert,
  CircularProgress,
} from '@mui/material';
import { integrationApi } from '../api/integrations';

export default function IntegrationsPage() {
  const [integrations, setIntegrations] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [processing, setProcessing] = useState<string | null>(null);

  useEffect(() => {
    loadIntegrations();
  }, []);

  const loadIntegrations = async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await integrationApi.getMarket();
      if (res.code === 0) {
        setIntegrations(res.data?.integrations || []);
      } else {
        setError(res.message || '加载集成列表失败');
      }
    } catch (err: any) {
      setError(err.message || '网络错误');
    } finally {
      setLoading(false);
    }
  };

  const handleInstall = async (id: string) => {
    try {
      setProcessing(id);
      const res = await integrationApi.install(id);
      if (res.code === 0) {
        await loadIntegrations();
      } else {
        alert(res.message || '安装失败');
      }
    } catch (err: any) {
      alert(err.message || '网络错误');
    } finally {
      setProcessing(null);
    }
  };

  const handleUninstall = async (id: string) => {
    if (!confirm('确定要卸载此集成吗？')) {
      return;
    }
    try {
      setProcessing(id);
      const res = await integrationApi.uninstall(id);
      if (res.code === 0) {
        await loadIntegrations();
      } else {
        alert(res.message || '卸载失败');
      }
    } catch (err: any) {
      alert(err.message || '网络错误');
    } finally {
      setProcessing(null);
    }
  };

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '400px' }}>
        <CircularProgress />
      </Box>
    );
  }

  if (error) {
    return (
      <Alert severity="error" sx={{ mt: 2 }}>
        {error}
      </Alert>
    );
  }

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h4" gutterBottom>
        集成市场
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        连接第三方应用，扩展系统能力。安装集成后，可在相应设置页面进行配置。
      </Typography>

      <Grid container spacing={3}>
        {integrations.map((integration) => (
          <Grid size={{ xs: 12, sm: 6, md: 4 }} key={integration.id}>
            <Card
              variant="outlined"
              sx={{
                height: '100%',
                display: 'flex',
                flexDirection: 'column',
              }}
            >
              <CardHeader
                title={integration.name}
                subheader={integration.kind === 'builtin' ? '内置渠道' : '插件'}
                action={
                  integration.installed && (
                    <Chip label="已安装" color="success" size="small" />
                  )
                }
              />
              <CardContent sx={{ flexGrow: 1 }}>
                <Typography variant="body2" color="text.secondary">
                  {integration.description}
                </Typography>
              </CardContent>
              <Box sx={{ p: 2, pt: 0 }}>
                {integration.installed ? (
                  <Button
                    variant="outlined"
                    color="error"
                    fullWidth
                    disabled={processing === integration.id}
                    onClick={() => handleUninstall(integration.id)}
                  >
                    {processing === integration.id ? '处理中...' : '卸载'}
                  </Button>
                ) : (
                  <Button
                    variant="contained"
                    fullWidth
                    disabled={processing === integration.id}
                    onClick={() => handleInstall(integration.id)}
                  >
                    {processing === integration.id ? '安装中...' : '安装'}
                  </Button>
                )}
              </Box>
            </Card>
          </Grid>
        ))}
      </Grid>

      {integrations.length === 0 && (
        <Alert severity="info" sx={{ mt: 2 }}>
          暂无可用集成
        </Alert>
      )}
    </Box>
  );
}
