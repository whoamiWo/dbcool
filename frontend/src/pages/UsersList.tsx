import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { UserMeta } from '@/types/acl';

/**
 * 用户管理(US-301).
 * 平台 Admin 用的简单列表 + 创建 + 启用/禁用.
 */
export function UsersListPage() {
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['admin', 'users'],
    queryFn: () => apiClient.get<UserMeta[]>('/admin/users'),
  });

  const createMutation = useMutation({
    mutationFn: () =>
      apiClient.post<UserMeta>('/admin/users', { username, password, displayName }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'users'] });
      setShowCreate(false);
      setUsername(''); setPassword(''); setDisplayName('');
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '创建失败');
    },
  });

  const toggleEnabledMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      apiClient.patch<UserMeta>(`/admin/users/${id}`, { enabled }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'users'] }),
  });

  if (isLoading) return <p>加载中…</p>;
  const users = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h1>👥 用户管理({users.length})</h1>
        <button
          onClick={() => setShowCreate(!showCreate)}
          style={{ padding: '8px 16px', background: '#1e293b', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' }}
        >
          {showCreate ? '取消' : '+ 新建用户'}
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
            <label>用户名 *</label>
            <input value={username} onChange={(e) => setUsername(e.target.value)} style={{ padding: 6, width: '100%' }} />
          </div>
          <div style={{ marginBottom: 8 }}>
            <label>密码 *</label>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} style={{ padding: 6, width: '100%' }} />
          </div>
          <div style={{ marginBottom: 8 }}>
            <label>显示名</label>
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} style={{ padding: 6, width: '100%' }} />
          </div>
          <button
            onClick={() => createMutation.mutate()}
            disabled={!username || !password || createMutation.isPending}
            style={{ padding: '8px 16px', background: '#16a34a', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' }}
          >
            {createMutation.isPending ? '创建中…' : '创建'}
          </button>
        </div>
      )}

      <table style={{ width: '100%', borderCollapse: 'collapse', background: 'white', borderRadius: 8, overflow: 'hidden', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
        <thead>
          <tr style={{ background: '#f1f5f9' }}>
            <th style={{ padding: 12, textAlign: 'left' }}>用户名</th>
            <th style={{ padding: 12, textAlign: 'left' }}>显示名</th>
            <th style={{ padding: 12, textAlign: 'left' }}>状态</th>
            <th style={{ padding: 12, textAlign: 'left' }}>创建时间</th>
            <th style={{ padding: 12, textAlign: 'right' }}>操作</th>
          </tr>
        </thead>
        <tbody>
          {users.map((u) => (
            <tr key={u.id} style={{ borderTop: '1px solid #e2e8f0' }}>
              <td style={{ padding: 12 }}>{u.username}</td>
              <td style={{ padding: 12 }}>{u.display_name}</td>
              <td style={{ padding: 12 }}>
                {u.enabled ? (
                  <span style={{ padding: '2px 8px', background: '#dcfce7', color: '#166534', borderRadius: 4, fontSize: 12 }}>启用</span>
                ) : (
                  <span style={{ padding: '2px 8px', background: '#fee2e2', color: '#991b1b', borderRadius: 4, fontSize: 12 }}>禁用</span>
                )}
              </td>
              <td style={{ padding: 12, color: '#64748b' }}>{new Date(u.created_at).toLocaleString('zh-CN')}</td>
              <td style={{ padding: 12, textAlign: 'right' }}>
                <button
                  onClick={() => toggleEnabledMutation.mutate({ id: u.id, enabled: !u.enabled })}
                  style={{ padding: '4px 8px', background: u.enabled ? '#dc2626' : '#16a34a', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                >
                  {u.enabled ? '禁用' : '启用'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
