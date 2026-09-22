import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import apiClient from '@/api/client';
import type { AclAction, AclPolicy, AclType, RoleMeta } from '@/types/acl';

/** ACL 权限编辑器(US-303/304/305 统一) */
export function AclEditorPage() {
  const qc = useQueryClient();
  const [params] = useSearchParams();
  const roleId = params.get('roleId');
  const [subject, setSubject] = useState('customer');
  const [newType, setNewType] = useState<AclType>('FIELD');
  const [newAction, setNewAction] = useState<AclAction>('DELETE');
  const [hiddenFields, setHiddenFields] = useState('ssn');
  const [filterField, setFilterField] = useState('dept');
  const [filterValue, setFilterValue] = useState('');
  const [error, setError] = useState<string | null>(null);

  const { data: roles } = useQuery({
    queryKey: ['admin', 'roles'],
    queryFn: () => apiClient.get<RoleMeta[]>('/admin/roles'),
  });
  const { data: policies } = useQuery({
    queryKey: ['admin', 'acl', roleId],
    queryFn: () => apiClient.get<AclPolicy[]>(`/admin/acl?roleId=${roleId}`),
    enabled: !!roleId,
  });

  const createMutation = useMutation({
    mutationFn: () => {
      let config = '{}';
      if (newType === 'FIELD') {
        const hidden = hiddenFields.split(',').map((s) => s.trim()).filter(Boolean);
        config = JSON.stringify({ hidden, readonly: [] });
      } else if (newType === 'ROW') {
        config = JSON.stringify({
          filters: [{ field: filterField, op: 'eq', value: filterValue }],
        });
      } else {
        config = '{}';
      }
      return apiClient.post<AclPolicy>('/admin/acl', {
        roleId,
        type: newType,
        subject,
        action: newType === 'ACTION' ? newAction : null,
        config,
      });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'acl', roleId] }),
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '创建失败');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => apiClient.delete(`/admin/acl/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'acl', roleId] }),
  });

  if (!roleId) {
    return (
      <div>
        <h1>🔐 ACL 权限配置</h1>
        <p>请先到 <a href="/admin/roles">角色管理</a> 选择一个角色。</p>
      </div>
    );
  }

  const role = roles?.find((r) => r.id === roleId);
  const ps = policies ?? [];

  return (
    <div>
      <h1>🔐 权限配置 — 角色 {role?.name ?? roleId}</h1>
      {error && (
        <div style={{ padding: 8, marginBottom: 12, background: 'rgba(239,68,68,0.2)', color: 'var(--color-error)', borderRadius: 4 }}>
          {error}
        </div>
      )}

      <div style={{ padding: 16, background: 'var(--color-text-primary)', borderRadius: 8, marginBottom: 16, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
        <h3 style={{ marginTop: 0 }}>+ 添加策略</h3>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8, marginBottom: 8 }}>
          <select value={subject} onChange={(e) => setSubject(e.target.value)} style={{ padding: 6 }}>
            <option value="customer">customer</option>
          </select>
          <select value={newType} onChange={(e) => setNewType(e.target.value as AclType)} style={{ padding: 6 }}>
            <option value="FIELD">字段权限 (FIELD)</option>
            <option value="ROW">记录权限 (ROW)</option>
            <option value="ACTION">操作权限 (ACTION)</option>
          </select>
          {newType === 'ACTION' ? (
            <select value={newAction} onChange={(e) => setNewAction(e.target.value as AclAction)} style={{ padding: 6 }}>
              <option value="CREATE">CREATE</option>
              <option value="READ">READ</option>
              <option value="UPDATE">UPDATE</option>
              <option value="DELETE">DELETE</option>
            </select>
          ) : newType === 'FIELD' ? (
            <input value={hiddenFields} onChange={(e) => setHiddenFields(e.target.value)} placeholder="ssn, password" style={{ padding: 6 }} />
          ) : (
            <input value={filterField} onChange={(e) => setFilterField(e.target.value)} placeholder="字段名(dept)" style={{ padding: 6 }} />
          )}
          {newType === 'ROW' ? (
            <input value={filterValue} onChange={(e) => setFilterValue(e.target.value)} placeholder="值(sales)" style={{ padding: 6 }} />
          ) : (
            <div />
          )}
        </div>
        <button
          onClick={() => createMutation.mutate()}
          disabled={createMutation.isPending}
          style={{ padding: '8px 16px', background: 'var(--color-success)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
        >
          添加
        </button>
      </div>

      <h3>现有策略({ps.length})</h3>
      {ps.length === 0 ? (
        <p style={{ color: 'var(--color-text-muted)' }}>暂无策略</p>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse', background: 'var(--color-text-primary)', borderRadius: 8, overflow: 'hidden' }}>
          <thead>
            <tr style={{ background: 'var(--color-bg-secondary)' }}>
              <th style={{ padding: 8, textAlign: 'left' }}>类型</th>
              <th style={{ padding: 8, textAlign: 'left' }}>对象</th>
              <th style={{ padding: 8, textAlign: 'left' }}>Action</th>
              <th style={{ padding: 8, textAlign: 'left' }}>配置</th>
              <th style={{ padding: 8, textAlign: 'right' }}></th>
            </tr>
          </thead>
          <tbody>
            {ps.map((p) => (
              <tr key={p.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                <td style={{ padding: 8 }}>
                  <span style={{ padding: '2px 8px', background: typeColor(p.type), color: 'var(--color-text-primary)', borderRadius: 4, fontSize: 11 }}>
                    {p.type}
                  </span>
                </td>
                <td style={{ padding: 8, fontFamily: 'monospace' }}>{p.subject}</td>
                <td style={{ padding: 8 }}>{p.action ?? '—'}</td>
                <td style={{ padding: 8, fontSize: 12, fontFamily: 'monospace', color: 'var(--color-text-disabled)' }}>{p.config_json}</td>
                <td style={{ padding: 8, textAlign: 'right' }}>
                  <button
                    onClick={() => deleteMutation.mutate(p.id)}
                    style={{ padding: '2px 8px', background: 'var(--color-error)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, fontSize: 12, cursor: 'pointer' }}
                  >
                    删
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <p style={{ fontSize: 12, color: 'var(--color-text-disabled)', marginTop: 16 }}>
        💡 ACL 已强制执行:读操作经 <code>AclEnforcer.filterReadableFields/filterRecord</code> 移除 hidden 字段,
        写操作经 <code>filterWritableFields/assertCanWriteFields</code> 拒绝写入 hidden 字段
        (原「仅配置不强制执行」为 Week 10 旧注记,现已过时并更正)。
        配置中的 <code>hidden</code> 同时表示该字段对当前角色不可见且不可编辑。
      </p>
    </div>
  );
}

function typeColor(t: AclType): string {
  return { FIELD: 'var(--color-secondary-500)', ROW: 'var(--color-info)', ACTION: 'var(--color-error)' }[t] ?? 'var(--color-text-disabled)';
}
