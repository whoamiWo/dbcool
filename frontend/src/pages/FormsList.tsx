import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { } from '@/types/collection';
import type { FormMeta } from '@/types/form';

export function FormsListPage() {
  const { collection } = useParams<{ collection?: string }>();
  const { data, isLoading, error } = useQuery({
    queryKey: ['forms', collection ?? 'all'],
    queryFn: () =>
      apiClient.get<FormMeta[]>(
        collection ? `/forms?collection=${collection}` : '/forms'
      ),
  });

  if (isLoading) return <p>加载中…</p>;
  if (error) return <p style={{ color: '#dc2626' }}>加载失败</p>;

  const forms = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>📋 表单{collection ? ` (${collection})` : ''}({forms.length})</h1>
        {collection && (
          <Link
            to={`/designer/forms/${collection}/new`}
            style={{
              padding: '8px 16px',
              background: '#1e293b',
              color: 'white',
              textDecoration: 'none',
              borderRadius: 4,
            }}
          >
            + 新建表单
          </Link>
        )}
      </div>

      {forms.length === 0 ? (
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
          {collection ? '该 collection 还没有表单,点上方"新建表单"开始' : '你还没有创建任何表单'}
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
                <th style={{ padding: 12, textAlign: 'left' }}>标题</th>
                <th style={{ padding: 12, textAlign: 'left' }}>Collection</th>
                <th style={{ padding: 12, textAlign: 'left' }}>创建时间</th>
                <th style={{ padding: 12, textAlign: 'right' }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {forms.map((f) => (
                <tr key={f.id} style={{ borderTop: '1px solid #e2e8f0' }}>
                  <td style={{ padding: 12 }}>{f.title}</td>
                  <td style={{ padding: 12, fontFamily: 'monospace' }}>{f.collection_name}</td>
                  <td style={{ padding: 12, color: '#64748b' }}>
                    {new Date(f.created_at).toLocaleString('zh-CN')}
                  </td>
                  <td style={{ padding: 12, textAlign: 'right' }}>
                    <Link
                      to={`/designer/forms/${f.collection_name}/${f.id}/edit`}
                      style={{ marginRight: 12, color: '#2563eb', textDecoration: 'none' }}
                    >
                      编辑
                    </Link>
                    <Link
                      to={`/forms/${f.id}/fill`}
                      style={{ color: '#16a34a', textDecoration: 'none' }}
                    >
                      填表
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
