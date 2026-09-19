import { useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useParams, Link } from 'react-router-dom';
import {
  Box, Button, Card, CardContent, Checkbox, Chip, CircularProgress, Divider,
  Grid, List, ListItem, ListItemIcon, ListItemText, Typography,
} from '@mui/material';
import PlayIcon from '@mui/icons-material/PlayArrow';
import DoneIcon from '@mui/icons-material/DoneAll';
import EventIcon from '@mui/icons-material/Bolt';
import apiClient from '@/api/client';
import type { Playbook } from './PlaybookList';

export interface PlaybookRun {
  id: string;
  playbookId: string;
  channelId?: string;
  instanceId?: string;
  status: 'RUNNING' | 'FINISHED' | 'OVERDUE';
  dueAt?: string;
  checklistJson: string;
  eventsJson: string;
  retrospectivePageId?: string;
  startedAt: string;
  finishedAt?: string;
}

interface ChecklistItem {
  title: string;
  done: boolean;
}

interface RunEvent {
  at: string;
  event: string;
  detail: string;
}

function slaChip(run?: PlaybookRun) {
  if (!run) return null;
  if (run.status === 'FINISHED') return <Chip size="small" color="success" label="已完成" />;
  if (run.status === 'OVERDUE') return <Chip size="small" color="error" label="SLA 已逾期" />;
  if (run.dueAt) {
    const remainMs = new Date(run.dueAt).getTime() - Date.now();
    if (remainMs < 0) return <Chip size="small" color="error" label="SLA 已逾期" />;
    if (remainMs < 3600_000) {
      return <Chip size="small" color="warning" label={`即将到期 ${Math.floor(remainMs / 60000)} 分钟`} />;
    }
    return <Chip size="small" color="info" label={`剩余 ${Math.floor(remainMs / 3600000)} 小时`} />;
  }
  return <Chip size="small" label="运行中" />;
}

