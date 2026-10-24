/** 权限预览面板 (US-307) */

import { useState, useEffect } from 'react';
import apiClient from '@/api/client';
import { useTranslation } from 'react-i18next';

interface UserRole {
  id: string;
  name: string;
  description?: string;
  tenant_id: string;
}

interface PermissionData {
  user_id: string;
  tenant_id: string;
  roles: UserRole[];
  policies_summary: Array<{ role_id: string; role_name: string }>;
}

interface PermissionPreviewProps {
  userId: string;
  tenantId?: string;
}

export function PermissionPreview({ userId, tenantId }: PermissionPreviewProps) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState<PermissionData | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!userId) return;

    // eslint-disable-next-line react-hooks/set-state-in-effect -- 加载状态/错误标志必须在 effect 内设置
    setLoading(true);
    setError(null);
    apiClient
      .get<{ code: number; message: string; data: PermissionData }>(
        `/admin/users/${userId}/effective-permissions${tenantId ? `?tenantId=${tenantId}` : ''}`,
      )
      .then((json) => {
        if (json.code === 0) {
          setData(json.data);
        } else {
          setError(json.message || t('common.error'));
        }
      })
      .catch(() => setError(t('common.networkError')))
      .finally(() => setLoading(false));
  }, [userId, tenantId, t]);

  if (loading) {
    return <div style={{ padding: 20, textAlign: 'center' }}>{t('common.loading')}</div>;
  }

  if (error) {
    return <div style={{ padding: 20, color: 'var(--color-error)' }}>{error}</div>;
  }

  if (!data || !data.roles.length) {
    return (
      <div style={{ padding: 20, color: 'var(--color-text-disabled)' }}>
        {t('permissionPreview.noRoles')}
      </div>
    );
  }

  return (
    <div style={{ padding: 16 }}>
      <h4 style={{ marginBottom: 12 }}>{t('permissionPreview.title')}</h4>
      
      <div style={{ marginBottom: 16 }}>
        <strong>{t('permissionPreview.roles')}:</strong>
        <ul style={{ margin: '8px 0', paddingLeft: 20 }}>
          {data.roles.map((role) => (
            <li key={role.id}>
              <span style={{ fontWeight: 500 }}>{role.name}</span>
              {role.description && (
                <span style={{ color: 'var(--color-text-muted)', marginLeft: 8 }}>
                  — {role.description}
                </span>
              )}
            </li>
          ))}
        </ul>
      </div>

      <div>
        <strong>{t('permissionPreview.policies')}:</strong>
        {data.policies_summary.length > 0 ? (
          <ul style={{ margin: '8px 0', paddingLeft: 20 }}>
            {data.policies_summary.map((policy, idx) => (
              <li key={idx}>
                {t('permissionPreview.rolePolicy', { role: policy.role_name })}
              </li>
            ))}
          </ul>
        ) : (
          <div style={{ color: 'var(--color-text-disabled)', marginTop: 8 }}>
            {t('permissionPreview.noPolicies')}
          </div>
        )}
      </div>
    </div>
  );
}
