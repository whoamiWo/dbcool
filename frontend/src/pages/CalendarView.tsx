import { useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta } from '@/types/collection';
import type { ViewFull } from '@/types/view';

/** 日历视图 — 按日期字段分组展示记录 */
export function CalendarViewPage() {
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

  const { data: recordsData } = useQuery({
    queryKey: ['records', collectionName],
    queryFn: () => apiClient.get<any[]>(`/collections/${collectionName}/records?limit=500`),
    enabled: !!collectionName,
  });

  if (!viewData || !collectionData) return <p>加载中…</p>;

  const fields = collectionData.fields ?? [];
  const records = recordsData ?? [];
  const config = (viewData.config ?? {}) as { dateField?: string; titleField?: string };
  const dateField = config.dateField ?? fields.find((f) => f.type === 'date' || f.type === 'datetime')?.name;
  const titleField = config.titleField ?? fields.find((f) => f.type === 'text')?.name ?? 'id';

  const { weeks } = useMemo(() => buildCalendar(records, dateField, titleField), [records, dateField, titleField]);

  const monthName = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long' });

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto', background: 'var(--color-bg-primary)', minHeight: '100vh', padding: '24px 0' }}>
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0' }}>{viewData.title}</h1>
      <p style={{ color: 'var(--color-text-muted)', marginBottom: 24 }}>
        共 {records.length} 条记录 · 按 <code>{dateField}</code> 排列 · {monthName}
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 1, background: 'rgba(255,255,255,0.05)', borderRadius: 8, overflow: 'hidden' }}>
        {['日', '一', '二', '三', '四', '五', '六'].map((day) => (
          <div key={day} style={{ background: 'rgba(255,255,255,0.02)', padding: 8, textAlign: 'center', fontWeight: 600, fontSize: 12, color: 'var(--color-text-muted)' }}>
            {day}
          </div>
        ))}

        {weeks.map((week, wi) =>
          week.map((day, di) => (
            <div
              key={wi + '-' + di}
              className="glass-card"
              style={{ minHeight: 120, padding: 8, border: '1px solid rgba(255,255,255,0.05)' }}
            >
              <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginBottom: 4 }}>{day.day}</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                {day.records.slice(0, 3).map((r) => (
                  <div
                    key={r.id}
                    style={{ background: 'rgba(59, 130, 246, 0.15)', borderLeft: '2px solid var(--color-info)', padding: '2px 6px', borderRadius: 2, fontSize: 11, color: 'var(--color-primary-200)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}
                    title={r.title}
                  >
                    {r.title}
                  </div>
                ))}
                {day.records.length > 3 && (
                  <div style={{ fontSize: 10, color: 'var(--color-text-muted)' }}>还有 {day.records.length - 3} 条</div>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

interface CalendarDay {
  day: number;
  records: Array<{ id: string; title: string }>;
}

function buildCalendar(records: any[], dateField?: string, titleField?: string) {
  const today = new Date();
  const year = today.getFullYear();
  const month = today.getMonth();

  const firstDay = new Date(year, month, 1);
  const startOffset = firstDay.getDay(); // 0=周日
  const daysInMonth = new Date(year, month + 1, 0).getDate();

  const map = new Map<string, Array<{ id: string; title: string }>>();
  records.forEach((r) => {
    const raw = r[dateField ?? ''];
    if (!raw) return;
    const d = new Date(raw);
    if (isNaN(d.getTime()) || d.getMonth() !== month || d.getFullYear() !== year) return;
    const key = d.getDate().toString();
    if (!map.has(key)) map.set(key, []);
    map.get(key)!.push({ id: r.id, title: r[titleField ?? ''] ?? r.id });
  });

  const weeks: CalendarDay[][] = [];
  let currentWeek: CalendarDay[] = [];
  for (let i = 0; i < startOffset; i++) {
    currentWeek.push({ day: 0, records: [] });
  }
  for (let day = 1; day <= daysInMonth; day++) {
    currentWeek.push({ day, records: map.get(day.toString()) ?? [] });
    if (currentWeek.length === 7) {
      weeks.push(currentWeek);
      currentWeek = [];
    }
  }
  if (currentWeek.length > 0) {
    while (currentWeek.length < 7) currentWeek.push({ day: 0, records: [] });
    weeks.push(currentWeek);
  }

  const monthName = today.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long' });
  return { weeks, monthName };
}