import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Box,
  Button,
  Typography,
  Paper,
  Divider,
  Card,
  CardContent,
  CircularProgress,
  Alert,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
} from '@mui/material';
import {
  ArrowBack as ArrowBackIcon,
  Restore as RestoreIcon,
} from '@mui/icons-material';
import { wikiApi } from '@/api/wiki';
import { VersionDiff } from '@/components/wiki/VersionDiff';
import type { WikiVersion, WikiPage } from '@/types/wiki';

export function WikiVersionHistoryPage() {
  const { slug } = useParams<{ slug: string }>();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [summary, setSummary] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [compareVersion, setCompareVersion] = useState<number | null>(null);

  // 先找页面
  const { data: pageData, isLoading: pageLoading } = useQuery({
    queryKey: ['wiki-page', slug],
    queryFn: async () => {
      const listRes = await wikiApi.listPages({ kbId: undefined });
      const pages = Array.isArray(listRes) ? listRes : (listRes as any)?.data || [];
      const page = pages.find((p: WikiPage) => p.slug === slug);
      if (!page) throw new Error('Page not found');
      return page as WikiPage;
    },
    enabled: !!slug,
  });

  // 获取版本列表
  const { data: versionsData, isLoading: versionsLoading } = useQuery({
    queryKey: ['wiki-versions', pageData?.id],
    queryFn: () => wikiApi.listVersions(pageData!.id),
    enabled: !!pageData,
  });



  const restoreMutation = useMutation({
    mutationFn: (version: number) => wikiApi.restoreVersion(pageData!.id, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-versions', pageData!.id] });
      queryClient.invalidateQueries({ queryKey: ['wiki-page', slug] });
      setOpen(false);
      setSummary('');
      setSuccess(true);
      setTimeout(() => setSuccess(false), 3000);
    },
    onError: (e: any) => {
      setError(e?.response?.data?.message || '回滚失败');
    },
  });

  const handleRestore = () => {
    if (selectedVersion !== null) {
      restoreMutation.mutate(selectedVersion);
    }
  };

  const [diffData, setDiffData] = useState<{ old: WikiVersion; new: WikiVersion } | null>(null);
  const [showDiff, setShowDiff] = useState(false);

  const handleCompareClick = (version: number) => {
    if (compareVersion === version) {
      setCompareVersion(null);
      setShowDiff(false);
      setDiffData(null);
    } else if (compareVersion === null) {
      setCompareVersion(version);
    } else {
      // 对比两个版本
      const v1 = versions.find((v: WikiVersion) => v.version === compareVersion);
      const v2 = versions.find((v: WikiVersion) => v.version === version);
      if (v1 && v2) {
        setCompareVersion(null);
        // 使用最早版本作为 old，最新版本作为 new
        const [oldV, newV] = compareVersion < version ? [v1, v2] : [v2, v1];
        setDiffData({ old: oldV, new: newV });
        setShowDiff(true);
      }
    }
  };

  if (pageLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (!pageData) {
    return (
      <Box sx={{ p: 4, textAlign: 'center' }}>
        <Typography variant="h5" color="error">页面不存在</Typography>
        <Button sx={{ mt: 2 }} href="/wiki/kb">返回知识库</Button>
      </Box>
    );
  }

  const versions = Array.isArray(versionsData) ? versionsData : (versionsData as any)?.data || [];

  return (
    <Box sx={{ maxWidth: 900, mx: 'auto', px: 2, py: 4 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => window.location.href = `/wiki/${slug}`}>
        返回文档
      </Button>

      <Typography variant="h4" component="h1" sx={{ mt: 2, mb: 3 }}>
        📜 版本历史 - {pageData.title}
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {success && (
        <Alert severity="success" sx={{ mb: 2 }}>
          已成功回滚到版本 {selectedVersion}
        </Alert>
      )}

      {versionsLoading ? (
        <CircularProgress />
      ) : versions.length === 0 ? (
        <Paper sx={{ p: 4, textAlign: 'center' }}>
          <Typography color="textSecondary">暂无版本记录</Typography>
        </Paper>
      ) : (
        <Box sx={{ mt: 2 }}>
          {versions.map((v: WikiVersion) => (
            <Card key={v.id} sx={{ mb: 2 }}>
              <CardContent>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                  <Box>
                    <Typography variant="h6">版本 {v.version}</Typography>
                    <Typography variant="body2" color="textSecondary">
                      {new Date(v.created_at).toLocaleString('zh-CN')}
                    </Typography>
                    {v.summary && (
                      <Typography variant="body2" sx={{ mt: 1 }}>
                        {v.summary}
                      </Typography>
                    )}
                  </Box>
                  <Box sx={{ display: 'flex', gap: 1 }}>
                    <Button
                      variant={compareVersion === v.version ? 'contained' : 'outlined'}
                      size="small"
                      onClick={() => handleCompareClick(v.version)}
                    >
                      对比
                    </Button>
                    <Button
                      variant="outlined"
                      size="small"
                      startIcon={<RestoreIcon />}
                      onClick={() => {
                        setSelectedVersion(v.version);
                        setOpen(true);
                      }}
                    >
                      回滚
                    </Button>
                  </Box>
                </Box>
                <Divider sx={{ my: 2 }} />
                <Box
                  sx={{
                    maxHeight: 200,
                    overflow: 'auto',
                    background: 'var(--color-bg-tertiary)',
                    padding: 2,
                    borderRadius: 1,
                    fontFamily: 'monospace',
                    fontSize: 12,
                  }}
                >
                  <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{v.content}</pre>
                </Box>
              </CardContent>
            </Card>
          ))}
        </Box>
      )}

      {showDiff && diffData && (
        <Paper sx={{ p: 3, mt: 3 }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
            <Typography variant="h6">版本对比：v{diffData.old.version} → v{diffData.new.version}</Typography>
            <Button size="small" onClick={() => setShowDiff(false)}>关闭对比</Button>
          </Box>
          <VersionDiff
            oldContent={diffData.old.content}
            newContent={diffData.new.content}
            oldLabel={`v${diffData.old.version}`}
            newLabel={`v${diffData.new.version}`}
          />
        </Paper>
      )}

      <Dialog open={open} onClose={() => setOpen(false)}>
        <DialogTitle>确认回滚</DialogTitle>
        <DialogContent>
          <Typography sx={{ mt: 1 }}>
            确定要回滚到版本 {selectedVersion} 吗？
          </Typography>
          <TextField
            label="回滚说明（可选）"
            value={summary}
            onChange={(e) => setSummary(e.target.value)}
            fullWidth
            sx={{ mt: 2 }}
            multiline
            rows={2}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>取消</Button>
          <Button variant="contained" color="primary" onClick={handleRestore} disabled={restoreMutation.isPending}>
            确认回滚
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}