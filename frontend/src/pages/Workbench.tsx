import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Box, Button, Card, CardContent, Chip, Grid, Stack, TextField,
  Typography,
} from '@mui/material';
import {
  AutoGraph, FolderOpen, Storage,
  TaskAlt, Workspaces,
  Forum as MessageIcon,
} from '@mui/icons-material';
import apiClient from '@/api/client';

/** 统一工作台门户 — 聚合视图、Wiki、BI、项目、IM 入口。 */
export default function WorkbenchPage() {
  const navigate = useNavigate();
  const [q, setQ] = useState('');

  const { data: collectionsResp } = useQuery({
    queryKey: ['collections'],
    queryFn: () => apiClient.get<{ code: number; data: Array<{ name: string; title?: string }> }>('/collections'),
  });

  const collections = useMemo(() => {
    const r = collectionsResp as unknown;
    if (Array.isArray(r)) return r as Array<{ name: string; title?: string }>;
    return ((r as { data?: Array<{ name: string; title?: string }> })?.data ?? []);
  }, [collectionsResp]);

  const cards = [
    { icon: Storage, title: '数据模型', desc: '动态 Collection + 表单/视图设计器', path: '/designer/schemas', count: collections.length, meta: '低代码底座', color: 'var(--color-primary-500)' },
    { icon: AutoGraph, title: 'BI 报表', desc: '数据透视与图表可视化', path: '/bi', count: 0, meta: '数据分析', color: 'var(--color-secondary-500)' },
    { icon: TaskAlt, title: '项目协同', desc: '任务看板与甘特图', path: '/projects', count: 0, meta: '项目管理', color: 'var(--color-success-500)' },
    { icon: MessageIcon, title: '即时消息', desc: '频道、线程、在线状态', path: '/im', count: 0, meta: '团队协作', color: 'var(--color-info-500)' },
    { icon: Workspaces, title: '工作流', desc: '审批/通知/数据更新/HTTP', path: '/designer/workflows', count: 0, meta: '流程引擎', color: 'var(--color-error-500)' },
    { icon: FolderOpen, title: 'Wiki 知识库', desc: '文档编辑、版本历史、全文检索', path: '/wiki/kb', count: 0, meta: '知识管理', color: 'var(--color-primary-400)' },
  ];

  const filtered = cards.filter((c) =>
    !q || c.title.includes(q) || c.desc.includes(q) || c.meta.includes(q)
  );

  return (
    <Box sx={{ p: 3, maxWidth: 1200, mx: 'auto' }}>
      <Stack direction="row" spacing={2} sx={{ mb: 3, alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography variant="h5" sx={{ fontWeight: 600 }}>🏢 统一工作台</Typography>
        <Typography variant="body2" color="text.secondary">聚合数据、文档、项目与协作入口</Typography>
        <Box sx={{ flex: 1 }} />
        <TextField
          size="small"
          placeholder="搜索模块…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          sx={{ minWidth: 240 }}
        />
      </Stack>

      <Grid container spacing={2}>
        {filtered.map((c) => (
          <Grid key={c.title} size={{ xs: 12, sm: 6, md: 4 }}>
            <Card
              onClick={() => navigate(c.path)}
              sx={{
                height: '100%',
                cursor: 'pointer',
                background: 'var(--glass-bg-medium)',
                backdropFilter: 'var(--glass-blur)',
                border: 'var(--glass-border)',
                boxShadow: 'var(--glass-shadow)',
                borderRadius: 'var(--radius-lg)',
                transition: 'all var(--transition-normal)',
                position: 'relative',
                overflow: 'hidden',
                '&::before': {
                  content: '""',
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  right: 0,
                  height: '3px',
                  background: c.color,
                },
                '&:hover': {
                  background: 'rgba(255, 255, 255, 0.08)',
                  boxShadow: 'var(--glass-shadow-lg)',
                  transform: 'translateY(-4px)',
                },
              }}
            >
              <CardContent>
                <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start' }}>
                  <Box sx={{ p: 1, borderRadius: 1, bgcolor: `${c.color}20`, backdropFilter: 'var(--glass-blur)' }}>
                    <c.icon sx={{ color: c.color, fontSize: 28 }} />
                  </Box>
                  <Box sx={{ flex: 1 }}>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5 }}>
                      <Typography variant="subtitle1" sx={{ fontWeight: 600, color: 'text.primary' }}>{c.title}</Typography>
                      {c.count > 0 && (
                        <Chip size="small" label={c.count} sx={{ bgcolor: c.color, color: '#fff', height: 20 }} />
                      )}
                    </Stack>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                      {c.desc}
                    </Typography>
                    <Typography variant="caption" color="text.disabled">{c.meta}</Typography>
                  </Box>
                </Stack>
              </CardContent>
            </Card>
          </Grid>
        ))}
      </Grid>

      <Box sx={{ mt: 4, p: 3, background: 'var(--glass-bg-light)', backdropFilter: 'var(--glass-blur)', borderRadius: 'var(--radius-lg)', border: 'var(--glass-border)' }}>
        <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 2, color: 'text.primary' }}>快速入口</Typography>
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
          {[
            { label: '知识库列表', path: '/wiki/kb' },
            { label: '数据模型设计', path: '/designer/schemas' },
            { label: 'BI 报表', path: '/bi' },
            { label: '项目任务', path: '/projects' },
            { label: 'IM 消息', path: '/im' },
            { label: '工作流', path: '/designer/workflows' },
            { label: '协同编辑', path: '/collab/demo-doc-id' },
          ].map((item) => (
            <Button key={item.path} variant="outlined" size="small" onClick={() => navigate(item.path)}>
              {item.label}
            </Button>
          ))}
        </Stack>
      </Box>
    </Box>
  );
}