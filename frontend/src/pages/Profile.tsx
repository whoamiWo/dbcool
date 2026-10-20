import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';

interface MeData {
  id: string;
  username: string;
  tenant_id: string;
  roles: string[];
  created_at: string;
}

/**
 * 个人中心(US-502 我的信息 + 改密码).
 */
export function ProfilePage() {
  const { t } = useTranslation();
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const meQuery = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: async () => {
      const res = await apiClient.get<{ data: MeData }>('/auth/me');
      return res.data;
    },
  });

  const changeMutation = useMutation({
    mutationFn: () =>
      apiClient.post<{ code: number; message: string }>('/auth/password', {
        oldPassword,
        newPassword,
      }),
    onSuccess: (res) => {
      setSuccess(res.message);
      setError(null);
      setOldPassword('');
      setNewPassword('');
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? t('profile.changeFailed'));
      setSuccess(null);
    },
  });

  return (
    <div style={{ maxWidth: 500 }}>
      <h1>👤 {t('profile.title')}</h1>

      {/* 我的信息卡(US-502) */}
      <div style={{ padding: 16, background: 'var(--color-text-primary)', borderRadius: 8, boxShadow: '0 1px 3px var(--color-bg-primary)', marginBottom: 16 }}>
        <h3 style={{ marginTop: 0 }}>{t('profile.infoTitle')}</h3>
        {meQuery.isLoading && <p style={{ color: 'var(--color-text-disabled)' }}>{t('common.loading')}</p>}
        {meQuery.data && (
          <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
            <tbody>
              <tr><td style={lbl}>{t('profile.username')}</td><td style={val}><code>{meQuery.data.username}</code></td></tr>
              <tr><td style={lbl}>{t('profile.userId')}</td><td style={val}><code style={{ fontSize: 11 }}>{meQuery.data.id}</code></td></tr>
              <tr><td style={lbl}>{t('profile.tenant')}</td><td style={val}>{meQuery.data.tenant_id}</td></tr>
              <tr><td style={lbl}>{t('profile.roles')}</td><td style={val}>
                {meQuery.data.roles.map((r) => (
                  <span key={r} style={{ display: 'inline-block', marginRight: 6, padding: '2px 8px',
                                        background: r === 'admin' ? 'var(--color-secondary-500)' : 'var(--color-info)',
                                        color: 'var(--color-text-primary)', borderRadius: 4, fontSize: 12 }}>{r}</span>
                ))}
              </td></tr>
              <tr><td style={lbl}>{t('profile.registeredAt')}</td><td style={val}>{meQuery.data.created_at.replace('T', ' ').slice(0, 19)}</td></tr>
            </tbody>
          </table>
        )}
      </div>

      {/* 修改密码(US-502) */}
      <div style={{ padding: 16, background: 'var(--color-text-primary)', borderRadius: 8, boxShadow: '0 1px 3px var(--color-bg-primary)' }}>
        <h3 style={{ marginTop: 0 }}>{t('profile.changePassword')}</h3>
        {error && <div style={{ padding: 8, marginBottom: 12, background: 'var(--color-error)', color: 'var(--color-error)', borderRadius: 4 }}>{error}</div>}
        {success && <div style={{ padding: 8, marginBottom: 12, background: 'rgba(var(--color-success-rgb, 16,185,129),0.2)', color: 'var(--color-success)', borderRadius: 4 }}>{success}</div>}
        <div style={{ marginBottom: 8 }}>
          <label htmlFor="p-lbl-1">{t('profile.oldPassword')}</label>
          <input id="p-lbl-1" type="password" value={oldPassword} onChange={(e) => setOldPassword(e.target.value)} style={inputStyle} />
        </div>
        <div style={{ marginBottom: 8 }}>
          <label htmlFor="p-lbl-2">{t('profile.newPassword')}</label>
          <input id="p-lbl-2" type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} style={inputStyle} />
        </div>
        <button onClick={() => changeMutation.mutate()} disabled={changeMutation.isPending || !oldPassword || !newPassword} style={btnStyle}>
          {changeMutation.isPending ? t('common.submitting') : t('profile.changePassword')}
        </button>
      </div>
    </div>
  );
}

const inputStyle = { padding: 6, width: '100%', border: '1px solid var(--color-border-medium)', borderRadius: 4 };
const btnStyle = { padding: '8px 16px', background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' };
const lbl = { padding: '6px 8px 6px 0', color: 'var(--color-text-disabled)', width: 80, verticalAlign: 'top' } as const;
const val = { padding: '6px 0' } as const;
