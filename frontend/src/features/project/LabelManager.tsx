import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Box, Button, IconButton, Paper, TextField, Typography } from '@mui/material';
import { DeleteOutlined, EditOutlined, LabelOutlined } from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';

export interface CardLabel {
  id: string;
  name: string;
  color?: string;
}

const PALETTE = ['#2f80ed', '#eb5757', '#27ae60', '#f2994a', '#9b51e0', '#219653', '#56ccf2'];

/**
 * Wave4 待办池 #15：项目标签管理 UI。
 *
 * 此前后端标签 CRUD（/api/project-boards/labels*）已存在但前端零接线 →
 * 标签功能对用户不可见。本组件消费既有端点，不新增后端能力。
 */
export function LabelManager({ projectId, onClose }: { projectId: string; onClose?: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [name, setName] = useState('');
  const [color, setColor] = useState(PALETTE[0]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingName, setEditingName] = useState('');

  const { data } = useQuery({
    queryKey: ['project-labels', projectId],
    queryFn: () =>
      apiClient.get<{ code: number; data: CardLabel[] }>(
        `/project-boards/labels/by-project/${projectId}`,
      ) as Promise<{ code: number; data: CardLabel[] }>,
    enabled: !!projectId,
  });
  const labels: CardLabel[] = Array.isArray(data?.data) ? data!.data : [];

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['project-labels', projectId] });

  const create = useMutation({
    mutationFn: () => apiClient.post('/project-boards/labels', { projectId, name, color }),
    onSuccess: () => { setName(''); invalidate(); },
  });

  const rename = useMutation({
    mutationFn: (args: { id: string; newName: string }) =>
      apiClient.put(`/project-boards/labels/${args.id}`, { name: args.newName }),
    onSuccess: () => { setEditingId(null); setEditingName(''); invalidate(); },
  });

  const remove = useMutation({
    mutationFn: (id: string) => apiClient.delete(`/project-boards/labels/${id}`),
    onSuccess: invalidate,
  });

  return (
    <Paper sx={{ p: 2 }} data-testid="label-manager">
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
          <LabelOutlined fontSize="small" /> {t('board.labels')}
        </Typography>
        {onClose && (
          <Button size="small" onClick={onClose}>{t('board.labelsClose')}</Button>
        )}
      </Box>

      <Box sx={{ display: 'flex', gap: 1, mb: 1.5 }}>
        <TextField
          size="small"
          value={name}
          placeholder={t('board.labelNamePlaceholder')}
          onChange={(e) => setName(e.target.value)}
          slotProps={{ input: { 'aria-label': t('board.labelNamePlaceholder') } }}
        />
        <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center' }}>
          {PALETTE.map((c) => (
            <IconButton
              key={c}
              size="small"
              aria-label={`${t('board.labelColor')} ${c}`}
              onClick={() => setColor(c)}
              style={{
                width: 18, height: 18, borderRadius: '50%', background: c,
                outline: color === c ? '2px solid var(--color-text-primary)' : 'none',
              }}
            />
          ))}
        </Box>
        <Button
          size="small"
          variant="contained"
          disabled={!name.trim() || create.isPending}
          onClick={() => create.mutate()}
        >
          {t('board.labelCreate')}
        </Button>
      </Box>

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        {labels.length === 0 ? (
          <Typography variant="body2" sx={{ color: 'var(--color-text-muted)' }}>
            {t('board.labelsEmpty')}
          </Typography>
        ) : labels.map((l) => (
          <Box key={l.id} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <span style={{
              width: 10, height: 10, borderRadius: '50%', background: l.color || PALETTE[0],
            }} />
            {editingId === l.id ? (
              <>
                <TextField
                  size="small"
                  value={editingName}
                  onChange={(e) => setEditingName(e.target.value)}
                  slotProps={{ input: { 'aria-label': t('board.labelRename') } }}
                />
                <Button
                  size="small"
                  disabled={!editingName.trim() || rename.isPending}
                  onClick={() => rename.mutate({ id: l.id, newName: editingName.trim() })}
                >
                  {t('board.labelSave')}
                </Button>
              </>
            ) : (
              <>
                <Typography variant="body2" sx={{ flex: 1 }}>{l.name}</Typography>
                <IconButton
                  size="small"
                  aria-label={`${t('board.labelRename')} ${l.name}`}
                  onClick={() => { setEditingId(l.id); setEditingName(l.name); }}
                >
                  <EditOutlined fontSize="small" />
                </IconButton>
                <IconButton
                  size="small"
                  aria-label={`${t('board.labelDelete')} ${l.name}`}
                  disabled={remove.isPending}
                  onClick={() => remove.mutate(l.id)}
                >
                  <DeleteOutlined fontSize="small" />
                </IconButton>
              </>
            )}
          </Box>
        ))}
      </Box>
    </Paper>
  );
}
