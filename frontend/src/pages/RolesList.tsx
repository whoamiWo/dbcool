import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import apiClient from '@/api/client';
import type { RoleMeta } from '@/types/acl';

/**
 * 角色管理(US-302).
 * US-308: 角色可选父角色,形成继承链(后端 CTE 已天然防护深环,前端仅做选择器排除自身与后代)。
 */

/** 递归收集一个角色的所有后代 id(含自身)。 */
function collectSelfAndDescendants(roles: RoleMeta[], rootId: string): Set<string> {
  const byParent = new Map<string | null, RoleMeta[]>();
  for (const r of roles) {
    const key = r.parent_role_id ?? null;
    const bucket = byParent.get(key);
    if (bucket) bucket.push(r);
    else byParent.set(key, [r]);
  }
  const result = new Set<string>();
  const stack = [rootId];
  while (stack.length) {
    const cur = stack.pop()!;
    if (!result.add(cur)) continue;
    for (const child of byParent.get(cur) ?? []) stack.push(child.id);
  }
  return result;
}

export function RolesListPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState('');
  const [parentRoleId, setParentRoleId] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['admin', 'roles'],
    queryFn: () => apiClient.get<RoleMeta[]>('/admin/roles'),
  });

  const createMutation = useMutation({
    mutationFn: () =>
      apiClient.post<RoleMeta>('/admin/roles', {
        name,
        description,
        parent_role_id: parentRoleId || null,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'roles'] });
      setShowCreate(false);
      setName(''); setDescription(''); setParentRoleId('');
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? t('roles.createFailed'));
    },
  });

  const updateParentMutation = useMutation({
    mutationFn: ({ id, parentRoleId: pid }: { id: string; parentRoleId: string | null }) =>
      apiClient.put(`/admin/roles/${id}`, { parent_role_id: pid }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'roles'] });
      setError(null);
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? t('roles.parentUpdateFailed'));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => apiClient.delete(`/admin/roles/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'roles'] }),
  });

  if (isLoading) return <p>{t('common.loading')}</p>;
  const roles = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h1>🎭 {t('roles.title')}({roles.length})</h1>
        <button
          onClick={() => setShowCreate(!showCreate)}
          style={{ padding: '8px 16px', background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
        >
          {showCreate ? t('common.cancel') : '+' + t('roles.newRole')}
        </button>
      </div>

      {error && (
        <div style={{ padding: 8, marginBottom: 12, background: 'rgba(239,68,68,0.2)', color: 'var(--color-error)', borderRadius: 4 }}>
          {error}
        </div>
      )}

      {showCreate && (
        <div style={{ padding: 16, background: 'var(--color-text-primary)', borderRadius: 8, marginBottom: 16, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
          <div style={{ marginBottom: 8 }}>
            <label htmlFor="r-lbl-1">{t('roles.nameLabel')}</label>
            <input id="r-lbl-1" value={name} onChange={(e) => setName(e.target.value)} placeholder="editor" style={{ padding: 6, width: '100%', fontFamily: 'monospace' }} />
          </div>
          <div style={{ marginBottom: 8 }}>
            <label htmlFor="r-lbl-2">{t('roles.descLabel')}</label>
            <input id="r-lbl-2" value={description} onChange={(e) => setDescription(e.target.value)} style={{ padding: 6, width: '100%' }} />
          </div>
          <div style={{ marginBottom: 8 }}>
            <label htmlFor="r-lbl-parent">{t('roles.parentRoleLabel')}</label>
            <select
              id="r-lbl-parent"
              data-testid="parent-select-create"
              value={parentRoleId}
              onChange={(e) => setParentRoleId(e.target.value)}
              style={{ padding: 6, width: '100%' }}
            >
              <option value="">{t('roles.none')}</option>
              {roles.map(r => (
                <option key={r.id} value={r.id}>{r.name}</option>
              ))}
            </select>
            <small style={{ color: 'var(--color-text-disabled)', display: 'block', marginTop: 4 }}>{t('roles.parentHint')}</small>
          </div>
          <button
            onClick={() => createMutation.mutate()}
            disabled={!name || createMutation.isPending}
            style={{ padding: '8px 16px', background: 'var(--color-success)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
          >
            {t('common.create')}
          </button>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 12 }}>
        {roles.map((r) => (
          <div
            key={r.id}
            style={{
              padding: 16,
              background: 'var(--color-text-primary)',
              borderRadius: 8,
              boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <h3 style={{ margin: 0 }}>🎭 {r.name}</h3>
              <span style={{ fontSize: 11, color: 'var(--color-text-disabled)' }}>{r.tenant_id}</span>
            </div>
            <p style={{ color: 'var(--color-text-disabled)', fontSize: 13, marginTop: 4 }}>{r.description || t('roles.noDescription')}</p>
            {/* US-308: 父角色选择(排除自身及后代,避免环) */}
            <div style={{ marginTop: 8 }}>
              <label htmlFor={`parent-${r.id}`} style={{ fontSize: 12, color: 'var(--color-text-disabled)' }}>
                {t('roles.parentRoleLabel')}
              </label>
              <select
                id={`parent-${r.id}`}
                data-testid={`parent-select-${r.name}`}
                aria-label={t('roles.parentAria', { name: r.name })}
                value={r.parent_role_id ?? ''}
                disabled={updateParentMutation.isPending}
                onChange={(e) => updateParentMutation.mutate({ id: r.id, parentRoleId: e.target.value || null })}
                style={{ padding: 4, width: '100%', marginTop: 2 }}
              >
                <option value="">{t('roles.none')}</option>
                {(() => {
                  const excluded = collectSelfAndDescendants(roles, r.id);
                  return roles
                    .filter(c => !excluded.has(c.id))
                    .map(c => <option key={c.id} value={c.id}>{c.name}</option>);
                })()}
              </select>
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <Link
                to={`/admin/acl?roleId=${r.id}`}
                style={{ padding: '4px 8px', background: 'var(--color-secondary-500)', color: 'var(--color-text-primary)', textDecoration: 'none', borderRadius: 4, fontSize: 12 }}
              >
                {t('roles.aclConfig')}
              </Link>
              {r.name !== 'admin' && (
                <button
                  onClick={() => {
                    if (confirm(t('roles.deleteConfirm', { name: r.name }))) deleteMutation.mutate(r.id);
                  }}
                  style={{ padding: '4px 8px', background: 'var(--color-error)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, fontSize: 12, cursor: 'pointer' }}
                >
                  {t('common.delete')}
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
