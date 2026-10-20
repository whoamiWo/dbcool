import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  Typography,
  Box,
  CircularProgress,
  Alert,
  List,
  ListItemButton,
  ListItemText,
  Divider,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Chip,
} from '@mui/material';
import { useTranslation } from 'react-i18next';
import apiClient from '@/api/client';

/**
 * 记录版本历史弹窗(PHASE125)。
 *
 * <p>展示某条 Collection 记录的变更版本列表,选中某版本可看<strong>字段级差异</strong>
 * (旧值 → 新值),并支持一键回滚到该版本。
 *
 * <p>差异来自后端 {@code changed_fields}(JSON 字符串),前端只做解析与展示,
 * 不自行计算 —— 保证与审计留痕的数据同源。
 */

interface Props {
  collectionName: string;
  recordId: string;
  open: boolean;
  onClose: () => void;
}

interface VersionSummary {
  version: number;
  operation: string | null;
  changedFields: string | null;
  changedBy: string | null;
  changedAt: string | null;
}

interface FieldDiff {
  field: string;
  oldValue: string | null;
  newValue: string | null;
}

/** 解析后端 changed_fields;非法 JSON 或空值时返回空数组(不抛错、不阻断 UI)。 */
function parseDiffs(raw: string | null | undefined): FieldDiff[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as FieldDiff[]) : [];
  } catch {
    return [];
  }
}

export function RecordVersionHistory({
  collectionName,
  recordId,
  open,
  onClose,
}: Props) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['record-versions', collectionName, recordId],
    queryFn: async () => {
      const res = await apiClient.get<{
        code: number;
        data: { versions: VersionSummary[] };
      }>(`/collections/${collectionName}/records/${recordId}/versions`);
      // 兼容 vitest mock 直接返数据 + 真后端 envelope
      return ((res as any)?.data ?? res) as { versions: VersionSummary[] };
    },
    enabled: open && !!collectionName && !!recordId,
  });

  const restoreMutation = useMutation({
    mutationFn: (version: number) =>
      apiClient.post(
        `/collections/${collectionName}/records/${recordId}/versions/${version}/restore`,
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['record-versions', collectionName, recordId],
      });
      queryClient.invalidateQueries({
        queryKey: ['collection-records', collectionName],
      });
      setSelected(null);
      onClose();
    },
    onError: (e: any) => {
      setError(e?.response?.data?.message ?? t('recordVersion.restoreFailed'));
    },
  });

  const versions = data?.versions ?? [];
  const current = versions.find((v) => v.version === selected) ?? null;
  const diffs = parseDiffs(current?.changedFields);

  const handleClose = () => {
    setSelected(null);
    setError(null);
    onClose();
  };

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="md" fullWidth>
      <DialogTitle>
        {t('recordVersion.title')} — {collectionName}
      </DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        {isLoading ? (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
            <CircularProgress />
          </Box>
        ) : versions.length === 0 ? (
          <Typography color="text.secondary" sx={{ py: 3 }}>
            {t('recordVersion.empty')}
          </Typography>
        ) : (
          <Box sx={{ display: 'flex', gap: 2, minHeight: 280 }}>
            {/* 左:版本列表 */}
            <Box sx={{ width: 220, flexShrink: 0, overflowY: 'auto' }}>
              <List dense disablePadding>
                {versions.map((v) => (
                  <ListItemButton
                    key={v.version}
                    selected={selected === v.version}
                    onClick={() => setSelected(v.version)}
                  >
                    <ListItemText
                      primary={`${t('recordVersion.version')} ${v.version}`}
                      secondary={
                        <span>
                          <Chip
                            size="small"
                            label={v.operation ?? '—'}
                            sx={{ mr: 0.5, height: 18, fontSize: 10 }}
                          />
                          <br />
                          {v.changedAt
                            ? String(v.changedAt).replace('T', ' ').slice(0, 19)
                            : '—'}
                        </span>
                      }
                    />
                  </ListItemButton>
                ))}
              </List>
            </Box>

            <Divider orientation="vertical" flexItem />

            {/* 右:字段级差异 */}
            <Box sx={{ flex: 1, overflowY: 'auto' }}>
              {!current ? (
                <Typography color="text.secondary">
                  {t('recordVersion.selectHint')}
                </Typography>
              ) : diffs.length === 0 ? (
                <Typography color="text.secondary">
                  {t('recordVersion.noDiff')}
                </Typography>
              ) : (
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>{t('recordVersion.field')}</TableCell>
                      <TableCell>{t('recordVersion.oldValue')}</TableCell>
                      <TableCell>{t('recordVersion.newValue')}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {diffs.map((d) => (
                      <TableRow key={d.field} hover>
                        <TableCell sx={{ fontFamily: 'monospace', fontSize: 12 }}>
                          {d.field}
                        </TableCell>
                        <TableCell
                          sx={{
                            fontSize: 12,
                            color: 'var(--color-error)',
                            textDecoration: 'line-through',
                          }}
                        >
                          {d.oldValue ?? '—'}
                        </TableCell>
                        <TableCell
                          sx={{ fontSize: 12, color: 'var(--color-success)' }}
                        >
                          {d.newValue ?? '—'}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </Box>
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>{t('common.cancel')}</Button>
        <Button
          variant="contained"
          disabled={selected === null || restoreMutation.isPending}
          onClick={() => selected !== null && restoreMutation.mutate(selected)}
        >
          {t('recordVersion.restore')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
