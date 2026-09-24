import { useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Box, Card, CardContent, Typography, CircularProgress, Alert, Chip } from '@mui/material';
import apiClient from '@/api/client';
import type { ViewFull, TimelineConfig } from '@/types/view';

interface TimelineRecord {
  id: string;
  title: string;
  date: string; // ISO date string
  color?: string;
  name?: string;
  created_at?: string;
  [key: string]: unknown;
}

const PALETTE = ['var(--color-primary-500)', 'var(--color-secondary-500)', 'var(--color-secondary-400)', 'var(--color-warning)', 'var(--color-success)', 'var(--color-info)', 'var(--color-success)'];

/** 从视图配置 + collection 字段推断时间字段 */
function inferDateField(view: ViewFull, defaultDateField: string): string {
  try {
    const cfg = JSON.parse(view.config_json ?? '{}') as Partial<TimelineConfig>;
    if (cfg.dateField) return cfg.dateField;
  } catch {}
  return defaultDateField;
}

/** 时间线卡片 */
function TimelineCard({ record, paletteIdx }: { record: TimelineRecord; paletteIdx: number }) {
  const color = record.color ?? PALETTE[paletteIdx % PALETTE.length];
  const dateStr = record.date ? new Date(record.date).toLocaleDateString('zh-CN', {
    year: 'numeric', month: 'short', day: 'numeric',
  }) : '—';

  return (
    <Card
      variant="outlined"
      sx={{
        borderLeft: `4px solid ${color}`,
        borderRadius: 2,
        mb: 1,
        '&:hover': { boxShadow: 'var(--shadow-md)' },
        transition: 'box-shadow 0.2s',
      }}
    >
      <CardContent sx={{ p: 1.5, display: 'flex', gap: 2, alignItems: 'flex-start' }}>
        {/* 时间标签 */}
        <Box sx={{ minWidth: 80, flexShrink: 0 }}>
          <Typography variant="caption" sx={{ color: 'var(--color-text-muted)', display: 'block' }}>
            {dateStr}
          </Typography>
        </Box>
        {/* 内容 */}
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography
            variant="body2"
            sx={{ fontWeight: 500, color: 'var(--color-text-primary)', mb: 0.5 }}
            noWrap
            title={record.title}
          >
            {record.title}
          </Typography>
        </Box>
        <Chip
          size="small"
          label=""
          sx={{
            bgcolor: `${color}22`,
            color,
            fontSize: 10,
            height: 20,
            border: `1px solid ${color}44`,
          }}
        />
      </CardContent>
    </Card>
  );
}

/**
 * 时间线视图 — 按日期纵向排列记录（对标 Notion Timeline / Trello Timeline）。
 *
 * <p>数据来源：Collection API GET /api/collections/{name}/records
 * <p>排序字段：配置中的 dateField（默认 created_at），升序/降序由 config.sortDirection 决定
 */
export function TimelineViewPage() {
  const { id: viewId } = useParams<{ id: string }>();
  const { data: viewData, isLoading: loadingView } = useQuery({
    queryKey: ['view', viewId],
    queryFn: () => apiClient.get<ViewFull>(`/views/${viewId}`),
    enabled: !!viewId,
  });

  const { data: recordsResp, isLoading: loadingRecords } = useQuery({
    queryKey: ['timeline-records', viewId],
    queryFn: async () => {
      const resp = await apiClient.get<{ code: number; data: Record<string, unknown>[] }>(
        `/views/${viewId}/timeline-records?limit=200`
      );
      return resp.data ?? [];
    },
    enabled: !!viewId,
    refetchOnWindowFocus: false,
  });

  if (loadingView || loadingRecords) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress size={32} />
      </Box>
    );
  }

  if (!recordsResp?.length) {
    return (
      <Alert severity="info" sx={{ mt: 2 }}>
        该集合暂无记录，请先添加数据。
      </Alert>
    );
  }

  // 按时间字段排序
  const sorted = useMemo(() => {
    const cfg = (viewData?.config ?? {}) as unknown as TimelineConfig;
    const dateField = cfg.dateField || inferDateField(viewData!, 'created_at');
    const direction = cfg.sortDirection === 'desc' ? -1 : 1;
    return [...recordsResp].sort((a, b) => {
      const da = String(a[dateField] ?? a.created_at ?? '');
      const db = String(b[dateField] ?? b.created_at ?? '');
      return direction * da.localeCompare(db);
    });
  }, [recordsResp, viewData]);

  return (
    <Box sx={{ maxWidth: 720, mx: 'auto', py: 3 }}>
      <Typography variant="h6" sx={{ fontWeight: 600, mb: 2, color: 'var(--color-text-primary)' }}>
        {viewData?.title ?? '时间线'}
      </Typography>
      <Box sx={{ position: 'relative', pl: 3 }}>
        {/* 垂直时间线主线 */}
        <Box
          sx={{
            position: 'absolute',
            left: 7,
            top: 0,
            bottom: 0,
            width: 2,
            bgcolor: 'var(--color-border-medium)',
            borderRadius: 1,
          }}
        />
        {sorted.map((rec, i) => {
          const dateField = inferDateField(viewData!, 'created_at');
          const title = String(rec.title ?? rec.name ?? rec.id ?? '未命名');
          const date = String(rec[dateField] ?? rec.created_at ?? '');
          const color = PALETTE[i % PALETTE.length];
          return (
            <Box key={`${rec.id}-${i}`} sx={{ position: 'relative', pl: 2, mb: 1 }}>
              {/* 时间线圆点 */}
              <Box
                sx={{
                  position: 'absolute',
                  left: -29,
                  top: 16,
                  width: 12,
                  height: 12,
                  borderRadius: '50%',
                  bgcolor: color,
                  border: '2px solid var(--color-bg-primary)',
                }}
              />
              <TimelineCard
                record={{ id: String(rec.id), title, date, color }}
                paletteIdx={i}
              />
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}
