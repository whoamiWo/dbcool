import { useState, useMemo, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta } from '@/types/collection';
import type { ViewFull } from '@/types/view';

type ViewMode = 'month' | 'week' | 'year';

/** 移动端断点检测 hook（<=768px 视为移动端） */
function useIsMobile(breakpoint = 768): boolean {
  const [isMobile, setIsMobile] = useState<boolean>(
    typeof window !== 'undefined' ? window.innerWidth <= breakpoint : false
  );
  useEffect(() => {
    const handler = () => setIsMobile(window.innerWidth <= breakpoint);
    window.addEventListener('resize', handler);
    return () => window.removeEventListener('resize', handler);
  }, [breakpoint]);
  return isMobile;
}

/** 日历视图 — 支持月/周/年视图（C3） */
export function CalendarViewPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const [currentMonth, setCurrentMonth] = useState<Date>(new Date());
  const [viewMode, setViewMode] = useState<ViewMode>('month');
  const isMobile = useIsMobile();
  const [expandedDay, setExpandedDay] = useState<number | null>(null);
  const queryClient = useQueryClient();

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
  const config = (viewData?.config ?? {}) as { dateField?: string; titleField?: string };
  const dateField = config.dateField ?? fields.find((f) => f.type === 'date' || f.type === 'datetime')?.name;
  const titleField = config.titleField ?? fields.find((f) => f.type === 'text')?.name ?? 'id';

  const year = currentMonth.getFullYear();
  const month = currentMonth.getMonth();

  const { data: allRecordsData } = useQuery({
    queryKey: ['records', collectionName, year, month, dateField],
    queryFn: () => {
      // Wave0.5 A-6 断链修复：原请求发 `field_gte=...`（两段 filter 参数），
      // 后端只认 `field:op:value` 逗号分隔语法，且 gt/lt 是**数值**比较（numericExpr），
      // 对日期字段无效 → 原"过滤"形同虚设。这里不再发送无效 filter，
      // 改为拉取后在前端按当前月裁剪（renderRecordsForMonth）。
      // 服务端日期范围过滤需新增 gte/lte 文本比较 op —— 属能力扩容，已列入例外通道候选。
      return apiClient.get<any[]>(`/collections/${collectionName}/records?limit=5000`);
    },
    enabled: !!collectionName && !!dateField && !!viewData && !!collectionData,
  });

  const allRecords = allRecordsData ?? [];

  /** Wave0.5 A-6：按当前月裁剪记录（翻月后只显示该月数据）。 */
  const recordsForMonth = useMemo(() => {
    if (!dateField) return allRecords;
    const prefix = `${year}-${String(month + 1).padStart(2, '0')}`;
    return allRecords.filter((r: any) => {
      const raw = r[dateField];
      if (!raw) return false;
      const d = new Date(raw);
      if (isNaN(d.getTime())) return false;
      return d.toISOString().slice(0, 7) === prefix;
    });
  }, [allRecords, dateField, year, month]);

  // C3: 按日期索引记录（全量，供年视图用）
  const recordsByDate = useMemo(() => {
    const map = new Map<string, any[]>();
    for (const r of allRecords) {
      const raw = dateField ? r[dateField] : undefined;
      if (!raw) continue;
      const d = new Date(raw);
      if (isNaN(d.getTime())) continue;
      const key = d.toISOString().slice(0, 10);
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push({ id: r.id, title: r[titleField] ?? r.id });
    }
    return map;
  }, [allRecords, dateField, titleField]);

  /** Wave0.5 A-6：当前月索引（供月/周视图用，翻月即时生效）。 */
  const recordsByDateInMonth = useMemo(() => {
    const map = new Map<string, any[]>();
    for (const r of recordsForMonth) {
      const raw = dateField ? r[dateField] : undefined;
      if (!raw) continue;
      const d = new Date(raw);
      if (isNaN(d.getTime())) continue;
      const key = d.toISOString().slice(0, 10);
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push({ id: r.id, title: r[titleField] ?? r.id });
    }
    return map;
  }, [recordsForMonth, dateField, titleField]);

  // C3: 拖拽改期 mutation
  const updateRecordDate = useMutation({
    mutationFn: ({ id, dateFieldVal }: { id: string; dateFieldVal: string }) =>
      apiClient.put(`/collections/${collectionName}/records/${id}`, {
        [String(dateField)]: dateFieldVal,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['records', collectionName] });
    },
  });

  const handleDrop = (recordId: string, newDate: string) => {
    updateRecordDate.mutate({ id: recordId, dateFieldVal: newDate });
  };

  const todayStr = new Date().toISOString().slice(0, 10);

  if (!viewData || !collectionData) return <p>{t('calendarView.loading')}</p>;

  const prevMonth = () => setCurrentMonth(new Date(year, month - 1, 1));
  const nextMonth = () => setCurrentMonth(new Date(year, month + 1, 1));
  const todayBtn = () => setCurrentMonth(new Date());

  const monthName = currentMonth.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long' });

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto', background: 'var(--color-bg-primary)', minHeight: '100vh', padding: isMobile ? '12px' : '24px 0' }}>
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0', fontSize: isMobile ? 20 : 28 }}>{viewData.title}</h1>

      {/* C3: 视图模式切换 + 翻页 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: isMobile ? 6 : 12, marginBottom: 12, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', border: '1px solid var(--color-border-light)', borderRadius: 6, overflow: 'hidden' }}>
          {(['month', 'week', 'year'] as ViewMode[]).map((m) => (
            <button
              key={m}
              onClick={() => setViewMode(m)}
              style={{
                padding: '4px 12px',
                fontSize: 12,
                cursor: 'pointer',
                border: 'none',
                background: viewMode === m ? 'var(--color-primary-500)' : 'transparent',
                color: viewMode === m ? '#fff' : 'var(--color-text-secondary)',
              }}
            >
              {t(`calendarView.mode${m.charAt(0).toUpperCase() + m.slice(1)}`)}
            </button>
          ))}
        </div>
        {(viewMode === 'month' || viewMode === 'year') && (
          <>
            <button onClick={prevMonth} className="glass-button" style={{ padding: isMobile ? '6px 10px' : '4px 12px', fontSize: 12 }}>
              {t('calendarView.prevMonth')}
            </button>
            <button onClick={nextMonth} className="glass-button" style={{ padding: isMobile ? '6px 10px' : '4px 12px', fontSize: 12 }}>
              {t('calendarView.nextMonth')}
            </button>
            <button onClick={todayBtn} className="glass-button" style={{ padding: isMobile ? '6px 10px' : '4px 12px', fontSize: 12 }}>
              {t('calendarView.today')}
            </button>
            {(viewMode === 'month' ? monthName : `${year} 年`)}
          </>
        )}
        {viewMode === 'week' && (
          <>
            <button onClick={() => setCurrentMonth(new Date(year, month, currentMonth.getDate() - 7))} className="glass-button" style={{ padding: '4px 10px', fontSize: 12 }}>
              {t('calendarView.prevWeek')}
            </button>
            <button onClick={() => setCurrentMonth(new Date(year, month, currentMonth.getDate() + 7))} className="glass-button" style={{ padding: '4px 10px', fontSize: 12 }}>
              {t('calendarView.nextWeek')}
            </button>
            {currentMonth.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })}
          </>
        )}
      </div>

      {viewMode === 'year' ? (
        renderYearView(year, recordsByDate, isMobile)
      ) : viewMode === 'week' ? (
        renderWeekView(currentMonth, recordsByDateInMonth, todayStr, handleDrop, isMobile)
      ) : (
        isMobile
          ? renderMonthMobile(expandedDay, setExpandedDay, recordsByDateInMonth, currentMonth)
          : renderMonthDesktop(recordsByDateInMonth, currentMonth)
      )}
    </div>
  );
}

// ===================== 子视图渲染 =====================

function renderMonthDesktop(byDate: Map<string, any[]>, currentMonth: Date) {
  // Wave0.5 A-6：此前硬编码 new Date()，翻月后月格不变；改为按 currentMonth 渲染
  const viewYear = currentMonth.getFullYear();
  const viewMonth = currentMonth.getMonth();
  const firstDay = new Date(viewYear, viewMonth, 1);
  const startOffset = firstDay.getDay();
  const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
  const todayStr = new Date().toISOString().slice(0, 10);

  const weeks: { day: number; records: any[] }[][] = [];
  let currentWeek: { day: number; records: any[] }[] = [];
  for (let i = 0; i < startOffset; i++) currentWeek.push({ day: 0, records: [] });
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${viewYear}-${String(viewMonth + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    currentWeek.push({ day: d, records: byDate.get(dateStr) ?? [] });
    if (currentWeek.length === 7) { weeks.push(currentWeek); currentWeek = []; }
  }
  if (currentWeek.length) { while (currentWeek.length < 7) currentWeek.push({ day: 0, records: [] }); weeks.push(currentWeek); }

  const dayLabels = ['日', '一', '二', '三', '四', '五', '六'];

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 1, background: 'var(--color-border-light)', borderRadius: 8, overflow: 'hidden' }}>
      {dayLabels.map((d) => (
        <div key={d} style={{ background: 'rgba(255,255,255,0.02)', padding: 8, textAlign: 'center', fontWeight: 600, fontSize: 12, color: 'var(--color-text-muted)' }}>{d}</div>
      ))}
      {weeks.flat().filter((x) => x.day > 0).length === 0 ? (
        <div style={{ gridColumn: '1/-1', textAlign: 'center', padding: 48, color: 'var(--color-text-muted)' }}>暂无数据</div>
      ) : (
        weeks.map((week, wi) =>
          week.map((day, di) => {
            const key = `${wi}-${di}`;
            const isToday = day.day > 0 && `${viewYear}-${String(viewMonth + 1).padStart(2, '0')}-${String(day.day).padStart(2, '0')}` === todayStr;
            return (
              <div key={key} style={{ minHeight: 120, padding: 8, border: '1px solid var(--color-border-light)', background: isToday ? 'rgba(99,102,241,0.1)' : undefined }}>
                <div style={{ fontSize: 12, color: isToday ? 'var(--color-info)' : 'var(--color-text-muted)', marginBottom: 4 }}>{day.day > 0 ? day.day : ''}</div>
                {day.records.length === 0 ? (
                  <div style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>—</div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                    {day.records.slice(0, 3).map((r) => (
                      <div key={r.id} style={{ background: 'rgba(59,130,246,0.15)', borderLeft: '2px solid var(--color-info)', padding: '2px 6px', borderRadius: 2, fontSize: 11, color: 'var(--color-primary-200)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }} title={r.title}>{r.title}</div>
                    ))}
                    {day.records.length > 3 && <div style={{ fontSize: 10, color: 'var(--color-text-muted)' }}>{day.records.length - 3} 条更多</div>}
                  </div>
                )}
              </div>
            );
          })
        )
      )}
    </div>
  );
}

function renderMonthMobile(_expandedDay: number | null, _setExpandedDay: (n: number | null) => void, byDate: Map<string, any[]>, _currentMonth: Date) {
  // Wave0.5 A-6：byDate 已按 currentMonth 裁剪；todayStr 仍取真实"今天"用于高亮
  const todayStr = new Date().toISOString().slice(0, 10);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {Array.from(byDate.entries()).sort().map(([date, recs]) => {
        const day = parseInt(date.split('-')[2]);
        const isOpen = _expandedDay === day;
        const isToday = date === todayStr;
        return (
          <div key={date} style={{ background: isToday ? 'rgba(99,102,241,0.1)' : 'var(--color-bg-surface)', border: '1px solid var(--color-border-light)', borderRadius: 8, padding: 12 }}>
            <button onClick={() => _setExpandedDay(isOpen ? null : day)} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%', background: 'transparent', border: 'none', cursor: 'pointer', padding: 0, color: 'var(--color-text-primary)' }}>
              <span style={{ fontWeight: 600, fontSize: 14 }}>{date.slice(5)} · {recs.length} 条</span>
              <span style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>{isOpen ? '收起' : '展开'}</span>
            </button>
            {isOpen && (
              <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
                {recs.map((r) => (
                  <div key={r.id} style={{ padding: '4px 8px', background: 'rgba(59,130,246,0.1)', borderRadius: 4, fontSize: 13 }}>{r.title}</div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function renderWeekView(baseDate: Date, byDate: Map<string, any[]>, todayStr: string, _handleDrop: (_: string, __: string) => void, isMobile: boolean) {
  const weekDays = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(baseDate);
    d.setDate(d.getDate() - d.getDay() + i);
    return d;
  });
  return (
    <div style={{ display: 'grid', gridTemplateColumns: isMobile ? '1fr' : 'repeat(7, 1fr)', gap: 1, background: 'var(--color-border-light)', borderRadius: 8, overflow: 'hidden' }}>
      {weekDays.map((d, i) => {
        const dateStr = d.toISOString().slice(0, 10);
        const recs = Array.from(byDate.get(dateStr) ?? []);
        const isToday = dateStr === todayStr;
        return (
          <div key={i} style={{ padding: 8, background: isToday ? 'rgba(99,102,241,0.08)' : undefined, minHeight: 120 }}>
            <div style={{ fontSize: 11, color: 'var(--color-text-muted)', marginBottom: 4 }}>
              {d.toLocaleDateString('zh-CN', { weekday: 'short', day: 'numeric' })}
            </div>
            {recs.length === 0 ? (
              <div style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>—</div>
            ) : (
              recs.map((r) => (
                <div key={r.id} style={{ padding: '3px 6px', margin: '2px 0', background: 'rgba(59,130,246,0.15)', borderLeft: '2px solid var(--color-info)', borderRadius: 2, fontSize: 11, color: 'var(--color-primary-200)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }} title={r.title}>{r.title}</div>
              ))
            )}
          </div>
        );
      })}
    </div>
  );
}

function renderYearView(year: number, byDate: Map<string, any[]>, isMobile: boolean) {
  const months = Array.from({ length: 12 }, (_, i) => {
    const recs: Array<{ day: number; count: number }> = [];
    const daysInMonth = new Date(year, i + 1, 0).getDate();
    for (let d = 1; d <= daysInMonth; d++) {
      const dateStr = `${year}-${String(i + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
      const count = (byDate.get(dateStr) ?? []).length;
      if (count > 0) recs.push({ day: d, count });
    }
    return { index: i, name: `${i + 1} 月`, recs };
  });
  return (
    <div style={{ display: 'grid', gridTemplateColumns: isMobile ? '1fr' : 'repeat(3, 1fr)', gap: 8 }}>
      {months.map((m) => (
        <div key={m.index} style={{ background: 'var(--color-bg-surface)', border: '1px solid var(--color-border-light)', borderRadius: 8, padding: 12 }}>
          <div style={{ fontWeight: 600, marginBottom: 8, color: 'var(--color-text-primary)' }}>{m.name}</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 2, fontSize: 10, color: 'var(--color-text-muted)', textAlign: 'center' }}>
            {['日','一','二','三','四','五','六'].map((d) => <div key={d}>{d}</div>)}
            {Array.from({ length: new Date(year, m.index, 0).getDay() }, (_, i) => <div key={`empty-${i}`}></div>)}
            {Array.from({ length: new Date(year, m.index + 1, 0).getDate() }, (_, i) => {
              const day = i + 1;
              const dateStr = `${year}-${String(m.index + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
              const count = (byDate.get(dateStr) ?? []).length;
              const isToday = dateStr === new Date().toISOString().slice(0, 10);
              return (
                <div key={day} style={{ padding: 2, background: count > 0 ? 'rgba(99,102,241,0.2)' : undefined, borderRadius: 3, color: count > 0 ? 'var(--color-info)' : isToday ? 'var(--color-info)' : 'inherit', fontWeight: isToday ? 700 : undefined }}>
                  {day}
                  {count > 0 && <div style={{ fontSize: 8 }}>{count}</div>}
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}
