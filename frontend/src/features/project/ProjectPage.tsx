import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Box, Button, Card, Stack, Tab, Tabs, TextField, Typography,
  Dialog, DialogTitle, DialogContent, DialogActions, CircularProgress, Alert,
} from '@mui/material';
import { useTranslation } from 'react-i18next';
import TaskBoard from './TaskBoard';
import GanttView from './GanttView';
import { GanttLegend } from './GanttView';
import { BoardView } from './BoardView';
import { projectApi } from './api';

/**
 * 项目协同页 — 看板 (Trello 对标) 与甘特图切换。
 * 支持项目列表展示、创建新项目、选择项目查看任务。
 */
export default function ProjectPage() {
  const [params, setParams] = useSearchParams();
  const [tab, setTab] = useState(0);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState({ name: '', description: '' });
  const { t } = useTranslation();

  const queryClient = useQueryClient();

  // 从 URL 取 projectId，若为空则显示项目列表
  const selectedProjectId = params.get('projectId') ?? '';

  const { data: projects, isLoading: loadingProjects } = useQuery({
    queryKey: ['projects'],
    queryFn: projectApi.listProjects,
    retry: 2,
  });

  const createMut = useMutation({
    mutationFn: (body: Record<string, unknown>) => projectApi.createProject(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] });
      setDialogOpen(false);
      setForm({ name: '', description: '' });
    },
  });

  const handleCreate = () => {
    if (!form.name.trim()) return;
    createMut.mutate({
      name: form.name,
      description: form.description || undefined,
    });
  };

  const selectProject = (id: string) => {
    setParams({ projectId: id });
  };

  if (loadingProjects) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
        <CircularProgress sx={{ color: 'primary.main' }} />
      </Box>
    );
  }

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 2 }}>
        <Typography variant="h5" sx={{ fontWeight: 600 }}>
          🗂️ {t('project.title')}
        </Typography>
        <Button
          variant="contained"
          size="small"
          onClick={() => setDialogOpen(true)}
          sx={{ bgcolor: 'primary.main', '&:hover': { bgcolor: 'primary.dark' } }}
        >
          {t('project.newProject')}
        </Button>
      </Stack>

      {/* 项目列表 */}
      {!selectedProjectId && (
        <Box>
          <Typography variant="subtitle1" sx={{ fontWeight: 500, mb: 2 }}>
            {t('project.myProjects')}
          </Typography>
          
          {projects && projects.length === 0 ? (
            <Alert severity="info" sx={{ bgcolor: 'var(--color-info)', border: '1px solid var(--color-info)' }}>
              {t('project.emptyProjects')}
            </Alert>
          ) : (
            <Stack spacing={2}>
              {projects?.map((p) => (
                <Card
                  key={p.id}
                  className="glass-card glass-card-hover"
                  onClick={() => selectProject(p.id)}
                  sx={{
                    cursor: 'pointer',
                    p: 2,
                    border: '1px solid var(--color-border-light)',
                    borderRadius: 2,
                    background: 'var(--color-border-light)',
                    backdropFilter: 'blur(8px)',
                    transition: 'all 0.2s ease',
                    '&:hover': {
                      boxShadow: 'var(--shadow-lg)',
                      borderColor: 'var(--color-border-medium)',
                      transform: 'translateY(-2px)',
                    },
                  }}
                >
                  <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
                    <Box
                      sx={{
                        width: 12,
                        height: 12,
                        borderRadius: '50%',
                        bgcolor: 'primary.main',
                        boxShadow: '0 0 12px var(--color-primary-500)',
                      }}
                    />
                    <Box sx={{ flex: 1 }}>
                      <Typography variant="body1" sx={{ fontWeight: 600 }}>
                        {p.name}
                      </Typography>
                      {p.description && (
                        <Typography variant="body2" color="text.secondary">
                          {p.description}
                        </Typography>
                      )}
                    </Box>
                    <Typography variant="caption" color="text.secondary">
                      {new Date(p.createdAt).toLocaleDateString()}
                    </Typography>
                  </Stack>
                </Card>
              ))}
            </Stack>
          )}
        </Box>
      )}

      {/* 项目详情 */}
      {selectedProjectId && (
        <>
          <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
            <Button
              size="small"
              onClick={() => setParams({})}
              sx={{ mr: 2, color: 'text.secondary' }}
            >
              {t('project.backToList')}
            </Button>
            <Typography variant="body2" color="text.secondary">
              {projects?.find((p) => p.id === selectedProjectId)?.name ?? selectedProjectId}
            </Typography>
          </Box>

          <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
            <Tab label="Trello 看板" />
            <Tab label="简易看板" />
            <Tab label="甘特图" />
          </Tabs>

          {tab === 0 && <BoardView projectId={selectedProjectId} />}
          {tab === 1 && <TaskBoard projectId={selectedProjectId} />}
          {tab === 2 && (
            <Box>
              <GanttLegend />
              <GanttView projectId={selectedProjectId} />
            </Box>
          )}
        </>
      )}

      {/* 新建项目对话框 */}
      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{t('project.newProject')}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              autoFocus
              label={t('project.projectName')}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder={t('project.projectNamePlaceholder')}
              helperText={t('project.required')}
            />
            <TextField
              label={t('project.projectDesc')}
              multiline
              rows={3}
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder={t('project.projectDescPlaceholder')}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>{t('common.cancel')}</Button>
          <Button
            variant="contained"
            onClick={handleCreate}
            disabled={!form.name.trim() || createMut.isPending}
            sx={{ bgcolor: 'primary.main', '&:hover': { bgcolor: 'primary.dark' } }}
          >
            {createMut.isPending ? <CircularProgress size={20} /> : t('project.create')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
