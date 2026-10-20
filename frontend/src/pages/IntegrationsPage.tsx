import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();
  const [integrations, setIntegrations] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [processing, setProcessing] = useState<string | null>(null);

  const loadIntegrations = async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await integrationApi.getMarket();
      if (res.code === 0) {
        setIntegrations(res.data?.integrations || []);
      } else {
        setError(res.message || t('integrations.loadFailed'));
      }
    } catch (err: any) {
      setError(err.message || t('integrations.networkError'));
    } finally {
      setLoading(false);
    }
  };

   
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：异步/条件/初始化模式，非连续渲染
    loadIntegrations();
  }, []);

  const handleInstall = async (id: string) => {
    try {
      setProcessing(id);
      const res = await integrationApi.install(id);
      if (res.code === 0) {
        await loadIntegrations();
      } else {
        alert(res.message || t('integrations.installFailed'));
      }
    } catch (err: any) {
      alert(err.message || t('integrations.networkError'));
    } finally {
      setProcessing(null);
    }
  };

  const handleUninstall = async (id: string) => {
    if (!confirm(t('integrations.confirmUninstall'))) {
      return;
    }
    try {
      setProcessing(id);
      const res = await integrationApi.uninstall(id);
      if (res.code === 0) {
        await loadIntegrations();
      } else {
        alert(res.message || t('integrations.uninstallFailed'));
      }
    } catch (err: any) {
      alert(err.message || t('integrations.networkError'));
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
        {t('integrations.title')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t('integrations.subtitle')}
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
                subheader={integration.kind === 'builtin' ? t('integrations.kindBuiltin') : t('integrations.kindPlugin')}
                action={
                  integration.installed && (
                    <Chip label={t('integrations.installed')} color="success" size="small" />
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
                    {processing === integration.id ? t('integrations.processing') : t('integrations.uninstall')}
                  </Button>
                ) : (
                  <Button
                    variant="contained"
                    fullWidth
                    disabled={processing === integration.id}
                    onClick={() => handleInstall(integration.id)}
                  >
                    {processing === integration.id ? t('integrations.installing') : t('integrations.install')}
                  </Button>
                )}
              </Box>
            </Card>
          </Grid>
        ))}
      </Grid>

      {integrations.length === 0 && (
        <Alert severity="info" sx={{ mt: 2 }}>
          {t('integrations.empty')}
        </Alert>
      )}
    </Box>
  );
}
