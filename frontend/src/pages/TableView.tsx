import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { ApiResponse, CollectionMeta, FieldDef } from '@/types/collection';
import type { TableConfig, ViewFull, SortRule } from '@/types/view';
import { applyFilters, applySort, FilterBar } from '@/components/views/FilterBar';

interface Record { id: string; [k: string]: unknown; }

/** 表格视图(US-201/202/203/206) */
export function TableViewPage() {
  const { id } = useParams<{ id: string }>();
  const [page, setPage] = useState(1);
  const [filters, setFilters] = useState<TableConfig['filters']>([]);
  const [sort, setSort] = useState<SortRule[]>([]);
  const [hiddenCols, setHiddenCols] = useState<Set<string>>(new Set());

  const { data: viewData } = useQuery({
    queryKey: ['view', id],
    queryFn: () => apiClient.get<ViewFull>(`/views/${id}`),
    enabled: !!id,
  });

  const collectionName = viewData?.data.collection_name;
  const { data: collectionData } = useQuery({
    queryKey: ['collection', collectionName],
    queryFn: () => apiClient.get<CollectionMeta>(`/collections/${collectionName}`),
    enabled: !!collectionName,
  });

  const { data: recordsData, isLoading } = useQuery({
    queryKey: ['records', collectionName],
    queryFn: () => apiClient.get<Record[]>(`/collections/${collectionName}/records?limit=500`),
    enabled: !!collectionName,
  });

  if (!viewData?.data) return <p>加载中…</p>;
  const view = viewData.data;
  const config = view.config as unknown as TableConfig;
  const fields: FieldDef[] = collectionData?.data.fields ?? [];
  const records: Record[] = recordsData?.data ?? [];

  const allColumns = config.columns ?? fields.map((f) => ({ field: f.name, label: f.label ?? f.name }));
  const visibleColumns = allColumns.filter((c) => !hiddenCols.has(c.field));

  const filtered = applyFilters(records, filters ?? []);
  const sorted = applySort(filtered, sort);
  const pageSize = config.pageSize ?? 20;
  const totalPages = Math.max(1, Math.ceil(sorted.length / pageSize));
  const pageRows = sorted.slice((page - 1) * pageSize, page * pageSize);

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto' }}>
      <Link to={`/designer/collections/${collectionName}`} style={{ color: '#64748b', fontSize: 12 }}>
        ← 返回 {collectionName}
      </Link>
      <h1>{view.title}</h1>
      <FilterBar fields={fields} filters={filters ?? []} onChange={setFilters} />
      <p style={{ fontSize: 13, color: '#64748b' }}>共 {sorted.length} 条</p>
    </div>
  );
}
