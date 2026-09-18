import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Box,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  Typography,
  Grid,
  IconButton,
  Tooltip,
  Alert,
} from '@mui/material';
import { Add as AddIcon, Delete as DeleteIcon, Edit as EditIcon } from '@mui/icons-material';
import { wikiApi } from '@/api/wiki';
import type { KnowledgeBase } from '@/types/wiki';

export function KnowledgeBaseListPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [newName, setNewName] = useState('');
  const [newSlug, setNewSlug] = useState('');
  const [newDescription, setNewDescription] = useState('');
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['wiki-kb'],
    queryFn: () => wikiApi.listKb(),
  });

  const createMutation = useMutation({
    mutationFn: (body: { name: string; slug: string; description?: string }) =>
      wikiApi.createKb(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-kb'] });
      setOpen(false);
      setNewName('');
      setNewSlug('');
      setNewDescription('');
      setError(null);
    },
    onError: (e: any) => {
      setError(e?.response?.data?.message || '创建失败');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => wikiApi.deleteKb(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-kb'] });
    },
  });

  const handleCreate = () => {
    if (!newName.trim() || !newSlug.trim()) {
      setError('名称和 slug 必填');
      return;
    }
    createMutation.mutate({ name: newName, slug: newSlug, description: newDescription });
  };

  const kbList = Array.isArray(data) ? data : (data as any)?.data || [];

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Typography variant="h4" component="h1">
          📚 知识库
        </Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setOpen(true)}
        >
          新建知识库
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {isLoading ? (
        <Typography>加载中...</Typography>
      ) : kbList.length === 0 ? (
        <Card>
          <CardContent>
            <Typography color="textSecondary">暂无知识库，点击上方按钮创建</Typography>
          </CardContent>
        </Card>
      ) : (
        <Grid container spacing={2}>
          {kbList.map((kb: KnowledgeBase) => (
            <Grid key={kb.id} size={{ xs: 12, sm: 6, md: 4 }}>
              <Card>
                <CardContent>
                  <Typography variant="h6">{kb.name}</Typography>
                  <Typography variant="body2" color="textSecondary" sx={{ mt: 1 }}>
                    {kb.description || '暂无描述'}
                  </Typography>
                  <Typography variant="caption" color="textSecondary">
                    Slug: {kb.slug}
                  </Typography>
                  <Box sx={{ display: 'flex', gap: 1, mt: 2 }}>
                    <Tooltip title="查看文档">
                      <Button
                        size="small"
                        variant="outlined"
                        onClick={() => navigate(`/wiki/kb/${kb.id}`)}
                      >
                        查看
                      </Button>
                    </Tooltip>
                    <Tooltip title="编辑">
                      <IconButton size="small" onClick={() => navigate(`/wiki/kb/${kb.id}/edit`)}>
                        <EditIcon />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title="删除">
                      <IconButton
                        size="small"
                        color="error"
                        onClick={() => deleteMutation.mutate(kb.id)}
                      >
                        <DeleteIcon />
                      </IconButton>
                    </Tooltip>
                  </Box>
                </CardContent>
              </Card>
            </Grid>
          ))}
        </Grid>
      )}

      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>新建知识库</DialogTitle>
        <DialogContent>
          <Box component="form" sx={{ mt: 2, display: 'flex', flexDirection: 'column', gap: 2 }}>
            <TextField
              label="名称"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              fullWidth
              required
            />
            <TextField
              label="Slug"
              value={newSlug}
              onChange={(e) => setNewSlug(e.target.value)}
              fullWidth
              helperText="唯一标识，如：my-wiki"
              required
            />
            <TextField
              label="描述"
              value={newDescription}
              onChange={(e) => setNewDescription(e.target.value)}
              multiline
              rows={3}
              fullWidth
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>取消</Button>
          <Button variant="contained" onClick={handleCreate} disabled={createMutation.isPending}>
            创建
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}