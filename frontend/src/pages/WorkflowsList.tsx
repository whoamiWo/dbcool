import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import apiClient from '@/api/client';
import EmptyState from '@/components/EmptyState';

interface WorkflowMeta {
  id: string;
  name: string;
  title: string;
  description: string;
  collection_name: string;
  enabled: boolean;
  created_at: string;
}

export function WorkflowsListPage() {
  const { t } = useTranslation();
  const { data, isLoading } = useQuery({
    queryKey: ['workflows', 'all'],
    queryFn: async () => {
      // 后端 envelope: {code, message, data: WorkflowMeta[]}
      const r = await apiClient.get<{ code: number; data: WorkflowMeta[] }>('/workflows');
      // 兼容 vitest mock 直接返数组 + 后端 envelope: r 是数组 OR {code, data: [...]} 
      if (Array.isArray(r)) return r;  // vitest 模式
      return r.data ?? [];  // 真后端 envelope
    },
  });
  const navigate = useNavigate();
  // PHASE98 R3: 顺手修掉双分号 `</p>;;`
  if (isLoading) return <p>{t('common.loading')}</p>;
  const workflows = data ?? [];
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h1>⚡ {t('workflows.title')}({workflows.length})</h1>
        <Link to="/designer/workflows/new" style={{ padding: '8px 16px', background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', textDecoration: 'none', borderRadius: 4 }}>{t('workflows.newWorkflow')}</Link>
      </div>
      {workflows.length === 0 ? (
        <EmptyState
          title={t('workflows.emptyTitle')}
          description={t('workflows.emptyDescription')}
          actionLabel={t('workflows.newWorkflow')}
          onAction={() => navigate('/designer/workflows/new')}
        />
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse', background: 'var(--color-text-primary)', borderRadius: 8, overflow: 'hidden', marginTop: 16 }}>
          <thead><tr style={{ background: 'var(--color-bg-secondary)' }}>
            <th style={{ padding: 12, textAlign: 'left' }}>{t('workflows.name')}</th>
            <th style={{ padding: 12, textAlign: 'left' }}>{t('workflows.titleCol')}</th>
            <th style={{ padding: 12, textAlign: 'left' }}>{t('workflows.collection')}</th>
            <th style={{ padding: 12, textAlign: 'left' }}>{t('workflows.status')}</th>
            <th style={{ padding: 12, textAlign: 'right' }}>{t('workflows.actions')}</th>
          </tr></thead>
          <tbody>
            {workflows.map((w) => (
              <tr key={w.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                <td style={{ padding: 12, fontFamily: 'monospace' }}>{w.name}</td>
                <td style={{ padding: 12 }}>{w.title}</td>
                <td style={{ padding: 12, fontFamily: 'monospace' }}>{w.collection_name}</td>
                <td style={{ padding: 12 }}>
                  {w.enabled ? <span style={{ padding: '2px 8px', background: 'rgba(16,185,129,0.2)', color: 'var(--color-success)', borderRadius: 4, fontSize: 12 }}>{t('workflows.enabled')}</span>
                            : <span style={{ padding: '2px 8px', background: 'rgba(239,68,68,0.2)', color: 'var(--color-error)', borderRadius: 4, fontSize: 12 }}>{t('workflows.disabled')}</span>}
                </td>
                <td style={{ padding: 12, textAlign: 'right' }}>
                  <Link to={`/designer/workflows/${w.id}/edit`} style={{ marginRight: 12, color: 'var(--color-info)', textDecoration: 'none' }}>{t('workflows.edit')}</Link>
                  <Link to={`/api/workflows/${w.id}/trigger`} style={{ color: 'var(--color-success)', textDecoration: 'none' }}>{t('workflows.trigger')}</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
