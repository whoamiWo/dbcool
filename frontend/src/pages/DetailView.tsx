import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { ApiResponse, CollectionMeta, FieldDef } from '@/types/collection';
import type { DetailConfig, ViewFull } from '@/types/view';

/** 详情视图(US-205) — 单条记录垂直展示 */
export function DetailViewPage() {
  const { id, recordId } = useParams<{ id: string; recordId: string }>();
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
  const { data: recordsData } = useQuery({
    queryKey: ['records', collectionName, 'records'],
    queryFn: () => apiClient.get<ApiResponse<Record<string, unknown>[]>>(`/collections/${collectionName}/records?limit=500`),
    enabled: !!collectionName,
  });

  if (!viewData?.data) return <p>加载中…</p>;
  const view = viewData.data;
  const config = view.config as unknown as DetailConfig;
  const fields: FieldDef[] = collectionData?.data.fields ?? [];
  const record = recordsData?.data.find((r: any) => r.id === recordId);

  if (!record) return <p>记录不存在</p>;

  const displayFields = config.fields ?? fields.map((f) => f.name);

  return (
    <div style={{ maxWidth: 800, margin: '0 auto' }}>
      <h1>{view.title}</h1>
      <div
        style={{
          background: 'white',
          padding: 24,
          borderRadius: 8,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
        }}
      >
        {displayFields
          .filter((fn) => !(config.hiddenFields ?? []).includes(fn))
          .map((fn) => {
            const f = fields.find((x) => x.name === fn);
            const val = (record as any)[fn];
            return (
              <div
                key={fn}
                style={{
                  display: 'grid',
                  gridTemplateColumns: '140px 1fr',
                  padding: '8px 0',
                  borderBottom: '1px solid #e2e8f0',
                }}
              >
                <div style={{ color: '#64748b', fontSize: 13 }}>{f?.label ?? fn}</div>
                <div style={{ fontSize: 14 }}>
                  {val == null ? <span style={{ color: '#94a3b8' }}>—</span> : String(val)}
                </div>
              </div>
            );
          })}
      </div>
    </div>
  );
}
