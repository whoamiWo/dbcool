import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Box, Card, CardContent, Chip, CircularProgress, Stack, Typography, Alert,
  Button, Dialog, DialogTitle, DialogContent, DialogActions, TextField, MenuItem,
} from '@mui/material';
import {
  DndContext,
  DragOverlay,
  closestCorners,
  KeyboardSensor,
  PointerSensor,
  TouchSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
  useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { projectApi, type Task, type TaskStatus } from './api';
import { Add, DragIndicator } from '@mui/icons-material';

const COLUMNS: Array<{ key: TaskStatus; label: string; color: string }> = [
  { key: 'TODO', label: '待办', color: '#9E9E9E' },
  { key: 'IN_PROGRESS', label: '进行中', color: '#1976D2' },
  { key: 'BLOCKED', label: '阻塞', color: '#F44336' },
  { key: 'DONE', label: '已完成', color: '#4CAF50' },
];

const PRIORITY_COLOR: Record<string, string> = {
  LOW: '#9E9E9E', MEDIUM: '#1976D2', HIGH: '#FF9800', CRITICAL: '#F44336',
};

function SortableTaskCard({ task, onClick }: { task: Task; onClick: () => void }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: task.id });

  const style = {
    transform: transform ? CSS.Translate.toString(transform) : undefined,
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <Box
      ref={setNodeRef}
      onClick={onClick}
      sx={{
        ...style,
        background: 'rgba(255,255,255,0.05)',
        borderRadius: 1.5,
        p: 1.5,
        mb: 1,
        cursor: 'grab',
        border: '1px solid rgba(255,255,255,0.08)',
        '&:hover': {
          background: 'rgba(255,255,255,0.08)',
          borderColor: 'rgba(255,255,255,0.15)',
        },
      }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start' }}>
        <DragIndicator sx={{ color: 'text.secondary', mt: 0.5, cursor: 'grab' }} {...attributes} {...listeners} />
        <Box sx={{ flex: 1 }}>
          <Typography variant="body2" sx={{ fontWeight: 500 }}>{task.title}</Typography>
          {task.description && (
            <Typography variant="caption" color="text.secondary" sx={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
              {task.description}
            </Typography>
          )}
          <Stack direction="row" spacing={1} sx={{ mt: 1, alignItems: 'center' }}>
            <Chip
              size="small"
              label={task.priority}
              sx={{
                bgcolor: `${PRIORITY_COLOR[task.priority] || '#9E9E9E'}20`,
                color: PRIORITY_COLOR[task.priority] || '#9E9E9E',
                height: 20,
                fontSize: '10px',
              }}
            />
            {task.endDate && (
              <Typography variant="caption" color="text.secondary">
                📅 {new Date(task.endDate).toLocaleDateString()}
              </Typography>
            )}
          </Stack>
        </Box>
      </Stack>
    </Box>
  );
}

function Column({
  label,
  color,
  tasks,
  onTaskClick,
  onAddClick,
}: {
  status: TaskStatus;
  label: string;
  color: string;
  tasks: Task[];
  onTaskClick: (task: Task) => void;
  onAddClick: () => void;
}) {
  return (
    <Box sx={{ flex: 1, minWidth: 280 }}>
      <Card className="glass-card" sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
        <CardContent sx={{ pb: 1 }}>
          <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: color, boxShadow: `0 0 8px ${color}60` }} />
              <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>{label}</Typography>
              <Chip label={tasks.length} size="small" sx={{ bgcolor: 'rgba(255,255,255,0.1)', height: 20, fontSize: '10px' }} />
            </Stack>
            <Button size="small" onClick={onAddClick} sx={{ color: 'primary.main', minWidth: 28, p: 0.5 }}>+</Button>
          </Stack>
        </CardContent>
        <CardContent sx={{ pt: 0, flex: 1, overflow: 'auto' }}>
          <SortableContext items={tasks.map((t) => t.id)} strategy={verticalListSortingStrategy}>
            <Stack spacing={1}>
              {tasks.map((task) => (
                <SortableTaskCard key={task.id} task={task} onClick={() => onTaskClick(task)} />
              ))}
              {tasks.length === 0 && (
                <Typography variant="body2" color="text.secondary" sx={{ fontStyle: 'italic', py: 2, textAlign: 'center' }}>
                  暂无任务
                </Typography>
              )}
            </Stack>
          </SortableContext>
        </CardContent>
      </Card>
    </Box>
  );
}

