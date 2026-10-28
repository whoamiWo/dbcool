import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta } from '@/types/collection';
import type { ViewFull } from '@/types/view';

/**
 * 通用集合甘特图视图（B5）。
 *
 * 与 features/project/GanttView（项目任务专用）不同：本组件基于任意
 * Collection 记录，按「开始日期字段 / 结束日期字段」渲染区间条。
 * 复用 CalendarView 的记录获取与时间轴布局范式，零新增依赖。
 */
export function GanttViewPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();

  const { data: viewData } = useQuery({
    queryKey: ['view', id],
    queryFn: () => apiClient.get<ViewFull>(`/views/${id}`),
    enabled: !!id,
  });

  const collectionName = viewData?.collection_name;
  const { data: collectionData } = useQuery({
    queryKey: ['collection', collectionName],
    queryFn: () => apiClient.get<CollectionMeta>(`/collections/${collectionName}`),
    enabled: !!collectionName,
  });

  const fields = collectionData?.fields ?? [];
  const config = (viewData?.config ?? {}) as {
    startDateField?: string;
    endDateField?: string;
    titleField?: string;
    progressField?: string;
    dependsOnField?: string;
  };

  const startField =
    config.startDateField ??
    fields.find((f) => f.name.toLowerCase().includes('start'))?.name ??
    'start_date';
  const endField =
    config.endDateField ??
    fields.find((f) => f.name.toLowerCase().includes('end'))?.name ??
    'end_date';
  const titleField =
    config.titleField ??
    fields.find((f) => f.type === 'text')?.name ??
    'title';
  // B5 返工：progress / 依赖字段解析（修复前 pages/GanttView 无 progress、无依赖线）
  const progressField =
    config.progressField ??
    fields.find((f) => f.name.toLowerCase().includes('progress'))?.name;
  const dependsOnField =
    config.dependsOnField ??
    fields.find((f) => f.name.toLowerCase().includes('depend'))?.name;

  const { data: recordsData } = useQuery({
    queryKey: ['gantt-records', collectionName, startField, endField],
    queryFn: () =>
      apiClient.get<any[]>(`/collections/${collectionName}/records?limit=500`),
    enabled: !!collectionName && !!viewData && !!collectionData,
  });

  const rows = useMemo(() => {
    const recs = recordsData ?? [];
    const parsed = recs
      .map((r) => {
        const s = r[startField] ? Date.parse(String(r[startField])) : null;
        const e = r[endField] ? Date.parse(String(r[endField])) : null;
        // B5 返工：progress / dependsOn 字段（可选）
        const progress = progressField && r[progressField] != null
          ? Math.min(100, Math.max(0, Number(r[progressField]) || 0))
          : 0;
        const dependsOn = dependsOnField && r[dependsOnField] != null
          ? String(r[dependsOnField]).split(',').map((x: string) => x.trim()).filter(Boolean)
          : [] as string[];
        return {
          id: String(r.id),
          title: String(r[titleField] ?? r.id ?? '').slice(0, 40),
          s,
          e,
          progress,
          dependsOn,
        };
      })
      .filter((x) => x.s != null || x.e != null) as Array<{
      id: string;
      title: string;
      s: number | null;
      e: number | null;
      progress: number;
      dependsOn: string[];
    }>;

    const times = parsed
      .map((x) => x.s ?? x.e)
      .filter((v): v is number => v != null);
    const lo = times.length ? Math.min(...times) : 0;
    const hi = times.length ? Math.max(...times) : 0;
    const span = Math.max(hi - lo, 1);

    // B5 返工：依赖线 — 每条依赖连接「前置任务结束点 → 当前任务开始点」
    const byId = new Map(parsed.map((p) => [p.id, p]));
    const deps: Array<{ fromId: string; toId: string; fromX: number; toX: number; fromY: number; toY: number }> = [];
    const rowIndex = new Map(parsed.map((p, i) => [p.id, i]));
    parsed.forEach((cur, i) => {
      cur.dependsOn.forEach((depId) => {
        const dep = byId.get(depId);
        if (!dep || dep.s == null || dep.e == null || cur.s == null) return;
        const depRow = rowIndex.get(depId);
        if (depRow == null) return;
        // 前置任务结束 X 坐标 → 当前任务开始 X 坐标
        deps.push({
          fromId: depId,
          toId: cur.id,
          fromX: ((dep.e as number) - lo) / span * 100,
          toX: ((cur.s as number) - lo) / span * 100,
          fromY: depRow,
          toY: i,
        });
      });
    });

    // 时间轴刻度：按 25% / 50% / 75% / 100% 分段
    const ticks = [0, 0.25, 0.5, 0.75, 1].map((p) => ({
      pos: p,
      label: new Date(lo + span * p).toISOString().slice(0, 10),
    }));

    return { rows: parsed, lo, span, ticks, deps };
  }, [recordsData, startField, endField, titleField, progressField, dependsOnField]);

  if (!viewData || !collectionData) {
    return <p style={{ color: 'var(--color-text-muted)' }}>{t('calendarView.loading')}</p>;
  }

  return (
    <div
      style={{
        maxWidth: 1200,
        margin: '0 auto',
        padding: '24px 0',
      }}
    >
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0' }}>
        {viewData.title}
      </h1>
      <p style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>
        {t('calendarView.recordCount', { count: rows.rows.length })} · {t('calendarView.sortedBy', { field: startField })}
      </p>

      {/* 时间轴表头 */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '240px 1fr',
          gap: 8,
          marginBottom: 4,
          fontSize: 12,
          color: 'var(--color-text-muted)',
        }}
      >
        <div>{t('calendarView.sortedBy', { field: startField })}</div>
        <div style={{ position: 'relative', height: 16 }}>
          {rows.ticks.map((tk) => (
            <span
              key={tk.pos}
              style={{
                position: 'absolute',
                left: `${tk.pos * 100}%`,
                transform: tk.pos === 1 ? 'translateX(-100%)' : 'none',
                fontSize: 11,
                color: 'var(--color-text-muted)',
              }}
            >
              {tk.label}
            </span>
          ))}
        </div>
      </div>

      {/* 甘特行 */}
      <div
        style={{
          border: '1px solid var(--color-border-light)',
          borderRadius: 8,
          overflow: 'hidden',
          position: 'relative',
        }}
      >
        {/* B5 返工：依赖线 SVG 覆盖层（修复前 pages/GanttView 无依赖连线）
            每条依赖从前置任务结束 X 斜拉到当前任务开始 X */}
        {rows.deps.length > 0 && (
          <svg
            style={{
              position: 'absolute',
              left: 240,
              top: 0,
              width: 'calc(100% - 248px)',
              height: '100%',
              pointerEvents: 'none',
              overflow: 'visible',
            }}
            aria-hidden
          >
            {rows.deps.map((d, i) => {
              const rowH = 38; // 每行约 38px（8px padding + 22px bar）
              const y1 = d.fromY * rowH + 14;
              const y2 = d.toY * rowH + 14;
              const x1 = d.fromX;
              const x2 = d.toX;
              return (
                <path
                  key={i}
                  d={`M ${x1} ${y1} C ${(x1 + x2) / 2} ${y1}, ${(x1 + x2) / 2} ${y2}, ${x2} ${y2}`}
                  fill="none"
                  stroke="var(--color-border-light)"
                  strokeWidth={1.5}
                  markerEnd="url(#gantt-arrow)"
                  opacity={0.7}
                />
              );
            })}
            <defs>
              <marker
                id="gantt-arrow"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--color-border-light)" />
              </marker>
            </defs>
          </svg>
        )}
        {rows.rows.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center', color: 'var(--color-text-muted)' }}>
            {t('calendarView.noData')}
          </div>
        ) : (
          rows.rows.map((row) => {
            const hasDates = row.s != null && row.e != null;
            const left = hasDates ? ((row.s as number) - rows.lo) / rows.span * 100 : 0;
            const width = hasDates
              ? Math.max(((row.e as number) - (row.s as number)) / rows.span * 100, 1.5)
              : 0;
            return (
              <div
                key={row.id}
                style={{
                  display: 'grid',
                  gridTemplateColumns: '240px 1fr',
                  gap: 8,
                  padding: '8px 0',
                  borderBottom: '1px solid var(--color-border-light)',
                  alignItems: 'center',
                }}
              >
                <div
                  style={{
                    fontSize: 13,
                    color: 'var(--color-text-primary)',
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                  }}
                  title={row.title}
                >
                  {row.title}
                </div>
                <div
                  style={{
                    position: 'relative',
                    height: 22,
                    background: 'rgba(255,255,255,0.03)',
                    borderRadius: 4,
                  }}
                >
                  {hasDates ? (
                    <div
                      style={{
                        position: 'absolute',
                        left: `${left}%`,
                        width: `${width}%`,
                        top: 3,
                        height: 16,
                        background: 'var(--color-info)',
                        borderRadius: 3,
                        overflow: 'hidden',
                      }}
                      title={`${new Date(row.s as number).toISOString().slice(0, 10)} → ${new Date(
                        row.e as number,
                      ).toISOString().slice(0, 10)}`}
                    >
                      {/* B5 返工：区间条内嵌 progress 进度填充（修复前 pages/GanttView 无 progress） */}
                      <div
                        style={{
                          width: `${row.progress}%`,
                          height: '100%',
                          background: 'var(--color-success)',
                          opacity: 0.85,
                        }}
                        aria-label={`progress ${row.progress}%`}
                      />
                    </div>
                  ) : (
                    <span style={{ fontSize: 11, color: 'var(--color-text-muted)', paddingLeft: 6, lineHeight: '22px' }}>
                      {t('calendarView.noData')}
                    </span>
                  )}
                </div>
              </div>
            );
          })
        )}
      </div>

      {rows.rows.length > 0 && (
        <p style={{ fontSize: 11, color: 'var(--color-text-muted)', marginTop: 8 }}>
          {t('calendarView.recordCount', { count: rows.rows.length })} ·{' '}
          {new Date(rows.lo).toISOString().slice(0, 10)} ~{' '}
          {new Date(rows.lo + rows.span).toISOString().slice(0, 10)}
        </p>
      )}
    </div>
  );
}
