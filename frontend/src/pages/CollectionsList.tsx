import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta } from '@/types/collection';

export function CollectionsListPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['collections'],
    queryFn: () =>
      apiClient.get<CollectionMeta[]>('/collections'),
  });

  if (isLoading) return <p>加载中…</p>;
  if (error) return <p style={{ color: '#dc2626' }}>加载失败</p>;

  const collections = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>📚 Collections({collections.length})</h1>
        <Link
          to="/designer/schemas"
          style={{
            padding: '8px 16px',
            background: '#1e293b',
            color: 'white',
            textDecoration: 'none',
            borderRadius: 4,
          }}
        >
          + 新建 Collection
        </Link>
      </div>

      {collections.length === 0 ? (
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
          还没有 Collection,点上方"新建 Collection"开始
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
                <th style={{ padding: 12, textAlign: 'left' }}>标题</th>
                <th style={{ padding: 12, textAlign: 'left' }}>字段数</th>
                <th style={{ padding: 12, textAlign: 'left' }}>创建时间</th>
                <th style={{ padding: 12 }}></th>
              </tr>
            </thead>
            <tbody>
              {collections.map((c) => {
                const fieldCount = c.fields_json
                  ? (JSON.parse(c.fields_json) as unknown[]).length
                  : 0;
                return (
                  <tr key={c.id} style={{ borderTop: '1px solid #e2e8f0' }}>
                    <td style={{ padding: 12, fontFamily: 'monospace' }}>{c.name}</td>
                    <td style={{ padding: 12 }}>{c.title}</td>
                    <td style={{ padding: 12 }}>{fieldCount}</td>
                    <td style={{ padding: 12, color: '#64748b' }}>
                      {new Date(c.created_at).toLocaleString('zh-CN')}
                    </td>
                    <td style={{ padding: 12, textAlign: 'right' }}>
                      <Link
                        to={`/designer/collections/${c.name}`}
                        style={{ color: '#2563eb', textDecoration: 'none' }}
                      >
                        打开 →
                      </Link>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
