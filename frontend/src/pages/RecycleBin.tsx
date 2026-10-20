import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Box,
  Button,
  Typography,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  CircularProgress,
  Alert,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
} from '@mui/material';
import { useTranslation } from 'react-i18next';
import apiClient from '@/api/client';

/**
 * 回收站页(PHASE125)—— 展示某 collection 已软删的记录,支持恢复 / 彻底删除 / 清空。
 *
 * <p>与 Wiki 版本页保持一致的交互语言:React Query 拉取 + 失效刷新,危险操作二次确认,
 * 加载态 CircularProgress,错误态 Alert。
 */
export function RecycleBinPage() {
  const { t } = useTranslation();
  const { name = '' } = useParams<{ name: string }>();
  const queryClient = useQueryClient();
  const [confirmClear, setConfirmClear] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['recycle-bin', name],
    queryFn: async () => {
      const res = await apiClient.get<{
        code: number;
        data: { records: Array<Record<string, unknown>> };
      }>(`/collections/${name}/recycle`);
      // 兼容 vitest mock 直接返数据 + 真后端 envelope
      return ((res as any)?.data ?? res) as { records: Array<Record<string, unknown>> };
    },
    enabled: !!name,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['recycle-bin', name] });
    // 恢复后业务列表需要重新拉取(记录重新出现在列表中)
    queryClient.invalidateQueries({ queryKey: ['collection-records', name] });
  };

  const restoreMutation = useMutation({
    mutationFn: (id: string) =>
      apiClient.post(`/collections/${name}/recycle/${id}/restore`),
    onSuccess: invalidate,
    onError: (e: any) => {
      setError(e?.response?.data?.message ?? t('recycleBin.restoreFailed'));
    },
  });

  const purgeMutation = useMutation({
    mutationFn: (id: string) => apiClient.delete(`/collections/${name}/recycle/${id}`),
    onSuccess: invalidate,
    onError: (e: any) => {
      setError(e?.response?.data?.message ?? t('recycleBin.purgeFailed'));
    },
  });

  const clearMutation = useMutation({
    mutationFn: () => apiClient.delete(`/collections/${name}/recycle`),
    onSuccess: () => {
      invalidate();
      setConfirmClear(false);
    },
    onError: (e: any) => {
      setError(e?.response?.data?.message ?? t('recycleBin.clearFailed'));
    },
  });

  const records = data?.records ?? [];

  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box sx={{ maxWidth: 1080, mx: 'auto', px: 2, py: 3 }}>
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          mb: 2,
        }}
      >
        <Box>
          <Typography variant="h5" sx={{ fontWeight: 600 }}>
            🗑️ {t('recycleBin.title')}
          </Typography>
          <Link
            to={`/designer/collections/${name}`}
            style={{ fontSize: 13, color: 'var(--color-text-muted)' }}
          >
            ← {t('recycleBin.backTo')} {name}
          </Link>
        </Box>
        {records.length > 0 && (
          <Button
            variant="outlined"
            color="error"
            onClick={() => setConfirmClear(true)}
            disabled={clearMutation.isPending}
          >
            {t('recycleBin.clear')}
          </Button>
        )}
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {records.length === 0 ? (
        <Paper sx={{ p: 4, textAlign: 'center' }}>
          <Typography color="text.secondary">{t('recycleBin.empty')}</Typography>
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>{t('recycleBin.colId')}</TableCell>
                <TableCell>{t('recycleBin.colData')}</TableCell>
                <TableCell align="right">{t('recycleBin.colActions')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {records.map((rec) => {
                const id = String(rec.id ?? '');
                // 摘要:去掉 id 后展示前 3 个字段,避免整行超长 JSON
                const rest = Object.entries(rec)
                  .filter(([k]) => k !== 'id')
                  .slice(0, 3)
                  .map(([k, v]) => `${k}=${String(v)}`)
                  .join(', ');
                return (
                  <TableRow key={id} hover>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>
                      {id || '—'}
                    </TableCell>
                    <TableCell sx={{ fontSize: 12, color: 'text.secondary' }}>
                      {rest || '—'}
                    </TableCell>
                    <TableCell align="right">
                      <Button
                        size="small"
                        onClick={() => restoreMutation.mutate(id)}
                        disabled={restoreMutation.isPending}
                        sx={{ mr: 1 }}
                      >
                        {t('recycleBin.restore')}
                      </Button>
                      <Button
                        size="small"
                        color="error"
                        onClick={() => purgeMutation.mutate(id)}
                        disabled={purgeMutation.isPending}
                      >
                        {t('recycleBin.purge')}
                      </Button>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/* 清空二次确认 */}
      <Dialog open={confirmClear} onClose={() => setConfirmClear(false)}>
        <DialogTitle>{t('recycleBin.clearConfirmTitle')}</DialogTitle>
        <DialogContent>
          <Typography>{t('recycleBin.clearConfirmMsg')}</Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmClear(false)}>
            {t('common.cancel')}
          </Button>
          <Button
            color="error"
            variant="contained"
            onClick={() => clearMutation.mutate()}
            disabled={clearMutation.isPending}
          >
            {t('recycleBin.clear')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
