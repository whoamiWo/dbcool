import React, { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import type { CollectionMeta } from '@/types/collection';
import type { ViewFull, SortRule, FilterRule } from '@/types/view';
import { filtersToQuery, sortToQuery, FilterBar } from '@/components/views/FilterBar';

interface RecordRow { id: string; [k: string]: unknown; }

/** 表格视图(US-201/202/203/206) — Week 9 MVP */
export function TableViewPage() {
  const { id } = useParams<{ id: string }>();
  const [filters, setFilters] = useState<FilterRule[]>([]);
  const [sort, setSort] = useState<SortRule[]>([]);

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
  const config = (view.config ?? {}) as { columns?: Array<{ field: string; label?: string; width?: number; visible?: boolean }>; pageSize?: number };
  const fields = collectionData?.fields ?? [];
  const records = recordsData ?? [];
  const fieldMap = new Map(fields.map((f) => [f.name, f]));
  // 过滤掉 visible=false 的列(Week 15 US-206)
  type Col = { field: string; label?: string; width?: number; visible?: boolean };
  const allColumns: Col[] = config.columns ?? fields.map((f) => ({ field: f.name, label: f.label ?? f.name, width: 160, visible: true }));
  const columns = allColumns.filter((c) => c.visible !== false);

  const filtered = records; // Week 17: 服务端已 filter+sort,前端直接用

  const exportCsv = async () => {
    const token = (useAuthStore.getState().user as { accessToken?: string } | null)?.accessToken ?? '';
    const res = await fetch('/api/collections/' + collectionName + '/export?limit=5000', {
      headers: { Authorization: 'Bearer ' + token },
    });
    if (!res.ok) { alert('导出失败: ' + res.status); return; }
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
      alert('导入失败: ' + json.message);
    }
    e.target.value = '';
  };
  const sorted = filtered; // Week 17: 服务端 sort 已完成

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto' }}>
      <Link to={`/designer/collections/${collectionName}`} style={{ color: '#64748b', fontSize: 12 }}>
        ← 返回 {collectionName}
      </Link>
      <h1>{view.title}</h1>

      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        <button onClick={exportCsv} style={{ padding: '4px 12px', background: '#10b981', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>
          📥 导出 CSV
        </button>
        <label style={{ padding: '4px 12px', background: '#3b82f6', color: 'white', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>
          📤 导入 CSV
          <input type="file" accept=".csv" onChange={handleImport} style={{ display: 'none' }} />
        </label>
        {importResult && <span style={{ alignSelf: 'center', fontSize: 12, color: importResult.failed > 0 ? '#dc2626' : '#10b981' }}>
          {importResult.success}/{importResult.total} 成功{importResult.failed > 0 ? `, ${importResult.failed} 失败` : ''}
        </span>}
      </div>

      <FilterBar fields={fields} filters={filters} onChange={setFilters} />

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, fontSize: 12, color: '#64748b' }}>
        <span>点击列头排序:</span>
        {columns.map((c) => {
          const s = sort.find((x) => x.field === c.field);
          return (
            <button
              key={c.field}
              onClick={() => toggleSort(c.field, sort, setSort)}
              style={{
                padding: '2px 8px',
                background: s ? '#1e293b' : 'white',
                color: s ? 'white' : '#1e293b',
                border: '1px solid #cbd5e1',
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
        <p>加载中…</p>
      ) : (
        <div
          style={{
            background: 'white',
            borderRadius: 8,
            boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
            overflowX: 'auto',
          }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, tableLayout: 'fixed' }}>
            <colgroup>
              {columns.map((c) => (
                <col key={c.field} style={{ width: c.width ?? 160 }} />
              ))}
            </colgroup>
            <thead>
              <tr style={{ background: '#f1f5f9' }}>
                {columns.map((c) => (
                  <th key={c.field} style={{ padding: 8, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {c.label ?? c.field}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sorted.length === 0 ? (
                <tr>
                  <td colSpan={columns.length} style={{ padding: 24, textAlign: 'center', color: '#64748b' }}>
                    无数据
                  </td>
                </tr>
              ) : (
                sorted.map((r) => (
                  <tr key={r.id} style={{ borderTop: '1px solid #e2e8f0' }}>
                    {columns.map((c) => {
                      const f = fieldMap.get(c.field);
                      const val = r[c.field];
                      return (
                        <td
                          key={c.field}
                          style={{ padding: 8, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', cursor: 'pointer' }}
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
                              style={{ width: '100%', border: '1px solid #3b82f6', borderRadius: 4, padding: 2, fontSize: 13, outline: 'none' }}
                            />
                          ) : (
                            renderCell(val, f)
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}

      <p style={{ fontSize: 12, color: '#64748b', marginTop: 8 }}>
        共 {sorted.length} 条(Week 9 MVP — 列控制 UI、详情链接、列宽设置见 WEEK_9_HANDOFF.md)
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
  if (value == null) return <span style={{ color: '#94a3b8' }}>—</span>;
  if (field?.type === 'boolean') return value === 'true' || value === true ? '✓' : '✗';
  if (field?.type === 'date') return String(value).slice(0, 10);
  // Week 41 D2:关联字段展开 {id, title} 渲染
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
