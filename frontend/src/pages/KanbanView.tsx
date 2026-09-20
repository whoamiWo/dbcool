import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta, FieldDef } from '@/types/collection';
import type { KanbanConfig, ViewFull } from '@/types/view';

interface Record { id: string; [k: string]: unknown; }

/** 看板视图(US-204) — 极简版:按字段分组 */
export function KanbanViewPage() {
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
    queryFn: () => apiClient.get<Record[]>(`/collections/${collectionName}/records?limit=500`),
    enabled: !!collectionName,
  });

  if (!viewData) return <p>加载中…</p>;
  const view = viewData;
  const config = view.config as unknown as KanbanConfig;
  const fields: FieldDef[] = collectionData?.fields ?? [];
  const records: Record[] = recordsData ?? [];

  if (!config.groupBy) return <p>看板视图未配置 groupBy 字段</p>;

  const groups = new Map<string, Record[]>();
  for (const r of records) {
    const key = String(r[config.groupBy] ?? '(空)');
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(r);
  }

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', background: 'var(--color-bg-primary)', minHeight: '100vh', padding: '24px 0' }}>
      <Link to={`/designer/collections/${collectionName}`} style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>
        ← 返回 {collectionName}
      </Link>
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0' }}>{view.title}</h1>
      <p style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>
        按 <code>{config.groupBy}</code> 分组({groups.size} 个分组)
      </p>

      <div
        style={{
          display: 'grid',
          gridAutoFlow: 'column',
          gridAutoColumns: '280px',
          gap: 12,
          overflowX: 'auto',
          paddingBottom: 12,
        }}
      >
        {Array.from(groups.entries()).map(([key, items]) => (
          <div
            key={key}
            className="glass-card"
            style={{ borderRadius: 8, padding: 12, minHeight: 200 }}
          >
            <div
              style={{
                fontWeight: 500,
                marginBottom: 8,
                paddingBottom: 8,
                borderBottom: '1px solid rgba(255, 255, 255, 0.1)',
                color: 'var(--color-text-primary)',
              }}
            >
              {key} <span style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>({items.length})</span>
            </div>
            {items.map((r) => {
              const titleField = config.cardTitleField ?? Object.keys(r).find((k) => k !== 'id');
              const title = titleField ? String(r[titleField] ?? '') : r.id.slice(0, 8);
              return (
                <div
                  key={r.id}
                  className="glass-card"
                  style={{ borderRadius: 4, padding: 8, marginBottom: 6 }}
                >
                  <div style={{ fontWeight: 500, fontSize: 13, color: 'var(--color-text-primary)' }}>{title}</div>
                  {(config.cardFields ?? []).slice(0, 3).map((f) => {
                    const field = fields.find((x) => x.name === f);
                    return (
                      <div key={f} style={{ fontSize: 11, color: 'var(--color-text-muted)', marginTop: 2 }}>
                        <strong>{field?.label ?? f}:</strong> {String(r[f] ?? '—')}
                      </div>
                    );
                  })}
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}
