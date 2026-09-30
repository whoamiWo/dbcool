import React, { useState, useMemo, useRef } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';
import { useVirtualizer } from '@tanstack/react-virtual';
import apiClient from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { useIsMobile } from '@/hooks/useIsMobile';
import type { CollectionMeta } from '@/types/collection';
import type { ViewFull, SortRule, FilterRule, TableConfig } from '@/types/view';
import { filtersToQuery, sortToQuery, FilterBar } from '@/components/views/FilterBar';

interface RecordRow { id: string; [k: string]: unknown; }
interface GroupedRow { _groupKey: string; _count: number; _aggregations: Record<string, unknown>; _children: RecordRow[]; }

/** 表格视图 (US-201/202/203/206) — Week 9 MVP + R2 T2 group by */
export function TableViewPage() {
  const { id } = useParams<{ id: string }>();
  const [filters, setFilters] = useState<FilterRule[]>([]);
  const [sort, setSort] = useState<SortRule[]>([]);
  const isMobile = useIsMobile();
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

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

  const queryClient = useQueryClient();
  const [editing, setEditing] = React.useState<{ id: string; field: string } | null>(null);

  const updateField = useMutation({
    mutationFn: ({ id, field, value }: { id: string; field: string; value: unknown }) =>
      apiClient.put(`/collections/${collectionName}/records/${id}`, { [field]: value }),
    onMutate: async ({ id, field, value }) => {
      await queryClient.cancelQueries({ queryKey: ['records', collectionName] });
      const prev = queryClient.getQueryData<RecordRow[]>(['records', collectionName]);
      queryClient.setQueryData<RecordRow[]>(['records', collectionName], (old) =>
        old ? old.map((r) => (r.id === id ? { ...r, [field]: value } : r)) : old
      );
      return { prev, id, field };
    },
    onError: (_err, _vars, context) => {
      if (context?.prev) queryClient.setQueryData(['records', collectionName], context.prev);
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['records', collectionName] });
    },
  });

  const { data: recordsData, isLoading } = useQuery({
    queryKey: ['records', collectionName, filters, sort],
    queryFn: () => {
      const params = new URLSearchParams();
      params.set('limit', '500');
      const sq = sortToQuery(sort);
      const fq = filtersToQuery(filters);
      if (sq) params.set('sort', sq);
      if (fq) params.set('filter', fq);
      return apiClient.get<RecordRow[]>(`/collections/${collectionName}/records?${params}`);
    },
    enabled: !!collectionName,
  });

  if (!viewData) return <p>加载中…</p>;
  const view = viewData;
  const config = (view.config ?? {}) as unknown as TableConfig;
  const fields = collectionData?.fields ?? [];
  const records = recordsData ?? [];
  const fieldMap = new Map(fields.map((f) => [f.name, f]));
  
  // R2 T2: 分组配置
  const groupByField = config.groupBy;
  const groupAggregations = config.groupAggregations ?? [];
  const nonGroupableTypes = ['formula', 'rollup', 'lookup', 'attachment', 'hasMany'];
  const groupableFields = fields.filter(f => !nonGroupableTypes.includes(f.type));

  // 过滤掉 visible=false 的列 (Week 15 US-206)
  type Col = { field: string; label?: string; width?: number; visible?: boolean };
  const allColumns: Col[] = config.columns ?? fields.map((f) => ({ field: f.name, label: f.label ?? f.name, width: 160, visible: true }));
  const columns = allColumns.filter((c) => c.visible !== false);

  // R2 T2: 分组渲染数据
  const groupedData = useMemo(() => {
    if (!groupByField) return null;
    
    const groups = new Map<string, RecordRow[]>();
    for (const r of records) {
      const key = String(r[groupByField] ?? '__empty__');
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(r);
    }

    const result: GroupedRow[] = [];
    for (const [key, groupRecords] of groups.entries()) {
      const aggregations: Record<string, unknown> = {};
      for (const agg of groupAggregations) {
        const values = groupRecords.map(r => Number(r[agg.field]) || 0);
        switch (agg.operator) {
          case 'sum':
            aggregations[agg.field] = values.reduce((a, b) => a + b, 0);
            break;
          case 'avg':
            aggregations[agg.field] = values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : 0;
            break;
          case 'min':
            aggregations[agg.field] = values.length > 0 ? Math.min(...values) : 0;
            break;
          case 'max':
            aggregations[agg.field] = values.length > 0 ? Math.max(...values) : 0;
            break;
        }
      }
      result.push({
        _groupKey: key,
        _count: groupRecords.length,
        _aggregations: aggregations,
        _children: groupRecords,
      });
    }
    return result;
  }, [groupByField, records, groupAggregations]);

  const filtered = groupedData ? [] : records; // R2 T2: 分组模式下用分组数据
  const sorted = filtered; // Server-side sorting applied

  // R3 T4: Virtual scrolling for large datasets
  const parentRef = useRef<HTMLDivElement>(null);
  const ROW_HEIGHT = 40;
  const VIRTUAL_THRESHOLD = 100; // Enable virtualization when rows >= this
  const shouldVirtualize = !groupByField && sorted.length >= VIRTUAL_THRESHOLD;
  
  const virtualizer = useVirtualizer({
    count: sorted.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 5,
    enabled: shouldVirtualize,
  });

  const exportCsv = async () => {
    const token = (useAuthStore.getState().user as { accessToken?: string } | null)?.accessToken ?? '';
    const res = await fetch('/api/collections/' + collectionName + '/export?limit=5000', {
      headers: { Authorization: 'Bearer ' + token },
    });
    if (!res.ok) { alert('导出失败：' + res.status); return; }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = collectionName + '_' + new Date().toISOString().slice(0, 10) + '.csv';
    a.click();
    URL.revokeObjectURL(url);
  };

  const [importResult, setImportResult] = React.useState<{ total: number; success: number; failed: number } | null>(null);

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file);
    const token = (useAuthStore.getState().user as { accessToken?: string } | null)?.accessToken ?? '';
    const res = await fetch('/api/collections/' + collectionName + '/import', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + token },
      body: fd,
    });
    const json = await res.json();
    if (json.code === 0) {
      setImportResult(json.data);
      queryClient.invalidateQueries({ queryKey: ['records', collectionName] });
    } else {
      alert('导入失败：' + json.message);
    }
    e.target.value = '';
  };

  const toggleGroup = (key: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const expandAll = () => {
    if (groupedData) setExpandedGroups(new Set(groupedData.map(g => g._groupKey)));
  };

  const collapseAll = () => {
    setExpandedGroups(new Set());
  };

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto', background: 'var(--color-bg-primary)', minHeight: '100vh', padding: '24px 0' }}>
      <Link to={`/designer/collections/${collectionName}`} style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>
        ← 返回 {collectionName}
      </Link>
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0' }}>{view.title}</h1>

      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        <button onClick={exportCsv} className="glass-button-primary" style={{ padding: '4px 12px', fontSize: 12 }}>
          📥 导出 CSV
        </button>
        <label style={{ padding: '4px 12px', background: 'rgba(59, 130, 246, 0.2)', color: 'var(--color-text-primary)', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>
          📤 导入 CSV
          <input type="file" accept=".csv" onChange={handleImport} style={{ display: 'none' }} />
        </label>
        {importResult && <span style={{ alignSelf: 'center', fontSize: 12, color: importResult.failed > 0 ? 'var(--color-error)' : 'var(--color-success)' }}>
          {importResult.success}/{importResult.total} 成功{importResult.failed > 0 ? `, ${importResult.failed} 失败` : ''}
        </span>}
      </div>

      {groupByField && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, fontSize: 12 }}>
          <span>分组:</span>
          <select
            value={groupByField}
            onChange={(e) => {
              const newConfig: TableConfig = { ...config, groupBy: e.target.value };
              apiClient.put(`/views/${id}`, { config: newConfig });
            }}
            style={{ padding: '4px 8px', borderRadius: 4, background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', border: '1px solid var(--color-border-light)' }}
          >
            <option value="">无分组</option>
            {groupableFields.map(f => (
              <option key={f.name} value={f.name}>{f.label ?? f.name}</option>
            ))}
          </select>
          <button onClick={expandAll} className="glass-button" style={{ padding: '2px 8px', fontSize: 11 }}>全部展开</button>
          <button onClick={collapseAll} className="glass-button" style={{ padding: '2px 8px', fontSize: 11 }}>全部折叠</button>
        </div>
      )}

      <FilterBar fields={fields} filters={filters} onChange={setFilters} />

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, fontSize: 12, color: 'var(--color-text-muted)' }}>
        <span>点击列头排序:</span>
        {columns.map((c) => {
          const s = sort.find((x) => x.field === c.field);
          return (
            <button
              key={c.field}
              onClick={() => toggleSort(c.field, sort, setSort)}
              style={{
                padding: '2px 8px',
                background: s ? 'rgba(99, 102, 241, 0.3)' : 'var(--color-border-light)',
                color: s ? 'var(--color-text-primary)' : 'var(--color-text-secondary)',
                border: '1px solid var(--color-border-light)',
                borderRadius: 4,
                cursor: 'pointer',
                fontSize: 12,
              }}
            >
              {c.label ?? c.field} {s ? (s.direction === 'asc' ? '↑' : '↓') : ''}
            </button>
          );
        })}
      </div>

      {isLoading ? (
        <p style={{ color: 'var(--color-text-muted)' }}>加载中…</p>
      ) : (
        <div
          className={isMobile ? 'table-view-container glass-card' : 'glass-card'}
          style={{ borderRadius: 8, overflowX: 'auto' }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, tableLayout: 'fixed' }}>
            <colgroup>
              {columns.map((c) => (
                <col key={c.field} style={{ width: c.width ?? 160 }} />
              ))}
            </colgroup>
            <thead>
              <tr style={{ background: 'rgba(30, 41, 59, 0.5)' }}>
                {groupByField && <th style={{ padding: 8, textAlign: 'left', minWidth: 60, color: 'var(--color-text-secondary)', borderBottom: '1px solid var(--color-border-light)' }}>📊</th>}
                {columns.map((c) => (
                  <th key={c.field} style={{ padding: 8, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', color: 'var(--color-text-secondary)', borderBottom: '1px solid var(--color-border-light)' }}>
                    {c.label ?? c.field}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {(!groupByField ? sorted : groupedData ?? []).length === 0 ? (
                <tr>
                  <td colSpan={columns.length + (groupByField ? 1 : 0)} style={{ padding: 24, textAlign: 'center', color: 'var(--color-text-muted)' }}>
                    无数据
                  </td>
                </tr>
              ) : (
                <>
                  {!groupByField && shouldVirtualize ? (
                    // R3 T4: Virtualized rendering for large datasets
                    <div
                      ref={parentRef}
                      style={{
                        height: `${virtualizer.getTotalSize()}px`,
                        width: '100%',
                        position: 'relative',
                      }}
                    >
                      <div
                        style={{
                          position: 'absolute',
                          top: 0,
                          left: 0,
                          width: '100%',
                          transform: `translateY(${virtualizer.getVirtualItems()[0]?.start ?? 0}px)`,
                        }}
                      >
                        {virtualizer.getVirtualItems().map((virtualRow) => {
                          const r = sorted[virtualRow.index];
                          return (
                            <tr
                              key={r.id}
                              style={{
                                position: 'absolute',
                                top: 0,
                                left: 0,
                                width: '100%',
                                height: `${virtualRow.size}px`,
                                borderTop: '1px solid var(--color-border-light)',
                              }}
                            >
                              {columns.map((c) => {
                                const f = fieldMap.get(c.field);
                                const val = r[c.field];
                                return (
                                  <td
                                    key={c.field}
                                    style={{ padding: 8, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', cursor: 'pointer', color: 'var(--color-text-secondary)' }}
                                    onDoubleClick={() => {
                                      setEditing({ id: r.id, field: c.field });
                                    }}
                                  >
                                    {editing?.id === r.id && editing?.field === c.field ? (
                                      <input
                                        autoFocus
                                        defaultValue={String(val ?? '')}
                                        onBlur={(e) => {
                                          const newVal = e.currentTarget.value;
                                          updateField.mutate({ id: r.id, field: c.field, value: newVal });
                                          setEditing(null);
                                        }}
                                        onKeyDown={(e) => {
                                          if (e.key === 'Enter') {
                                            e.preventDefault();
                                            const newVal = (e.currentTarget as HTMLInputElement).value;
                                            updateField.mutate({ id: r.id, field: c.field, value: newVal });
                                            setEditing(null);
                                          }
                                          if (e.key === 'Escape') setEditing(null);
                                        }}
                                        className="glass-input"
                                        style={{ width: '100%', fontSize: 13 }}
                                      />
                                    ) : (
                                      renderCell(val, f)
                                    )}
                                  </td>
                                );
                              })}
                            </tr>
                          );
                        })}
                      </div>
                    </div>
                  ) : !groupByField && sorted.map((r) => (
                    <tr key={r.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                      {columns.map((c) => {
                        const f = fieldMap.get(c.field);
                        const val = r[c.field];
                        return (
                          <td
                            key={c.field}
                            style={{ padding: 8, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', cursor: 'pointer', color: 'var(--color-text-secondary)' }}
                            onDoubleClick={() => {
                              setEditing({ id: r.id, field: c.field });
                            }}
                          >
                            {editing?.id === r.id && editing?.field === c.field ? (
                              <input
                                autoFocus
                                defaultValue={String(val ?? '')}
                                onBlur={(e) => {
                                  const newVal = e.currentTarget.value;
                                  updateField.mutate({ id: r.id, field: c.field, value: newVal });
                                  setEditing(null);
                                }}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter') {
                                    e.preventDefault();
                                    const newVal = (e.currentTarget as HTMLInputElement).value;
                                    updateField.mutate({ id: r.id, field: c.field, value: newVal });
                                    setEditing(null);
                                  }
                                  if (e.key === 'Escape') setEditing(null);
                                }}
                                className="glass-input"
                                style={{ width: '100%', fontSize: 13 }}
                              />
                            ) : (
                              renderCell(val, f)
                            )}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                  {groupByField && groupedData?.map((g) => (
                    <React.Fragment key={g._groupKey}>
                      <tr style={{ background: 'rgba(99, 102, 241, 0.1)', borderTop: '1px solid var(--color-border-light)' }}>
                        <td style={{ padding: 8, cursor: 'pointer', fontWeight: 600 }} onClick={() => toggleGroup(g._groupKey)}>
                          {expandedGroups.has(g._groupKey) ? '▼' : '▶'}
                        </td>
                        {columns.map((c) => {
                          if (c.field === groupByField) {
                            return <td key={c.field} style={{ padding: 8, fontWeight: 600 }}>{g._groupKey}</td>;
                          }
                          if (g._aggregations.hasOwnProperty(c.field)) {
                            return <td key={c.field} style={{ padding: 8, color: 'var(--color-info)' }}>{formatAgg(g._aggregations[c.field])}</td>;
                          }
                          return <td key={c.field} style={{ padding: 8, color: 'var(--color-text-muted)' }}>—</td>;
                        })}
                        <td style={{ padding: 8, fontWeight: 600, color: 'var(--color-info)' }}>{g._count} 条</td>
                      </tr>
                      {expandedGroups.has(g._groupKey) && g._children.map((r) => (
                        <tr key={r.id} style={{ borderTop: '1px solid var(--color-border-light)', background: 'rgba(0,0,0,0.02)' }}>
                          <td style={{ padding: 8 }}></td>
                          {columns.map((c) => {
                            const f = fieldMap.get(c.field);
                            const val = r[c.field];
                            return (
                              <td
                                key={c.field}
                                style={{ padding: 8, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', cursor: 'pointer', color: 'var(--color-text-secondary)' }}
                                onDoubleClick={() => {
                                  setEditing({ id: r.id, field: c.field });
                                }}
                              >
                                {editing?.id === r.id && editing?.field === c.field ? (
                                  <input
                                    autoFocus
                                    defaultValue={String(val ?? '')}
                                    onBlur={(e) => {
                                      const newVal = e.currentTarget.value;
                                      updateField.mutate({ id: r.id, field: c.field, value: newVal });
                                      setEditing(null);
                                    }}
                                    onKeyDown={(e) => {
                                      if (e.key === 'Enter') {
                                        e.preventDefault();
                                        const newVal = (e.currentTarget as HTMLInputElement).value;
                                        updateField.mutate({ id: r.id, field: c.field, value: newVal });
                                        setEditing(null);
                                      }
                                      if (e.key === 'Escape') setEditing(null);
                                    }}
                                    className="glass-input"
                                    style={{ width: '100%', fontSize: 13 }}
                                  />
                                ) : (
                                  renderCell(val, f)
                                )}
                              </td>
                            );
                          })}
                          <td style={{ padding: 8 }}></td>
                        </tr>
                      ))}
                    </React.Fragment>
                  ))}
                </>
              )}
            </tbody>
          </table>
        </div>
      )}

      <p style={{ fontSize: 12, color: 'var(--color-text-muted)', marginTop: 8 }}>
        共 {(groupByField ? (groupedData?.length ?? 0) : sorted.length)} 条{(groupByField ? ` (${groupedData?.reduce((a, g) => a + g._count, 0)} 记录)` : '')}
      </p>
    </div>
  );
}

function toggleSort(field: string, sort: SortRule[], setSort: (s: SortRule[]) => void) {
  const existing = sort.find((x) => x.field === field);
  if (!existing) setSort([...sort, { field, direction: 'asc' }]);
  else if (existing.direction === 'asc')
    setSort(sort.map((x) => (x.field === field ? { ...x, direction: 'desc' as const } : x)));
  else setSort(sort.filter((x) => x.field !== field));
}

function renderCell(value: unknown, field?: { type?: string }): React.ReactNode {
  if (value == null) return <span style={{ color: 'var(--color-text-muted)' }}>—</span>;
  if (field?.type === 'boolean') return value === 'true' || value === true ? '✓' : '✗';
  if (field?.type === 'date') return String(value).slice(0, 10);
  if (field?.type === 'belongsTo' && typeof value === 'object') {
    const obj = value as { id?: string; title?: string };
    return <span title={obj.id ?? ''}>{obj.title ?? obj.id ?? '—'}</span>;
  }
  if (field?.type === 'hasMany' && Array.isArray(value)) {
    const titles = (value as Array<{ title?: string }>)
      .map((v) => v.title ?? '—').join(', ');
    return <span title={titles}>{titles || '—'}</span>;
  }
  return String(value);
}

function formatAgg(value: unknown): string {
  if (typeof value === 'number') {
    if (value === Math.floor(value)) return String(Math.round(value));
    return value.toFixed(2);
  }
  return String(value ?? '—');
}

