import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta } from '@/types/collection';
import type { ViewFull, SortRule, FilterRule } from '@/types/view';
import { applyFilters, applySort, FilterBar } from '@/components/views/FilterBar';

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

  const { data: recordsData, isLoading } = useQuery({
    queryKey: ['records', collectionName],
    queryFn: () => apiClient.get<RecordRow[]>(`/collections/${collectionName}/records?limit=500`),
    enabled: !!collectionName,
  });

  if (!viewData) return <p>加载中…</p>;
  const view = viewData;
  const config = (view.config ?? {}) as { columns?: Array<{ field: string; label?: string }>; pageSize?: number };
  const fields = collectionData?.fields ?? [];
  const records = recordsData ?? [];
  const fieldMap = new Map(fields.map((f) => [f.name, f]));
  const columns = config.columns ?? fields.map((f) => ({ field: f.name, label: f.label ?? f.name }));

  const filtered = applyFilters(records, filters);
  const sorted = applySort(filtered, sort);

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto' }}>
      <Link to={`/designer/collections/${collectionName}`} style={{ color: '#64748b', fontSize: 12 }}>
        ← 返回 {collectionName}
      </Link>
      <h1>{view.title}</h1>

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
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ background: '#f1f5f9' }}>
                {columns.map((c) => (
                  <th key={c.field} style={{ padding: 8, textAlign: 'left' }}>
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
                        <td key={c.field} style={{ padding: 8 }}>
                          {renderCell(val, f)}
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
  return String(value);
}
