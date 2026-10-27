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
        return { id: String(r.id), title: String(r[titleField] ?? r.id ?? '').slice(0, 40), s, e };
      })
      .filter((x) => x.s != null || x.e != null) as Array<{
      id: string;
      title: string;
      s: number | null;
      e: number | null;
    }>;

    const times = parsed
      .map((x) => x.s ?? x.e)
      .filter((v): v is number => v != null);
    const lo = times.length ? Math.min(...times) : 0;
    const hi = times.length ? Math.max(...times) : 0;
    const span = Math.max(hi - lo, 1);

    // 时间轴刻度：按 25% / 50% / 75% / 100% 分段
    const ticks = [0, 0.25, 0.5, 0.75, 1].map((p) => ({
      pos: p,
      label: new Date(lo + span * p).toISOString().slice(0, 10),
    }));

    return { rows: parsed, lo, span, ticks };
  }, [recordsData, startField, endField, titleField]);

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
        }}
      >
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
                    />
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
