import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import apiClient from '@/api/client';
import type { RoleMeta } from '@/types/acl';

/**
 * 角色管理(US-302).
 */
export function RolesListPage() {
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['admin', 'roles'],
    queryFn: () => apiClient.get<RoleMeta[]>('/admin/roles'),
  });

  const createMutation = useMutation({
    mutationFn: () =>
      apiClient.post<RoleMeta>('/admin/roles', { name, description }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'roles'] });
      setShowCreate(false);
      setName(''); setDescription('');
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '创建失败');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => apiClient.delete(`/admin/roles/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'roles'] }),
  });

  if (isLoading) return <p>加载中…</p>;;
  const roles = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h1>🎭 角色管理({roles.length})</h1>
        <button
          onClick={() => setShowCreate(!showCreate)}
          style={{ padding: '8px 16px', background: '#1e293b', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' }}
        >
          {showCreate ? '取消' : '+ 新建角色'}
        </button>
      </div>

      {error && (
        <div style={{ padding: 8, marginBottom: 12, background: '#fee2e2', color: '#991b1b', borderRadius: 4 }}>
          {error}
        </div>
      )}

      {showCreate && (
        <div style={{ padding: 16, background: 'white', borderRadius: 8, marginBottom: 16, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
          <div style={{ marginBottom: 8 }}>
            <label>名称(英文,小写) *</label>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="editor" style={{ padding: 6, width: '100%', fontFamily: 'monospace' }} />
          </div>
          <div style={{ marginBottom: 8 }}>
            <label>描述</label>
            <input value={description} onChange={(e) => setDescription(e.target.value)} style={{ padding: 6, width: '100%' }} />
          </div>
          <button
            onClick={() => createMutation.mutate()}
            disabled={!name || createMutation.isPending}
            style={{ padding: '8px 16px', background: '#16a34a', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' }}
          >
            创建
          </button>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 12 }}>
        {roles.map((r) => (
          <div
            key={r.id}
            style={{
              padding: 16,
              background: 'white',
              borderRadius: 8,
              boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <h3 style={{ margin: 0 }}>🎭 {r.name}</h3>
              <span style={{ fontSize: 11, color: '#64748b' }}>{r.tenant_id}</span>
            </div>
            <p style={{ color: '#64748b', fontSize: 13, marginTop: 4 }}>{r.description || '(无描述)'}</p>
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <Link
                to={`/admin/acl?roleId=${r.id}`}
                style={{ padding: '4px 8px', background: '#7c3aed', color: 'white', textDecoration: 'none', borderRadius: 4, fontSize: 12 }}
              >
                权限配置
              </Link>
              {r.name !== 'admin' && (
                <button
                  onClick={() => {
                    if (confirm(`删除角色 "${r.name}"?`)) deleteMutation.mutate(r.id);
                  }}
                  style={{ padding: '4px 8px', background: '#dc2626', color: 'white', border: 'none', borderRadius: 4, fontSize: 12, cursor: 'pointer' }}
                >
                  删除
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