export function PlaybookRunPage() {
  const { id = '' } = useParams();
  const queryClient = useQueryClient();
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);

  const { data: playbookResp } = useQuery({
    queryKey: ['playbook', id],
    queryFn: () => apiClient.get<{ code: number; data: Playbook }>(`/playbooks/${id}`),
  });

  const { data: runsResp, isLoading } = useQuery({
    queryKey: ['playbook-runs', id],
    queryFn: () =>
      apiClient.get<{ code: number; data: PlaybookRun[] }>(`/playbooks/${id}/runs`),
  });

  const playbook = playbookResp?.data;
  const runs = runsResp?.data ?? [];
  const run = runs.find((r) => r.id === (selectedRunId ?? runs[0]?.id));

  const checklist = useMemo<ChecklistItem[]>(() => {
    if (!run) return [];
    try {
      return JSON.parse(run.checklistJson || '[]');
    } catch {
      return [];
    }
  }, [run?.checklistJson]);

  const events = useMemo<RunEvent[]>(() => {
    if (!run) return [];
    try {
      return JSON.parse(run.eventsJson || '[]');
    } catch {
      return [];
    }
  }, [run?.eventsJson]);

  const doneCount = checklist.filter((c) => c.done).length;
  const progress = checklist.length ? Math.round((doneCount / checklist.length) * 100) : 0;

  const runMutation = useMutation({
    mutationFn: () => apiClient.post<{ code: number; data: PlaybookRun }>(`/playbooks/${id}/run`, {}),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['playbook-runs', id] }),
  });

  const checkMutation = useMutation({
    mutationFn: (vars: { index: number; done: boolean }) =>
      apiClient.put<{ code: number; data: PlaybookRun }>(
        `/playbooks/runs/${run?.id}/checklist`, vars,
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['playbook-runs', id] }),
  });

  const finishMutation = useMutation({
    mutationFn: () =>
      apiClient.post<{ code: number; data: PlaybookRun }>(`/playbooks/runs/${run?.id}/finish`, {}),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['playbook-runs', id] }),
  });

  return (
    <Box sx={{ maxWidth: 1080, mx: 'auto', p: 3 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2 }}>
        <Typography variant="h5" sx={{ fontWeight: 600, flexGrow: 1 }}>
          {playbook?.name ?? '剧本详情'}
        </Typography>
        {slaChip(run)}
        <Button
          variant="contained"
          startIcon={runMutation.isPending ? <CircularProgress size={16} color="inherit" /> : <PlayIcon />}
          onClick={() => runMutation.mutate()}
          disabled={runMutation.isPending}
        >
          运行
        </Button>
        <Button
          variant="outlined"
          color="success"
          startIcon={<DoneIcon />}
          onClick={() => finishMutation.mutate()}
          disabled={finishMutation.isPending || !run || run.status === 'FINISHED'}
        >
          完成并复盘
        </Button>
      </Box>

      {run?.retrospectivePageId && (
        <Chip
          color="success"
          variant="outlined"
          label="复盘页已生成"
          sx={{ mb: 2 }}
          component={Link}
          to="/wiki/kb"
          clickable
        />
      )}

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 7 }}>
          <Card variant="outlined" sx={{ borderRadius: 2 }}>
            <CardContent>
              <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                <Typography variant="subtitle1" sx={{ fontWeight: 600, flexGrow: 1 }}>
                  Checklist({doneCount}/{checklist.length})
                </Typography>
                <Typography variant="body2" color="primary" sx={{ fontWeight: 600 }}>
                  {progress}%
                </Typography>
              </Box>
              {checklist.length === 0 ? (
                <Typography color="text.secondary" sx={{ py: 3, textAlign: 'center' }}>
                  {isLoading ? '加载中…' : '该剧本暂无 Checklist 项,可在定义中补充 checklist 数组'}
                </Typography>
              ) : (
                <List dense>
                  {checklist.map((item, i) => (
                    <ListItem
                      key={i}
                      secondaryAction={
                        <Checkbox
                          edge="end"
                          checked={item.done}
                          onChange={(e) =>
                            checkMutation.mutate({ index: i, done: e.currentTarget.checked })
                          }
                          disabled={checkMutation.isPending}
                        />
                      }
                      sx={{
                        borderRadius: 1,
                        '&:hover': { bgcolor: 'action.hover' },
                      }}
                    >
                      <ListItemIcon sx={{ minWidth: 32 }}>
                        <DoneIcon
                          fontSize="small"
                          color={item.done ? 'success' : 'disabled'}
                        />
                      </ListItemIcon>
                      <ListItemText
                        primary={item.title}
                        slotProps={{
                          primary: {
                            sx: item.done
                              ? { textDecoration: 'line-through', color: 'text.disabled' }
                              : undefined,
                          },
                        }}
                      />
                    </ListItem>
                  ))}
                </List>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 12, md: 5 }}>
          <Card variant="outlined" sx={{ borderRadius: 2 }}>
            <CardContent>
              <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 1 }}>
                运行信息
              </Typography>
              {run ? (
                <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, mb: 2 }}>
                  <Typography variant="body2" color="text.secondary">
                    状态: {run.status}
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    开始: {new Date(run.startedAt).toLocaleString('zh-CN')}
                  </Typography>
                  {run.finishedAt && (
                    <Typography variant="body2" color="text.secondary">
                      结束: {new Date(run.finishedAt).toLocaleString('zh-CN')}
                    </Typography>
                  )}
                  {run.dueAt && (
                    <Typography variant="body2" color="text.secondary">
                      SLA 到期: {new Date(run.dueAt).toLocaleString('zh-CN')}
                    </Typography>
                  )}
                </Box>
              ) : (
                <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                  尚未运行,点击右上角「运行」发起第一次执行
                </Typography>
              )}

              <Divider sx={{ my: 1.5 }} />
              <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
                事件流
              </Typography>
              {events.length === 0 ? (
                <Typography variant="body2" color="text.secondary">
                  暂无事件
                </Typography>
              ) : (
                events.map((e, i) => (
                  <Box key={i} sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', py: 0.5 }}>
                    <EventIcon fontSize="small" color="action" sx={{ mt: 0.3 }} />
                    <Box>
                      <Typography variant="caption" color="text.secondary">
                        {new Date(e.at).toLocaleString('zh-CN')} · {e.event}
                      </Typography>
                      <Typography variant="body2">{e.detail}</Typography>
                    </Box>
                  </Box>
                ))
              )}
            </CardContent>
          </Card>

          {runs.length > 1 && (
            <Card variant="outlined" sx={{ borderRadius: 2, mt: 2 }}>
              <CardContent>
                <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
                  历史运行({runs.length})
                </Typography>
                {runs.map((r) => (
                  <Chip
                    key={r.id}
                    size="small"
                    sx={{ m: 0.25 }}
                    label={`${new Date(r.startedAt).toLocaleDateString('zh-CN')} · ${r.status}`}
                    color={r.id === run?.id ? 'primary' : 'default'}
                    variant={r.id === run?.id ? 'filled' : 'outlined'}
                    onClick={() => setSelectedRunId(r.id)}
                  />
                ))}
              </CardContent>
            </Card>
          )}
        </Grid>
      </Grid>
    </Box>
  );
}
