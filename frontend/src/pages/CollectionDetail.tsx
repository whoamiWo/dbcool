import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta } from '@/types/collection';
import type { FormMeta } from '@/types/form';
import type { ViewMeta } from '@/types/view';

type RecordRow = { id: string } & Record<string, unknown>;

export function CollectionDetailPage() {
  const { name } = useParams<{ name: string }>();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [showForm, setShowForm] = useState(false);
  const [formData, setFormData] = useState<Record<string, string>>({});

  const { data: metaData, isLoading: metaLoading } = useQuery({
    queryKey: ['collection', name],
    queryFn: () => apiClient.get<CollectionMeta>(`/collections/${name}`),
    enabled: !!name,
  });

  const { data: formsData } = useQuery({
    queryKey: ['forms', name],
    queryFn: () => apiClient.get<FormMeta[]>(`/forms?collection=${name}`),
    enabled: !!name,
  });

  const { data: viewsData } = useQuery({
    queryKey: ['views', name],
    queryFn: () => apiClient.get<ViewMeta[]>(`/views?collection=${name}`),
    enabled: !!name,
  });

  const { data: recordsData } = useQuery({
    queryKey: ['records', name],
    queryFn: () => apiClient.get<RecordRow[]>(`/collections/${name}/records?limit=50`),
    enabled: !!name,
  });

  const createRecordMutation = useMutation({
    mutationFn: async (data: RecordRow) => {
      return apiClient.post<{ id: string }>(`/collections/${name}/records`, data);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['records', name] });
      setShowForm(false);
      setFormData({});
    },
  });

  if (metaLoading) return <p>加载中…</p>;
  if (!metaData) return <p>Collection 不存在</p>;
  const meta = metaData;
  const fields = meta.fields ?? [];
  const forms = formsData ?? [];
  const views = viewsData ?? [];
  const records = recordsData ?? [];

  const handleSubmitRecord = () => {
    const data: Record<string, unknown> = {};
    for (const f of fields) {
      const v = formData[f.name];
      if (v !== undefined && v !== '') {
        data[f.name] = f.type === 'number' ? Number(v) : v;
      }
    }
    createRecordMutation.mutate(data as RecordRow);
  };

  return (
    <div>
      <button onClick={() => navigate(`/designer/collections/${name}`)} style={{ background: 'none', border: 'none', color: 'var(--color-text-disabled)', cursor: 'pointer', marginBottom: 16 }}>← 返回</button>
      <h1>
        📋 {meta.title} <span style={{ color: 'var(--color-text-disabled)', fontSize: 14, fontWeight: 'normal' }}>({meta.name})</span>
      </h1>
      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        <Link to={`/designer/schemas/${meta.name}/edit`} style={{ padding: '8px 16px', background: 'var(--color-bg-elevated)', color: 'var(--color-text-primary)', textDecoration: 'none', borderRadius: 4 }}>✏️ 编辑 Schema</Link>
        <Link to={`/designer/forms/${meta.name}/new`} style={{ padding: '8px 16px', background: 'var(--color-info)', color: 'var(--color-text-primary)', textDecoration: 'none', borderRadius: 4 }}>📝 建表单</Link>
        <Link to={`/designer/views/${meta.name}/new`} style={{ padding: '8px 16px', background: 'var(--color-secondary-500)', color: 'var(--color-text-primary)', textDecoration: 'none', borderRadius: 4 }}>📊 建视图</Link>
        <button onClick={() => setShowForm(!showForm)} style={{ padding: '8px 16px', background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
          {showForm ? '取消' : '+ 添加记录'}
        </button>
      </div>

      {forms.length > 0 && (
        <div style={{ marginTop: 12, padding: 12, background: 'var(--color-text-primary)', borderRadius: 8, boxShadow: '0 1px 3px var(--color-bg-primary)' }}>
          <h3 style={{ marginTop: 0 }}>📋 关联表单({forms.length})</h3>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {forms.map((f) => (
              <Link key={f.id} to={`/forms/${f.id}/fill`} style={{ padding: '8px 12px', background: 'var(--color-info)', border: '1px solid var(--color-info)', borderRadius: 4, color: 'var(--color-info)', textDecoration: 'none' }}>
                {f.title}
              </Link>
            ))}
          </div>
        </div>
      )}

      {views.length > 0 && (
        <div style={{ marginTop: 12, padding: 12, background: 'var(--color-text-primary)', borderRadius: 8, boxShadow: '0 1px 3px var(--color-bg-primary)' }}>
          <h3 style={{ marginTop: 0 }}>👁 视图({views.length})</h3>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {views.map((v) => (
              <Link key={v.id} to={`/views/${v.id}/run`} style={{ padding: '8px 12px', background: 'var(--color-info)', border: '1px solid var(--color-info)', borderRadius: 4, color: 'var(--color-info)', textDecoration: 'none', fontSize: 13 }}>
                📊 {v.title} ({v.type})
              </Link>
            ))}
          </div>
        </div>
      )}

      {showForm && (
        <div style={{ marginTop: 16, padding: 16, background: 'var(--color-text-primary)', borderRadius: 8, boxShadow: '0 1px 3px var(--color-bg-primary)' }}>
          <h3 style={{ marginTop: 0 }}>新记录</h3>
          {fields.map((f) => (
            <div key={f.name} style={{ marginBottom: 12 }}>
              <label style={{ display: 'block', marginBottom: 4 }}>
                {f.label ?? f.name} {f.required && <span style={{ color: 'var(--color-error)' }}>*</span>}
                <span style={{ color: 'var(--color-text-muted)', fontSize: 12, marginLeft: 8 }}>({f.type})</span>
              </label>
              <input
                value={formData[f.name] ?? ''}
                onChange={(e) => setFormData({ ...formData, [f.name]: e.target.value })}
                style={{ padding: 8, fontSize: 14, width: '100%', maxWidth: 400 }}
              />
            </div>
          ))}
          <button
            onClick={handleSubmitRecord}
            disabled={createRecordMutation.isPending}
            style={{
              padding: '8px 16px',
              background: createRecordMutation.isPending ? 'var(--color-text-muted)' : 'var(--color-bg-secondary)',
              color: 'var(--color-text-primary)',
              border: 'none',
              borderRadius: 4,
              cursor: createRecordMutation.isPending ? 'not-allowed' : 'pointer',
            }}
          >
            {createRecordMutation.isPending ? '提交中…' : '提交'}
          </button>
        </div>
      )}

      <div style={{ marginTop: 16, padding: 16, background: 'var(--color-text-primary)', borderRadius: 8, boxShadow: '0 1px 3px var(--color-bg-primary)' }}>
        <h3 style={{ marginTop: 0 }}>记录({records.length})</h3>
        {records.length === 0 ? (
          <p style={{ color: 'var(--color-text-disabled)' }}>暂无记录</p>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ background: 'var(--color-bg-secondary)' }}>
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
                  <tr key={r.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                    <td style={{ padding: 8, fontFamily: 'monospace', fontSize: 12 }}>{String(r.id).slice(0, 8)}…</td>
                    {fields.map((f) => (
                      <td key={f.name} style={{ padding: 8 }}>{String((r as Record<string, unknown>)[f.name] ?? '')}</td>
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
