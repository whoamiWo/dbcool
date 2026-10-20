import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
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
import EmptyState from '@/components/EmptyState';

export function KnowledgeBaseListPage() {
  const { t } = useTranslation();
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
      setError(e?.response?.data?.message || t('wiki.createFailed'));
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
      setError(t('wiki.nameRequired'));
      return;
    }
    createMutation.mutate({ name: newName, slug: newSlug, description: newDescription });
  };

  const kbList = Array.isArray(data) ? data : (data as any)?.data || [];

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Typography variant="h4" component="h1">
          {t('wiki.pageTitle')}
        </Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setOpen(true)}
        >
          {t('wiki.newKB')}
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {isLoading ? (
        <Typography>{t('wiki.loading')}</Typography>
      ) : kbList.length === 0 ? (
        <EmptyState
          title={t('wiki.emptyTitle')}
          description={t('wiki.emptyDesc')}
          actionLabel={t('wiki.actionLabel')}
          onAction={() => setOpen(true)}
        />
      ) : (
        <Grid container spacing={2}>
          {kbList.map((kb: KnowledgeBase) => (
            <Grid key={kb.id} size={{ xs: 12, sm: 6, md: 4 }}>
              <Card>
                <CardContent>
                  <Typography variant="h6">{kb.name}</Typography>
                  <Typography variant="body2" color="textSecondary" sx={{ mt: 1 }}>
                    {kb.description || t('wiki.noDesc')}
                  </Typography>
                  <Typography variant="caption" color="textSecondary">
                    {t('wiki.slugLabel')}: {kb.slug}
                  </Typography>
                  <Box sx={{ display: 'flex', gap: 1, mt: 2 }}>
                    <Tooltip title={t('wiki.viewDocs')}>
                      <Button
                        size="small"
                        variant="outlined"
                        onClick={() => navigate(`/wiki/kb/${kb.id}`)}
                      >
                        {t('common.view')}
                      </Button>
                    </Tooltip>
                    <Tooltip title={t('common.edit')}>
                      <IconButton
                        size="small"
                        onClick={() => navigate(`/wiki/kb/${kb.id}/edit`)}
                        aria-label={t('wiki.editHint', { name: kb.name })}
                      >
                        <EditIcon />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title={t('common.delete')}>
                      <IconButton
                        size="small"
                        color="error"
                        onClick={() => deleteMutation.mutate(kb.id)}
                        aria-label={t('wiki.deleteHint', { name: kb.name })}
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
        <DialogTitle>{t('wiki.newKB')}</DialogTitle>
        <DialogContent>
          <Box component="form" sx={{ mt: 2, display: 'flex', flexDirection: 'column', gap: 2 }}>
            <TextField
              label={t('common.name')}
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              fullWidth
              required
            />
            <TextField
              label={t('wiki.slugLabel')}
              value={newSlug}
              onChange={(e) => setNewSlug(e.target.value)}
              fullWidth
              helperText={t('wiki.slugHint')}
              required
            />
            <TextField
              label={t('common.description')}
              value={newDescription}
              onChange={(e) => setNewDescription(e.target.value)}
              multiline
              rows={3}
              fullWidth
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>{t('common.cancel')}</Button>
          <Button variant="contained" onClick={handleCreate} disabled={createMutation.isPending}>
            {t('common.create')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
