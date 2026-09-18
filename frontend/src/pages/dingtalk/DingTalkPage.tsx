import { Box, Button, Typography, Card, CardContent, Grid, Alert, Chip } from '@mui/material';
import { useState, useEffect } from 'react';
import { useDingTalkAuth } from '@/hooks/useDingTalkAuth';
import { dingtalkApi } from '@/api/integrations';

export function DingTalkPage() {
  const { loading, error, isInDingTalk: inDingTalk } = useDingTalkAuth();
  const [syncStatus, setSyncStatus] = useState<any>(null);
  const [userMappings, setUserMappings] = useState<any[]>([]);

  useEffect(() => {
    // 获取同步状态
    dingtalkApi.getSyncStatus().then(res => setSyncStatus(res.data)).catch(console.error);
    // 获取用户映射
    dingtalkApi.getUserMappings().then(res => setUserMappings(res.data.mappings || [])).catch(console.error);
  }, []);

  const handleSyncOrg = async () => {
    try {
      const result = await dingtalkApi.syncOrganization();
      setSyncStatus(result.data);
    } catch (err) {
      console.error('同步失败', err);
    }
  };

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h4" gutterBottom>钉钉集成</Typography>

      <Grid container spacing={3}>
        {/* SSO 状态 */}
        <Grid size={{ xs: 12, md: 4 }}>
          <Card>
            <CardContent>
              <Typography variant="h6">SSO 登录</Typography>
              <Chip label={inDingTalk ? "钉钉环境" : "普通环境"} color={inDingTalk ? "success" : "default"} />
              {loading && <Typography variant="body2">加载中...</Typography>}
              {error && <Alert severity="error">{error}</Alert>}
              <Button variant="contained" onClick={() => window.location.href = '/api/dingtalk/auth-url'}>
                钉钉扫码登录
              </Button>
            </CardContent>
          </Card>
        </Grid>

        {/* 组织架构同步 */}
        <Grid size={{ xs: 12, md: 4 }}>
          <Card>
            <CardContent>
              <Typography variant="h6">组织架构同步</Typography>
              <Button variant="contained" onClick={handleSyncOrg} disabled={loading}>
                立即同步
              </Button>
              {syncStatus && (
                <Typography variant="body2" sx={{ mt: 1 }}>
                  部门: {syncStatus.data?.departmentsSynced} | 用户: {syncStatus.data?.usersSynced}
                </Typography>
              )}
            </CardContent>
          </Card>
        </Grid>

        {/* 用户映射 */}
        <Grid size={{ xs: 12, md: 4 }}>
          <Card>
            <CardContent>
              <Typography variant="h6">用户映射 ({userMappings.length})</Typography>
              {userMappings.slice(0, 5).map((m, i) => (
                <Chip key={i} label={`${m.username} (${m.dingtalkUserId})`} sx={{ mr: 1, mb: 1 }} />
              ))}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
}
