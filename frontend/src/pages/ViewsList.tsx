import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { } from '@/types/collection';
import type { ViewMeta } from '@/types/view';

/** 视图列表 — Week 9 */
export function ViewsListPage() {
  const { collection } = useParams<{ collection?: string }>();
  const { data, isLoading } = useQuery({
    queryKey: ['views', collection ?? 'all'],
    queryFn: () =>
      apiClient.get<ViewMeta[]>(
        collection ? `/views?collection=${collection}` : '/views'
      ),
  });

  if (isLoading) return <p>加载中…</p>;

  const views = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h1>📊 视图{collection ? ` (${collection})` : ''}({views.length})</h1>
        {collection && (
          <Link
            to={`/designer/views/${collection}/new`}
            style={{
              padding: '8px 16px',
              background: '#1e293b',
              color: 'white',
              textDecoration: 'none',
              borderRadius: 4,
            }}
          >
            + 新建视图
          </Link>
        )}
      </div>

      {views.length === 0 ? (
        <div
          style={{
            marginTop: 24,
            padding: 32,
            background: 'white',
            borderRadius: 8,
            textAlign: 'center',
            color: '#64748b',
          }}
        >
          还没有视图
        </div>
      ) : (
        <div
          style={{
            marginTop: 16,
            background: 'white',
            borderRadius: 8,
            boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
            overflow: 'hidden',
          }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ background: '#f1f5f9' }}>
                <th style={{ padding: 12, textAlign: 'left' }}>名称</th>
                <th style={{ padding: 12, textAlign: 'left' }}>类型</th>
                <th style={{ padding: 12, textAlign: 'left' }}>Collection</th>
                <th style={{ padding: 12, textAlign: 'right' }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {views.map((v) => (
                <tr key={v.id} style={{ borderTop: '1px solid #e2e8f0' }}>
                  <td style={{ padding: 12 }}>{v.title}</td>
                  <td style={{ padding: 12 }}>
                    <span
                      style={{
                        padding: '2px 8px',
                        background: typeColor(v.type),
                        color: 'white',
                        borderRadius: 4,
                        fontSize: 11,
                      }}
                    >
                      {v.type}
                    </span>
                  </td>
                  <td style={{ padding: 12, fontFamily: 'monospace' }}>{v.collection_name}</td>
                  <td style={{ padding: 12, textAlign: 'right' }}>
                    <Link
                      to={`/views/${v.id}/run`}
                      style={{ marginRight: 12, color: '#16a34a', textDecoration: 'none' }}
                    >
                      打开
                    </Link>
                    <Link
                      to={`/designer/views/${v.collection_name}/${v.id}/edit`}
                      style={{ color: '#2563eb', textDecoration: 'none' }}
                    >
                      编辑
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function typeColor(type: string): string {
  return { TABLE: '#3b82f6', KANBAN: '#8b5cf6', DETAIL: '#10b981' }[type] ?? '#64748b';
}
