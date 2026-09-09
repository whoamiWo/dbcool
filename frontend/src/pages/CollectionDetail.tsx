import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { ApiResponse, CollectionMeta } from '@/types/collection';
import type { FormMeta } from '@/types/form';

interface Record {
  id: string;
  [key: string]: unknown;
}

export function CollectionDetailPage() {
  const { name } = useParams<{ name: string }>();
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState<Record<string, string>>({});

  const { data: metaData, isLoading: metaLoading } = useQuery({
    queryKey: ['collection', name],
    queryFn: () =>
      apiClient.get<ApiResponse<CollectionMeta>>(`/collections/${name}`),
    enabled: !!name,
  });

  const { data: recordsData, isLoading: recordsLoading } = useQuery({
    queryKey: ['collection', name, 'records'],
    queryFn: () =>
      apiClient.get<ApiResponse<Record[]>>(`/collections/${name}/records?limit=50`),
    enabled: !!name,
  });

  const createRecordMutation = useMutation({
    mutationFn: async (data: Record<string, unknown>) => {
      return apiClient.post<ApiResponse<Record>>(`/collections/${name}/records`, data);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['collection', name, 'records'] });
      setShowForm(false);
      setFormData({});
    },
  });

  if (metaLoading) return <p>加载中…</p>;
  if (!metaData) return <p>Collection 不存在</p>;

  const meta = metaData.data;
  const fields = meta.fields ?? [];
  const records = recordsData?.data ?? [];

  const handleSubmitRecord = () => {
    const data: Record<string, unknown> = {};
    for (const f of fields) {
      const v = formData[f.name];
      if (v !== undefined && v !== '') {
        data[f.name] = f.type === 'number' ? Number(v) : v;
      }
    }
    createRecordMutation.mutate(data);
  };

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Link to="/designer/schemas" style={{ color: '#64748b', textDecoration: 'none' }}>
          ← 返回列表
        </Link>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>
          📋 {meta.title}{' '}
          <span style={{ color: '#64748b', fontSize: 14, fontWeight: 'normal' }}>({meta.name})</span>
        </h1>
        <div style={{ display: 'flex', gap: 8 }}>
          <Link
            to={`/designer/schemas/${meta.name}/edit`}
            style={{
              padding: '8px 16px',
              background: '#475569',
              color: 'white',
              textDecoration: 'none',
              borderRadius: 4,
            }}
          >
            ✏️ 编辑 Schema
          </Link>
          <Link
            to={`/designer/forms/${meta.name}/new`}
            style={{
              padding: '8px 16px',
              background: '#0891b2',
              color: 'white',
              textDecoration: 'none',
              borderRadius: 4,
            }}
          >
            📝 建表单
          </Link>
          <button
            onClick={() => setShowForm(!showForm)}
            style={{
              padding: '8px 16px',
              background: '#1e293b',
              color: 'white',
              border: 'none',
              borderRadius: 4,
              cursor: 'pointer',
            }}
          >
            {showForm ? '取消' : '+ 添加记录'}
          </button>
        </div>
      </div>

      {/* 表单列表(Week 8 新增) */}
      <FormsListForCollection collectionName={meta.name} />

      {showForm && (
        <div
          style={{
            marginTop: 16,
            padding: 16,
            background: 'white',
            borderRadius: 8,
            boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          }}
        >
          <h3 style={{ marginTop: 0 }}>新记录</h3>
          {fields.map((f) => (
            <div key={f.name} style={{ marginBottom: 12 }}>
              <label style={{ display: 'block', marginBottom: 4 }}>
                {f.label ?? f.name} {f.required && <span style={{ color: '#dc2626' }}>*</span>}
                <span style={{ color: '#64748b', fontSize: 12, marginLeft: 8 }}>({f.type})</span>
              </label>
              <input
                value={formData[f.name] ?? ''}
                onChange={(e) =>
                  setFormData({ ...formData, [f.name]: e.target.value })
                }
                style={{ padding: 8, fontSize: 14, width: '100%', maxWidth: 400 }}
              />
            </div>
          ))}
          <button
            onClick={handleSubmitRecord}
            disabled={createRecordMutation.isPending}
            style={{
              padding: '8px 16px',
              background: createRecordMutation.isPending ? '#94a3b8' : '#1e293b',
              color: 'white',
              border: 'none',
              borderRadius: 4,
              cursor: createRecordMutation.isPending ? 'not-allowed' : 'pointer',
            }}
          >
            {createRecordMutation.isPending ? '提交中…' : '提交'}
          </button>
        </div>
      )}

      <div
        style={{
          marginTop: 16,
          padding: 16,
          background: 'white',
          borderRadius: 8,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
        }}
      >
        <h3 style={{ marginTop: 0 }}>记录({records.length})</h3>
        {recordsLoading ? (
          <p>加载中…</p>
        ) : records.length === 0 ? (
          <p style={{ color: '#64748b' }}>暂无记录</p>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ background: '#f1f5f9' }}>
                  <th style={{ padding: 8, textAlign: 'left' }}>ID</th>
                  {fields.map((f) => (
                    <th key={f.name} style={{ padding: 8, textAlign: 'left' }}>
                      {f.label ?? f.name}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {records.map((r) => (
                  <tr key={r.id} style={{ borderTop: '1px solid #e2e8f0' }}>
                    <td style={{ padding: 8, fontFamily: 'monospace', fontSize: 12 }}>
                      {r.id?.toString().slice(0, 8)}…
                    </td>
                    {fields.map((f) => (
                      <td key={f.name} style={{ padding: 8 }}>
                        {String(r[f.name] ?? '')}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * 该 Collection 的表单列表(Week 8 新增)
 */
function FormsListForCollection({ collectionName }: { collectionName: string }) {
  const { data } = useQuery({
    queryKey: ['forms', collectionName],
    queryFn: () =>
      apiClient.get<ApiResponse<FormMeta[]>>(`/forms?collection=${collectionName}`),
  });

  const forms = data?.data ?? [];
  if (forms.length === 0) return null;

  return (
    <div
      style={{
        marginTop: 16,
        padding: 16,
        background: 'white',
        borderRadius: 8,
        boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
      }}
    >
      <h3 style={{ marginTop: 0 }}>📋 关联表单({forms.length})</h3>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {forms.map((f) => (
          <Link
            key={f.id}
            to={`/forms/${f.id}/fill`}
            style={{
              padding: '8px 12px',
              background: '#ecfeff',
              border: '1px solid #0891b2',
              borderRadius: 4,
              color: '#0e7490',
              textDecoration: 'none',
            }}
          >
            {f.title}
          </Link>
        ))}
      </div>
    </div>
  );
}