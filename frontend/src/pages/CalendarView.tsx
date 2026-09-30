import { useState, useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta } from '@/types/collection';
import type { ViewFull } from '@/types/view';

/** 日历视图 — 按日期字段分组展示记录 (T3: 支持翻月) */
export function CalendarViewPage() {
  const { id } = useParams<{ id: string }>();
  const [currentMonth, setCurrentMonth] = useState<Date>(new Date());

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

  if (!viewData || !collectionData) return <p>加载中…</p>;

  const fields = collectionData.fields ?? [];
  const config = (viewData.config ?? {}) as { dateField?: string; titleField?: string };
  const dateField = config.dateField ?? fields.find((f) => f.type === 'date' || f.type === 'datetime')?.name;
  const titleField = config.titleField ?? fields.find((f) => f.type === 'text')?.name ?? 'id';

  const { data: monthRecordsData } = useQuery({
    queryKey: ['records', collectionName, currentMonth.toISOString().slice(0, 7)],
    queryFn: () => {
      // R5 T3: Query by month to avoid limit=500 truncation
      const year = currentMonth.getFullYear();
      const month = String(currentMonth.getMonth() + 1).padStart(2, '0');
      return apiClient.get<any[]>(`/collections/${collectionName}/records?limit=5000&filter=${encodeURIComponent(dateField + '_gte=' + year + '-' + month + '-01')}&filter=${encodeURIComponent(dateField + '_lte=' + year + '-' + month + '-31')}`);
    },
    enabled: !!collectionName && !!dateField,
  });

  const allRecords = monthRecordsData ?? [];

  const { weeks, monthName } = useMemo(
    () => buildCalendar(allRecords, currentMonth, dateField, titleField),
    [allRecords, currentMonth, dateField, titleField]
  );

  const goToPrevMonth = () => {
    setCurrentMonth(new Date(currentMonth.getFullYear(), currentMonth.getMonth() - 1, 1));
  };

  const goToNextMonth = () => {
    setCurrentMonth(new Date(currentMonth.getFullYear(), currentMonth.getMonth() + 1, 1));
  };

  const goToToday = () => {
    setCurrentMonth(new Date());
  };

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto', background: 'var(--color-bg-primary)', minHeight: '100vh', padding: '24px 0' }}>
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0' }}>{viewData.title}</h1>
      
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
        <button onClick={goToPrevMonth} className="glass-button" style={{ padding: '4px 12px', fontSize: 12 }}>
          ← 上一月
        </button>
        <button onClick={goToNextMonth} className="glass-button" style={{ padding: '4px 12px', fontSize: 12 }}>
          下一月 →
        </button>
        <button onClick={goToToday} className="glass-button" style={{ padding: '4px 12px', fontSize: 12 }}>
          回到今天
        </button>
        <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--color-text-primary)' }}>
          {monthName}
        </span>
      </div>

      <p style={{ color: 'var(--color-text-muted)', marginBottom: 24 }}>
        共 {allRecords.length} 条记录 · 按 <code>{dateField}</code> 排列
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 1, background: 'var(--color-border-light)', borderRadius: 8, overflow: 'hidden' }}>
        {['日', '一', '二', '三', '四', '五', '六'].map((day) => (
          <div key={day} style={{ background: 'rgba(255,255,255,0.02)', padding: 8, textAlign: 'center', fontWeight: 600, fontSize: 12, color: 'var(--color-text-muted)' }}>
            {day}
          </div>
        ))}

        {weeks.length === 0 ? (
          <div style={{ gridColumn: '1/-1', textAlign: 'center', padding: 48, color: 'var(--color-text-muted)' }}>
            该月份无数据
          </div>
        ) : (
          weeks.map((week, wi) =>
            week.map((day, di) => (
              <div
                key={wi + '-' + di}
                className="glass-card"
                style={{ minHeight: 120, padding: 8, border: '1px solid var(--color-border-light)' }}
              >
                <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginBottom: 4 }}>
                  {day.day > 0 && (
                    <>
                      {day.records.some(() => isToday(day.day, currentMonth)) && (
                        <span style={{ marginRight: 4, color: 'var(--color-info)' }}>●</span>
                      )}
                      {day.day}
                    </>
                  )}
                </div>
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
          )
        )}
      </div>
    </div>
  );
}

interface CalendarDay {
  day: number;
  records: Array<{ id: string; title: string }>;
}

function isToday(day: number, month: Date): boolean {
  const today = new Date();
  return day === today.getDate() && 
         month.getMonth() === today.getMonth() && 
         month.getFullYear() === today.getFullYear();
}

function buildCalendar(records: any[], currentMonth: Date, dateField?: string, titleField?: string) {
  const year = currentMonth.getFullYear();
  const month = currentMonth.getMonth();

  const firstDay = new Date(year, month, 1);
  const startOffset = firstDay.getDay(); // 0=周日
  const daysInMonth = new Date(year, month + 1, 0).getDate();

  const map = new Map<string, Array<{ id: string; title: string }>>();
  for (const r of records) {
    const raw = r[dateField ?? ''];
    if (!raw) continue;
    const d = new Date(raw);
    if (isNaN(d.getTime()) || d.getMonth() !== month || d.getFullYear() !== year) continue;
    const key = d.getDate().toString();
    if (!map.has(key)) map.set(key, []);
    map.get(key)!.push({ id: r.id, title: r[titleField ?? ''] ?? r.id });
  }

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

  const monthName = currentMonth.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long' });
  return { weeks, monthName };
}