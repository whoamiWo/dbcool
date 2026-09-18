import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Box, Card, CardContent, Chip, CircularProgress, Grid, MenuItem,
  Select, Stack, Typography, Alert,
} from '@mui/material';
import { projectApi, type Task, type TaskStatus } from './api';

const COLUMNS: Array<{ key: TaskStatus; label: string; color: string }> = [
  { key: 'TODO', label: '待办', color: '#9E9E9E' },
  { key: 'IN_PROGRESS', label: '进行中', color: '#1976D2' },
  { key: 'BLOCKED', label: '阻塞', color: '#F44336' },
  { key: 'DONE', label: '已完成', color: '#4CAF50' },
];

const PRIORITY_COLOR: Record<string, string> = {
  LOW: '#9E9E9E', MEDIUM: '#1976D2', HIGH: '#FF9800', CRITICAL: '#F44336',
};

interface Props { projectId: string }

/** 任务看板 — 按状态分列,可下拉改状态(对标 Trello)。 */
export default function TaskBoard({ projectId }: Props) {
  const queryClient = useQueryClient();

  const { data: tasks, isLoading, isError, error } = useQuery({
    queryKey: ['project-tasks', projectId],
    queryFn: () => projectApi.listTasks(projectId),
    enabled: !!projectId,
  });

  const updateMut = useMutation({
    mutationFn: ({ id, status }: { id: string; status: TaskStatus }) =>
      projectApi.updateTask(id, { status }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['project-tasks', projectId] }),
  });

  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (isError) {
    return (
      <Alert severity="error">加载任务失败:{(error as Error)?.message}</Alert>
    );
  }

  const byStatus = (status: TaskStatus) =>
    (tasks ?? []).filter((t) => t.status === status);

  return (
    <Grid container spacing={2}>
      {COLUMNS.map((col) => (
        <Grid key={col.key} size={{ xs: 12, md: 3 }}>
          <Card sx={{ height: '100%', borderTop: `3px solid ${col.color}` }}>
            <CardContent>
              <Stack direction="row" spacing={1} sx={{ mb: 2, alignItems: 'center' }}>
                <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
                  {col.label}
                </Typography>
                <Chip size="small" label={byStatus(col.key).length} />
              </Stack>
              <Stack spacing={1.5}>
                {byStatus(col.key).map((task) => (
                  <TaskCard
                    key={task.id}
                    task={task}
                    onStatusChange={(status) => updateMut.mutate({ id: task.id, status })}
                  />
                ))}
                {byStatus(col.key).length === 0 && (
                  <Typography variant="body2" color="text.secondary">
                    暂无任务
                  </Typography>
                )}
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      ))}
    </Grid>
  );
}

function TaskCard({ task, onStatusChange }: { task: Task; onStatusChange: (s: TaskStatus) => void }) {
  return (
    <Box sx={{ p: 1.5, border: '1px solid #e0e0e0', borderRadius: 1 }}>
      <Typography variant="body2" sx={{ fontWeight: 500, mb: 1 }}>
        {task.title}
      </Typography>
      <Stack direction="row" spacing={1} sx={{ mb: 1, alignItems: 'center', flexWrap: 'wrap' }}>
        <Chip
          size="small"
          label={task.priority}
          sx={{ bgcolor: PRIORITY_COLOR[task.priority] ?? '#9E9E9E', color: '#fff' }}
        />
        {task.endDate && (
          <Typography variant="caption" color="text.secondary">
            截止 {task.endDate.slice(0, 10)}
          </Typography>
        )}
      </Stack>
      <Box sx={{ mb: 1 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
          <Typography variant="caption" color="text.secondary">进度</Typography>
          <Typography variant="caption" color="text.secondary">{task.progress}%</Typography>
        </Box>
        <Box sx={{ height: 4, bgcolor: '#e0e0e0', borderRadius: 2 }}>
          <Box sx={{ height: 4, width: `${task.progress}%`, bgcolor: '#1976D2', borderRadius: 2 }} />
        </Box>
      </Box>
      <Select
        size="small"
        fullWidth
        value={task.status}
        onChange={(e) => onStatusChange(e.target.value as TaskStatus)}
      >
        {COLUMNS.map((c) => (
          <MenuItem key={c.key} value={c.key}>{c.label}</MenuItem>
        ))}
      </Select>
    </Box>
  );
}
