import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();
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

  const { data: viewsResp } = useQuery({
    queryKey: ['views'],
    queryFn: () => apiClient.get<{ code: number; data: Array<{ id: string; title: string; view_type: string }> }>('/views'),
  });
  const views = useMemo(() => {
    const r = viewsResp as unknown;
    if (Array.isArray(r)) return r;
    return ((r as { data?: Array<{ id: string; title: string; view_type: string }> })?.data ?? []);
  }, [viewsResp]);

  const { data: wikiResp } = useQuery({
    queryKey: ['wiki-list'],
    queryFn: () => apiClient.get<{ code: number; data: Array<{ id: string; title: string }> }>('/wiki'),
  });
  const wikis = useMemo(() => {
    const r = wikiResp as unknown;
    if (Array.isArray(r)) return r;
    return ((r as { data?: Array<{ id: string; title: string }> })?.data ?? []);
  }, [wikiResp]);

  const { data: projectResp } = useQuery({
    queryKey: ['projects'],
    queryFn: () => apiClient.get<{ code: number; data: Array<{ id: string; title: string }> }>('/projects'),
  });
  const projects = useMemo(() => {
    const r = projectResp as unknown;
    if (Array.isArray(r)) return r;
    return ((r as { data?: Array<{ id: string; title: string }> })?.data ?? []);
  }, [projectResp]);

  const { data: channelsResp } = useQuery({
    queryKey: ['channels'],
    queryFn: () => apiClient.get<{ code: number; data: Array<{ id: string; name: string }> }>('/im/channels'),
  });
  const channels = useMemo(() => {
    const r = channelsResp as unknown;
    if (Array.isArray(r)) return r;
    return ((r as { data?: Array<{ id: string; name: string }> })?.data ?? []);
  }, [channelsResp]);

  const cards = [
    { icon: Storage, title: t('workbench.cardDataModel'), desc: t('workbench.cardDataModelDesc'), path: '/designer/schemas', count: collections.length, meta: t('workbench.cardDataModelMeta'), color: 'var(--color-primary-500)' },
    { icon: AutoGraph, title: t('workbench.cardBi'), desc: t('workbench.cardBiDesc'), path: '/bi', count: views.length, meta: t('workbench.cardBiMeta'), color: 'var(--color-secondary-500)' },
    { icon: TaskAlt, title: t('workbench.cardProject'), desc: t('workbench.cardProjectDesc'), path: '/projects', count: projects.length, meta: t('workbench.cardProjectMeta'), color: 'var(--color-success)' },
    { icon: MessageIcon, title: t('workbench.cardIm'), desc: t('workbench.cardImDesc'), path: '/im', count: channels.length, meta: t('workbench.cardImMeta'), color: 'var(--color-info)' },
    { icon: Workspaces, title: t('workbench.cardWorkflow'), desc: t('workbench.cardWorkflowDesc'), path: '/designer/workflows', count: 0, meta: t('workbench.cardWorkflowMeta'), color: 'var(--color-error)' },
    { icon: FolderOpen, title: t('workbench.cardWiki'), desc: t('workbench.cardWikiDesc'), path: '/wiki/kb', count: wikis.length, meta: t('workbench.cardWikiMeta'), color: 'var(--color-primary-400)' },
  ];

  const filtered = cards.filter((c) =>
    !q || c.title.includes(q) || c.desc.includes(q) || c.meta.includes(q)
  );

  return (
    <Box sx={{ p: 3, maxWidth: 1200, mx: 'auto' }}>
      <Stack direction="row" spacing={2} sx={{ mb: 3, alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography variant="h5" sx={{ fontWeight: 600 }}>🏢 {t('workbench.title')}</Typography>
        <Typography variant="body2" color="text.secondary">{t('workbench.subtitle')}</Typography>
        <Box sx={{ flex: 1 }} />
        <TextField
          size="small"
          placeholder={t('workbench.searchPlaceholder')}
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
                        <Chip size="small" label={c.count} sx={{ bgcolor: c.color, color: 'var(--color-text-primary)', height: 20 }} />
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
        <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 2, color: 'text.primary' }}>{t('workbench.quickAccess')}</Typography>
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
          {[
            { label: t('workbench.quickKb'), path: '/wiki/kb' },
            { label: t('workbench.quickSchema'), path: '/designer/schemas' },
            { label: t('workbench.cardBi'), path: '/bi' },
            { label: t('workbench.quickProject'), path: '/projects' },
            { label: t('workbench.quickIm'), path: '/im' },
            { label: t('workbench.cardWorkflow'), path: '/designer/workflows' },
            { label: t('workbench.quickCollab'), path: '/collab/demo-doc-id' },
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