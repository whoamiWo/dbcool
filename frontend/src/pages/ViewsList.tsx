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
    queryFn: async () => {
      // 后端 envelope: {code, message, data: ViewMeta[]}
      const r = await apiClient.get<{ code: number; data: ViewMeta[] }>(
        collection ? `/views?collection=${collection}` : '/views'
      );
      // 兼容 vitest mock 直接返数组 + 后端 envelope: r 是数组 OR {code, data: [...]} 
      if (Array.isArray(r)) return r;  // vitest 模式
      return r.data ?? [];  // 真后端 envelope
    },
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
              background: 'var(--color-bg-secondary)',
              color: 'var(--color-text-primary)',
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
            background: 'var(--color-text-primary)',
            borderRadius: 8,
            textAlign: 'center',
            color: 'var(--color-text-disabled)',
          }}
        >
          还没有视图
        </div>
      ) : (
        <div
          style={{
            marginTop: 16,
            background: 'var(--color-text-primary)',
            borderRadius: 8,
            boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
            overflow: 'hidden',
          }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ background: 'var(--color-bg-secondary)' }}>
                <th style={{ padding: 12, textAlign: 'left' }}>名称</th>
                <th style={{ padding: 12, textAlign: 'left' }}>类型</th>
                <th style={{ padding: 12, textAlign: 'left' }}>Collection</th>
                <th style={{ padding: 12, textAlign: 'right' }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {views.map((v) => (
                <tr key={v.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                  <td style={{ padding: 12 }}>{v.title}</td>
                  <td style={{ padding: 12 }}>
                    <span
                      style={{
                        padding: '2px 8px',
                        background: typeColor(v.type),
                        color: 'var(--color-text-primary)',
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
                      style={{ marginRight: 12, color: 'var(--color-success)', textDecoration: 'none' }}
                    >
                      打开
                    </Link>
                    <Link
                      to={`/designer/views/${v.collection_name}/${v.id}/edit`}
                      style={{ color: 'var(--color-info)', textDecoration: 'none' }}
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
  return { TABLE: 'var(--color-info)', KANBAN: 'var(--color-secondary-500)', DETAIL: 'var(--color-success)', TIMELINE: 'var(--color-warning)' }[type] ?? 'var(--color-text-disabled)';
}
