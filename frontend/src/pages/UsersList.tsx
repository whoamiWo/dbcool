import { Fragment, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ErrorState } from '@/components/ErrorState';
import { EmptyState } from '@/components/EmptyState';
import { PermissionPreview } from '@/components/PermissionPreview';
import apiClient from '@/api/client';
import type { UserMeta } from '@/types/acl';

/**
 * 用户管理 (US-301).
 * 平台 Admin 用的简单列表 + 创建 + 启用/禁用.
 */
export function UsersListPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState<string | null>(null);
  // US-307: 展开查看某用户的生效权限
  const [expandedUserId, setExpandedUserId] = useState<string | null>(null);

  // PHASE98 R3: 取 isError/error/refetch。此前只解构 data/isLoading ——
  // 请求 500 时 data 为 undefined，页面渲染「用户管理 (0)」+ 空表头，
  // 与"真的没有用户"完全无法区分。
  const { data, isLoading, isError, error: queryError, refetch } = useQuery({
    queryKey: ['admin', 'users'],
    queryFn: async () => {
      // 后端 envelope: {code, message, data: UserMeta[]}
      const r = await apiClient.get<{ code: number; data: UserMeta[] }>('/admin/users');
      // 兼容 vitest mock 直接返数组 + 后端 envelope: r 是数组 OR {code, data: [...]}
      if (Array.isArray(r)) return r;  // vitest 模式
      return r.data ?? [];  // 真后端 envelope
    },
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
      setError(e.response?.data?.message ?? t('usersList.createFailed'));
    },
  });

  const toggleEnabledMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      apiClient.patch<UserMeta>(`/admin/users/${id}`, { enabled }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'users'] }),
  });

  // PHASE98 R3: 错误态优先（失败 ≠ 空列表）
  if (isError) {
    return (
      <div>
        <h1>{t('usersList.pageTitle')}</h1>
        <ErrorState
          message={t('usersList.loadFailed', { error: (queryError as Error)?.message ?? '' })}
          onRetry={() => void refetch()}
        />
      </div>
    );
  }

  if (isLoading) return <p>{t('usersList.loading')}</p>;
  const users = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h1>{t('usersList.pageTitleWithCount', { count: users.length })}</h1>
        <button
          onClick={() => setShowCreate(!showCreate)}
          style={{ padding: '8px 16px', background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
        >
          {showCreate ? t('usersList.cancel') : t('usersList.newUserBtn')}
        </button>
      </div>

      {error && (
        <div style={{ padding: 8, marginBottom: 12, background: 'var(--color-error)', color: 'var(--color-error)', borderRadius: 4 }}>
          {error}
        </div>
      )}

      {showCreate && (
        <div style={{ padding: 16, background: 'var(--color-text-primary)', borderRadius: 8, marginBottom: 16, boxShadow: '0 1px 3px var(--color-bg-primary)' }}>
          <div style={{ marginBottom: 8 }}>
            <label htmlFor="u-lbl-1">{t('usersList.usernameLabel')}</label>
            <input id="u-lbl-1" value={username} onChange={(e) => setUsername(e.target.value)} style={{ padding: 6, width: '100%' }} />
          </div>
          <div style={{ marginBottom: 8 }}>
            <label htmlFor="u-lbl-2">{t('usersList.passwordLabel')}</label>
            <input id="u-lbl-2" type="password" value={password} onChange={(e) => setPassword(e.target.value)} style={{ padding: 6, width: '100%' }} />
          </div>
          <div style={{ marginBottom: 8 }}>
            <label htmlFor="u-lbl-3">{t('usersList.displayNameLabel')}</label>
            <input id="u-lbl-3" value={displayName} onChange={(e) => setDisplayName(e.target.value)} style={{ padding: 6, width: '100%' }} />
          </div>
          <button
            onClick={() => createMutation.mutate()}
            disabled={!username || !password || createMutation.isPending}
            style={{ padding: '8px 16px', background: 'var(--color-success)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
          >
            {createMutation.isPending ? t('usersList.creating') : t('usersList.createBtn')}
          </button>
        </div>
      )}

      {/* PHASE98 R3: 空态带 CTA —— 此前 0 用户时只渲染一个空表头，无法引导新建。
          注意文案与顶部「+ 新建用户」按钮区分开，避免同一可访问名重复出现。 */}
      {users.length === 0 && (
        <EmptyState
          title={t('usersList.emptyTitle')}
          description={t('usersList.emptyDesc')}
          actionLabel={t('usersList.emptyAction')}
          onAction={() => setShowCreate(true)}
        />
      )}

      <table style={{ width: '100%', borderCollapse: 'collapse', background: 'var(--color-text-primary)', borderRadius: 8, overflow: 'hidden', boxShadow: '0 1px 3px var(--color-bg-primary)' }}>
        <thead>
          <tr style={{ background: 'var(--color-bg-secondary)' }}>
            <th style={{ padding: 12, textAlign: 'left' }}>{t('usersList.colUsername')}</th>
            <th style={{ padding: 12, textAlign: 'left' }}>{t('usersList.colDisplayName')}</th>
            <th style={{ padding: 12, textAlign: 'left' }}>{t('usersList.colStatus')}</th>
            <th style={{ padding: 12, textAlign: 'left' }}>{t('usersList.colCreatedAt')}</th>
            <th style={{ padding: 12, textAlign: 'right' }}>{t('usersList.colAction')}</th>
          </tr>
        </thead>
        <tbody>
          {users.map((u) => (
            <Fragment key={u.id}>
              <tr style={{ borderTop: '1px solid var(--color-border-light)' }}>
                <td style={{ padding: 12 }}>{u.username}</td>
                <td style={{ padding: 12 }}>{u.display_name}</td>
                <td style={{ padding: 12 }}>
                  {u.enabled ? (
                    <span style={{ padding: '2px 8px', background: 'rgba(var(--color-success-rgb, 16,185,129),0.2)', color: 'var(--color-success)', borderRadius: 4, fontSize: 12 }}>{t('usersList.statusEnabled')}</span>
                  ) : (
                    <span style={{ padding: '2px 8px', background: 'var(--color-error)', color: 'var(--color-error)', borderRadius: 4, fontSize: 12 }}>{t('usersList.statusDisabled')}</span>
                  )}
                </td>
                <td style={{ padding: 12, color: 'var(--color-text-disabled)' }}>{new Date(u.created_at).toLocaleString('zh-CN')}</td>
                <td style={{ padding: 12, textAlign: 'right' }}>
                  <button
                    onClick={() => setExpandedUserId(expandedUserId === u.id ? null : u.id)}
                    aria-expanded={expandedUserId === u.id}
                    style={{ padding: '4px 8px', marginRight: 8, background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                  >
                    {t('usersList.viewPermissions')}
                  </button>
                  <button
                    onClick={() => toggleEnabledMutation.mutate({ id: u.id, enabled: !u.enabled })}
                    style={{ padding: '4px 8px', background: u.enabled ? 'var(--color-error)' : 'var(--color-success)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                  >
                    {u.enabled ? t('usersList.toggleDisable') : t('usersList.toggleEnable')}
                  </button>
                </td>
              </tr>
              {expandedUserId === u.id && (
                <tr>
                  <td colSpan={5} style={{ padding: 0, background: 'var(--color-bg-secondary)' }}>
                    <PermissionPreview userId={u.id} />
                  </td>
                </tr>
              )}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  );
}
