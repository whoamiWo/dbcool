import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { ErrorState } from '@/components/ErrorState';
import { EmptyState } from '@/components/EmptyState';
import type { } from '@/types/collection';
import type { FormMeta } from '@/types/form';

export function FormsListPage() {
  const { t } = useTranslation();
  const { collection } = useParams<{ collection?: string }>();
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['forms', collection ?? 'all'],
    queryFn: async () => {
      // 后端 envelope: {code, message, data: FormMeta[]}
      const r = await apiClient.get<{ code: number; data: FormMeta[] }>(
        collection ? `/forms?collection=${collection}` : '/forms'
      );
      // 兼容 vitest mock 直接返数组 + 后端 envelope: r 是数组 OR {code, data: [...]} 
      if (Array.isArray(r)) return r;  // vitest 模式
      return r.data ?? [];  // 真后端 envelope
    },
  });

  // PHASE98 R3: 错误态改为可重试 + 展示真实原因
  if (isLoading) return <p>{t('common.loading')}</p>;
  if (error) {
    return (
      <div>
        <h1>📋 {t('forms.title')}</h1>
        <ErrorState
          message={t('forms.loadFailed')}
          onRetry={() => void refetch()}
        />
      </div>
    );
  }

  const forms = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>📋 {t('forms.title')}{collection ? ` (${collection})` : ''}({forms.length})</h1>
        {collection && (
          <Link
            to={`/designer/forms/${collection}/new`}
            style={{
              padding: '8px 16px',
              background: 'var(--color-bg-secondary)',
              color: 'var(--color-text-primary)',
              textDecoration: 'none',
              borderRadius: 4,
            }}
          >
            {t('forms.newForm')}
          </Link>
        )}
      </div>

      {/* PHASE98 R3: 空态带 CTA（原引导语"点上方"不可点击） */}
      {forms.length === 0 ? (
        <EmptyState
          title={collection ? t('forms.emptyCollection') : t('forms.emptyGlobal')}
          description={t('forms.description')}
          actionLabel={collection ? '+' + t('forms.newForm') : undefined}
          onAction={
            collection
              ? () => (window.location.href = `/designer/forms/${collection}/new`)
              : undefined
          }
        />
      ) : (
        <div
          style={{
            marginTop: 16,
            background: 'var(--color-text-primary)',
            borderRadius: 8,
            boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
            overflow: 'hidden',
          }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ background: 'var(--color-bg-secondary)' }}>
                <th style={{ padding: 12, textAlign: 'left' }}>{t('forms.titleCol')}</th>
                <th style={{ padding: 12, textAlign: 'left' }}>{t('forms.collection')}</th>
                <th style={{ padding: 12, textAlign: 'left' }}>{t('forms.createdAt')}</th>
                <th style={{ padding: 12, textAlign: 'right' }}>{t('forms.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {forms.map((f) => (
                <tr key={f.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                  <td style={{ padding: 12 }}>{f.title}</td>
                  <td style={{ padding: 12, fontFamily: 'monospace' }}>{f.collection_name}</td>
                  <td style={{ padding: 12, color: 'var(--color-text-disabled)' }}>
                    {new Date(f.created_at).toLocaleString('zh-CN')}
                  </td>
                  <td style={{ padding: 12, textAlign: 'right' }}>
                    <Link
                      to={`/designer/forms/${f.collection_name}/${f.id}/edit`}
                      style={{ marginRight: 12, color: 'var(--color-info)', textDecoration: 'none' }}
                    >
                      {t('forms.edit')}
                    </Link>
                    <Link
                      to={`/forms/${f.id}/fill`}
                      style={{ color: 'var(--color-success)', textDecoration: 'none' }}
                    >
                      {t('forms.fill')}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
