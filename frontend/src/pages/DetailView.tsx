import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta, FieldDef } from '@/types/collection';
import type { DetailConfig, ViewFull } from '@/types/view';

/** 详情视图(US-205) — 单条记录垂直展示 */
export function DetailViewPage() {
  const { id, recordId } = useParams<{ id: string; recordId: string }>();
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
    queryKey: ['records', collectionName, 'records'],
    queryFn: () => apiClient.get<Record<string, unknown>[] & { id: string }[]>(`/collections/${collectionName}/records?limit=500`),
    enabled: !!collectionName,
  });

  if (!viewData) return <p>加载中…</p>;
  const view = viewData;
  const config = view.config as unknown as DetailConfig;
  const fields: FieldDef[] = collectionData?.fields ?? [];
  const record = recordsData?.find((r: any) => r.id === recordId);

  if (!record) return <p>记录不存在</p>;

  const displayFields = config.fields ?? fields.map((f) => f.name);

  return (
    <div style={{ maxWidth: 800, margin: '0 auto', background: 'var(--color-bg-primary)', minHeight: '100vh', padding: '24px 0' }}>
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0' }}>{view.title}</h1>
      <div
        className="glass-card"
        style={{ padding: 24, borderRadius: 8 }}
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
                  borderBottom: '1px solid rgba(255,255,255,0.05)',
                }}
              >
                <div style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>{f?.label ?? fn}</div>
                <div style={{ fontSize: 14, color: 'var(--color-text-primary)' }}>
                  {val == null ? <span style={{ color: 'var(--color-text-muted)' }}>—</span> : String(val)}
                </div>
              </div>
            );
          })}
      </div>
    </div>
  );
}
