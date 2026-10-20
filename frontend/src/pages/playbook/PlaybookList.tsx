import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import {
  Box, Button, Card, CardActionArea, CardContent, Chip, Dialog, DialogActions,
  DialogContent, DialogTitle, Grid, TextField, Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import apiClient from '@/api/client';
import EmptyState from '@/components/EmptyState';

export interface Playbook {
  id: string;
  name: string;
  description?: string;
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
  channelId?: string;
  createdAt: string;
}

/** PHASE118 i18n：模块级常量改存 key，在组件内用 t() 取值（hook 不能在组件外调用） */
const STATUS_FILTERS = [
  { key: 'ALL', labelKey: 'playbookList.filterAll' },
  { key: 'ACTIVE', labelKey: 'playbookList.filterActive' },
  { key: 'DRAFT', labelKey: 'playbookList.filterDraft' },
  { key: 'ARCHIVED', labelKey: 'playbookList.filterArchived' },
] as const;

const STATUS_COLOR: Record<string, 'success' | 'default' | 'warning'> = {
  ACTIVE: 'success',
  DRAFT: 'default',
  ARCHIVED: 'warning',
};

export function PlaybookListPage() {
  const { t, i18n } = useTranslation();
  const [filter, setFilter] = useState('ALL');
  const [createOpen, setCreateOpen] = useState(false);
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const { data, isLoading } = useQuery({
    queryKey: ['playbooks', filter],
    queryFn: () =>
      apiClient.get<{ code: number; data: Playbook[] }>(
        `/playbooks?status=${filter === 'ALL' ? '' : filter}`,
      ),
  });

  const createMutation = useMutation({
    mutationFn: (body: { name: string; description: string; yamlSource: string }) =>
      apiClient.post<{ code: number; data: Playbook }>('/playbooks', body),
    onSuccess: (resp) => {
      setCreateOpen(false);
      queryClient.invalidateQueries({ queryKey: ['playbooks'] });
      if (resp.data?.id) navigate(`/playbooks/${resp.data.id}`);
    },
  });

  const list = data?.data ?? [];

  return (
    <Box sx={{ maxWidth: 1080, mx: 'auto', p: 3 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h5" sx={{ fontWeight: 600 }}>
          {t('playbookList.title')}
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreateOpen(true)}>
          {t('playbookList.newPlaybook')}
        </Button>
      </Box>

      <Box sx={{ display: 'flex', gap: 1, mb: 2 }}>
        {STATUS_FILTERS.map((s) => (
          <Chip
            key={s.key}
            label={t(s.labelKey)}
            color={filter === s.key ? 'primary' : 'default'}
            variant={filter === s.key ? 'filled' : 'outlined'}
            onClick={() => setFilter(s.key)}
          />
        ))}
      </Box>

      {isLoading ? (
        <Typography color="text.secondary">{t('playbookList.loading')}</Typography>
      ) : (
        <Grid container spacing={2}>
          {list.map((pb) => (
            <Grid key={pb.id} size={{ xs: 12, sm: 6, md: 4 }}>
              <Card variant="outlined" sx={{ borderRadius: 2, '&:hover': { boxShadow: 2 } }}>
                <CardActionArea component={Link} to={`/playbooks/${pb.id}`}>
                  <CardContent>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                      <Typography variant="subtitle1" sx={{ fontWeight: 600, flexGrow: 1 }} noWrap>
                        {pb.name}
                      </Typography>
                      <Chip
                        size="small"
                        label={pb.status}
                        color={STATUS_COLOR[pb.status] ?? 'default'}
                      />
                    </Box>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5, minHeight: 40 }}>
                      {pb.description || t('playbookList.noDescription')}
                    </Typography>
                    <Typography variant="caption" color="text.disabled">
                      {t('playbookList.createdAt', { date: new Date(pb.createdAt).toLocaleString(i18n.language) })}
                    </Typography>
                  </CardContent>
                </CardActionArea>
              </Card>
            </Grid>
          ))}
          {list.length === 0 && (
            <Grid size={{ xs: 12 }}>
              <EmptyState
                title={t('playbookList.emptyTitle')}
                description={t('playbookList.emptyDesc')}
                actionLabel={t('playbookList.newPlaybook')}
                onAction={() => setCreateOpen(true)}
              />
            </Grid>
          )}
        </Grid>
      )}

      <CreatePlaybookDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onSubmit={(body) => createMutation.mutate(body)}
        submitting={createMutation.isPending}
      />
    </Box>
  );
}

function CreatePlaybookDialog({
  open, onClose, onSubmit, submitting,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (body: { name: string; description: string; yamlSource: string }) => void;
  submitting: boolean;
}) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  const submit = () => {
    if (!name.trim()) return;
    onSubmit({
      name: name.trim(),
      description: description.trim(),
      // 注意：此处的 message 是提交给后端的节点配置数据（非 UI 文案），保留原文不翻译
      yamlSource: JSON.stringify([
        { id: 'start', type: 'NOTIFICATION', config: { message: '剧本已启动' } },
      ]),
    });
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth aria-label={t('playbookList.newPlaybook')}>
      <DialogTitle>{t('playbookList.newPlaybook')}</DialogTitle>
      <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
        <TextField
          label={t('playbookList.nameLabel')}
          value={name}
          onChange={(e) => setName(e.currentTarget.value)}
          fullWidth
          // eslint-disable-next-line jsx-a11y/no-autofocus -- 弹窗/面板内首个输入框自动聚焦，符合交互预期
          autoFocus
        />
        <TextField
          label={t('playbookList.descLabel')}
          value={description}
          onChange={(e) => setDescription(e.currentTarget.value)}
          fullWidth
          multiline
          minRows={2}
        />
        <Typography variant="caption" color="text.secondary">
          {t('playbookList.editHint')}
        </Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('common.cancel')}</Button>
        <Button variant="contained" onClick={submit} disabled={submitting || !name.trim()}>
          {t('common.create')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
