import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Box, Card, CardContent, CircularProgress, Typography, Alert, Chip,
} from '@mui/material';
import { projectApi, type GanttNode } from './api';

interface FlatTask {
  id: string;
  title: string;
  depth: number;
  progress: number;
  status: string;
  start: number | null;
  end: number | null;
}

/** 树形 → 扁平列表(带层级缩进)。 */
function flatten(nodes: GanttNode[], depth = 0, out: FlatTask[] = []): FlatTask[] {
  nodes.forEach((n) => {
    out.push({
      id: n.id,
      title: n.title,
      depth,
      progress: n.progress ?? 0,
      status: n.status,
      start: n.startDate ? Date.parse(n.startDate) : null,
      end: n.endDate ? Date.parse(n.endDate) : null,
    });
    if (n.children?.length) flatten(n.children, depth + 1, out);
  });
  return out;
}

function fmt(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

interface Props { projectId: string }

/** 甘特图 — 按时间轴展示任务区间与进度(对标项目管理视图)。 */
export default function GanttView({ projectId }: Props) {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['project-gantt', projectId],
    queryFn: () => projectApi.gantt(projectId),
    enabled: !!projectId,
  });

  const { tasks, min, span } = useMemo(() => {
    const flat = flatten(data ?? []);
    const times = flat.filter((t) => t.start != null && t.end != null);
    const lo = times.length ? Math.min(...times.map((t) => t.start as number)) : 0;
    const hi = times.length ? Math.max(...times.map((t) => t.end as number)) : 0;
    return { tasks: flat, min: lo, span: Math.max(hi - lo, 1) };
  }, [data]);

  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (isError) {
    return <Alert severity="error">加载甘特数据失败:{(error as Error)?.message}</Alert>;
  }

  if (tasks.length === 0) {
    return <Alert severity="info">该项目暂无任务,请先创建任务并设置开始/结束日期。</Alert>;
  }

  const scheduled = tasks.some((t) => t.start != null && t.end != null);

  return (
    <Card>
      <CardContent>
        <StackHeader min={min} span={span} showAxis={scheduled} />
        <Box>
          {tasks.map((t) => {
            const hasDates = t.start != null && t.end != null;
            const left = hasDates ? ((t.start as number) - min) / span * 100 : 0;
            const width = hasDates
              ? Math.max(((t.end as number) - (t.start as number)) / span * 100, 1)
              : 0;
            return (
              <Box
                key={t.id}
                sx={{
                  display: 'flex', alignItems: 'center', py: 0.75,
                  borderBottom: '1px solid #f0f0f0',
                }}
              >
                {/* 左侧:任务标题(按层级缩进) */}
                <Box sx={{ width: 240, pr: 2, pl: t.depth * 2, flexShrink: 0 }}>
                  <Typography
                    variant="body2"
                    noWrap
                    sx={{ fontWeight: t.depth === 0 ? 600 : 400 }}
                    title={t.title}
                  >
                    {t.title}
                  </Typography>
                </Box>

                {/* 右侧:时间条 */}
                <Box sx={{ position: 'relative', flex: 1, height: 24, bgcolor: '#fafafa', borderRadius: 1 }}>
                  {hasDates ? (
                    <Box
                      sx={{
                        position: 'absolute',
                        left: `${left}%`,
                        width: `${width}%`,
                        top: 4,
                        height: 16,
                        bgcolor: '#1976D2',
                        borderRadius: 1,
                        overflow: 'hidden',
                      }}
                      title={`${fmt(t.start as number)} → ${fmt(t.end as number)}`}
                    >
                      <Box
                        sx={{
                          width: `${t.progress}%`,
                          height: '100%',
                          bgcolor: '#4CAF50',
                        }}
                      />
                    </Box>
                  ) : (
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ pl: 1, lineHeight: '24px' }}
                    >
                      未排期
                    </Typography>
                  )}
                </Box>

                <Box sx={{ width: 56, pl: 1, flexShrink: 0 }}>
                  <Typography variant="caption" color="text.secondary">
                    {t.progress}%
                  </Typography>
                </Box>
              </Box>
            );
          })}
        </Box>
        {scheduled && (
          <Typography variant="caption" color="text.secondary" sx={{ mt: 2, display: 'block' }}>
            时间范围:{fmt(min)} ~ {fmt(min + span)}
          </Typography>
        )}
      </CardContent>
    </Card>
  );
}

/** 时间轴表头(按季度粗略分隔,仅作刻度参考)。 */
function StackHeader({ min, span, showAxis }: { min: number; span: number; showAxis: boolean }) {
  if (!showAxis) return null;
  const ticks = [0, 0.25, 0.5, 0.75, 1];
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
      <Box sx={{ width: 240, pr: 2, flexShrink: 0 }}>
        <Typography variant="caption" color="text.secondary">任务</Typography>
      </Box>
      <Box sx={{ position: 'relative', flex: 1, height: 20 }}>
        {ticks.map((p) => (
          <Box
            key={p}
            sx={{
              position: 'absolute',
              left: `${p * 100}%`,
              top: 0,
              transform: p === 1 ? 'translateX(-100%)' : 'none',
            }}
          >
            <Typography variant="caption" color="text.secondary">
              {fmt(min + span * p)}
            </Typography>
          </Box>
        ))}
      </Box>
      <Box sx={{ width: 56, pl: 1, flexShrink: 0 }}>
        <Typography variant="caption" color="text.secondary">进度</Typography>
      </Box>
    </Box>
  );
}

/** 状态图例(供外部复用)。 */
export function GanttLegend() {
  return (
    <Box sx={{ display: 'flex', gap: 1, mb: 2 }}>
      <Chip size="small" label="计划" sx={{ bgcolor: '#1976D2', color: '#fff' }} />
      <Chip size="small" label="已完成进度" sx={{ bgcolor: '#4CAF50', color: '#fff' }} />
    </Box>
  );
}
