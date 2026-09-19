import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import {
  Box, Button, Card, CardActionArea, CardContent, Chip, Dialog, DialogActions,
  DialogContent, DialogTitle, Grid, TextField, Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import apiClient from '@/api/client';

export interface Playbook {
  id: string;
  name: string;
  description?: string;
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
  channelId?: string;
  createdAt: string;
}

const STATUS_FILTERS = [
  { key: 'ALL', label: '全部' },
  { key: 'ACTIVE', label: '已激活' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'ARCHIVED', label: '已归档' },
] as const;

const STATUS_COLOR: Record<string, 'success' | 'default' | 'warning'> = {
  ACTIVE: 'success',
  DRAFT: 'default',
  ARCHIVED: 'warning',
};

export function PlaybookListPage() {
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
          剧本 Playbooks
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreateOpen(true)}>
          新建剧本
        </Button>
      </Box>

      <Box sx={{ display: 'flex', gap: 1, mb: 2 }}>
        {STATUS_FILTERS.map((s) => (
          <Chip
            key={s.key}
            label={s.label}
            color={filter === s.key ? 'primary' : 'default'}
            variant={filter === s.key ? 'filled' : 'outlined'}
            onClick={() => setFilter(s.key)}
          />
        ))}
      </Box>

      {isLoading ? (
        <Typography color="text.secondary">加载中…</Typography>
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
                      {pb.description || '无描述'}
                    </Typography>
                    <Typography variant="caption" color="text.disabled">
                      创建于 {new Date(pb.createdAt).toLocaleString('zh-CN')}
                    </Typography>
                  </CardContent>
                </CardActionArea>
              </Card>
            </Grid>
          ))}
          {list.length === 0 && (
            <Grid size={{ xs: 12 }}>
              <Typography color="text.secondary" sx={{ py: 6, textAlign: 'center' }}>
                暂无剧本,点击右上角「新建剧本」开始
              </Typography>
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
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  const submit = () => {
    if (!name.trim()) return;
    onSubmit({
      name: name.trim(),
      description: description.trim(),
      yamlSource: JSON.stringify([
        { id: 'start', type: 'NOTIFICATION', config: { message: '剧本已启动' } },
      ]),
    });
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth aria-label="新建剧本">
      <DialogTitle>新建剧本</DialogTitle>
      <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
        <TextField
          label="名称"
          value={name}
          onChange={(e) => setName(e.currentTarget.value)}
          fullWidth
          autoFocus
        />
        <TextField
          label="描述"
          value={description}
          onChange={(e) => setDescription(e.currentTarget.value)}
          fullWidth
          multiline
          minRows={2}
        />
        <Typography variant="caption" color="text.secondary">
          创建后可编辑节点定义 JSON(含 nodes / edges / checklist)
        </Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>取消</Button>
        <Button variant="contained" onClick={submit} disabled={submitting || !name.trim()}>
          创建
        </Button>
      </DialogActions>
    </Dialog>
  );
}