export default function TaskBoard({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const [activeTask, setActiveTask] = useState<Task | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [newTaskForm, setNewTaskForm] = useState({
    title: '',
    description: '',
    priority: 'MEDIUM' as Task['priority'],
    endDate: '',
  });

  const { data: tasks = [], isLoading, isError } = useQuery({
    queryKey: ['project-tasks', projectId],
    queryFn: () => projectApi.listTasks(projectId),
    enabled: !!projectId,
  });

  const updateMut = useMutation({
    mutationFn: ({ id, status }: { id: string; status: TaskStatus }) =>
      projectApi.updateTask(id, { status }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['project-tasks', projectId] }),
  });

  const createMut = useMutation({
    mutationFn: (body: Record<string, unknown>) => projectApi.createTask(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['project-tasks', projectId] });
      setDialogOpen(false);
      setNewTaskForm({ title: '', description: '', priority: 'MEDIUM', endDate: '' });
    },
  });

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 250, tolerance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const handleDragStart = (event: DragStartEvent) => {
    const active = event.active;
    const task = tasks.find((t) => t.id === active.id);
    if (task) setActiveTask(task);
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    setActiveTask(null);

    if (!over) return;

    const taskId = active.id as string;
    const toStatus = (over.data.current?.sortable?.status || over.id) as TaskStatus;

    const currentTask = tasks.find((t) => t.id === taskId);
    if (!currentTask) return;

    // 同一列内排序
    if (currentTask.status === toStatus) {
      const oldIndex = tasks.findIndex((t) => t.id === taskId);
      const newIndex = tasks.findIndex((t) => t.id === over.id);
      if (oldIndex !== newIndex) {
        const newTasks = arrayMove(tasks, oldIndex, newIndex);
        queryClient.setQueryData(['project-tasks', projectId], newTasks);
      }
    } else {
      // 跨列移动
      updateMut.mutate({ id: taskId, status: toStatus });
    }
  };

  const columns = COLUMNS.map((col) => ({
    ...col,
    tasks: tasks.filter((t) => t.status === col.key),
  }));

  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
        <CircularProgress sx={{ color: 'primary.main' }} />
      </Box>
    );
  }

  if (isError) {
    return <Alert severity="error">加载失败</Alert>;
  }

  return (
    <Box>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 500 }}>看板视图</Typography>
        <Button
          variant="contained"
          size="small"
          startIcon={<Add />}
          onClick={() => setDialogOpen(true)}
          sx={{ bgcolor: 'primary.main', '&:hover': { bgcolor: 'primary.dark' } }}
        >
          新建任务
        </Button>
      </Stack>

      <DndContext
        sensors={sensors}
        collisionDetection={closestCorners}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
      >
        <Box sx={{ display: 'flex', gap: 2, overflowX: 'auto', pb: 2 }}>
          {columns.map((col) => (
            <Column
              key={col.key}
              status={col.key}
              label={col.label}
              color={col.color}
              tasks={col.tasks}
              onTaskClick={(task) => console.log('click', task)}
              onAddClick={() => setDialogOpen(true)}
            />
          ))}
        </Box>
        <DragOverlay>
          {activeTask ? (
            <Box sx={{ background: 'rgba(255,255,255,0.1)', borderRadius: 2, p: 2, border: '1px solid rgba(255,255,255,0.2)' }}>
              <Typography variant="body1" sx={{ fontWeight: 600 }}>{activeTask.title}</Typography>
            </Box>
          ) : null}
        </DragOverlay>
      </DndContext>

      {/* 新建任务对话框 */}
      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>新建任务</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              autoFocus
              label="标题"
              value={newTaskForm.title}
              onChange={(e) => setNewTaskForm({ ...newTaskForm, title: e.target.value })}
              placeholder="例如：完成需求评审"
            />
            <TextField
              label="描述"
              multiline
              rows={3}
              value={newTaskForm.description}
              onChange={(e) => setNewTaskForm({ ...newTaskForm, description: e.target.value })}
            />
            <TextField
              select
              label="优先级"
              value={newTaskForm.priority}
              onChange={(e) => setNewTaskForm({ ...newTaskForm, priority: e.target.value as Task['priority'] })}
            >
              <MenuItem value="LOW">低</MenuItem>
              <MenuItem value="MEDIUM">中</MenuItem>
              <MenuItem value="HIGH">高</MenuItem>
              <MenuItem value="CRITICAL">紧急</MenuItem>
            </TextField>
            <TextField
              label="截止日期"
              type="date"
              value={newTaskForm.endDate}
              onChange={(e) => setNewTaskForm({ ...newTaskForm, endDate: e.target.value })}
              slotProps={{ htmlInput: { shrink: true } }}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>取消</Button>
          <Button
            variant="contained"
            onClick={() => {
              if (!newTaskForm.title.trim()) return;
              createMut.mutate({
                projectId,
                title: newTaskForm.title,
                description: newTaskForm.description || undefined,
                priority: newTaskForm.priority,
                endDate: newTaskForm.endDate || undefined,
                status: 'TODO',
              });
            }}
            disabled={!newTaskForm.title.trim() || createMut.isPending}
            sx={{ bgcolor: 'primary.main', '&:hover': { bgcolor: 'primary.dark' } }}
          >
            {createMut.isPending ? <CircularProgress size={20} /> : '创建'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
