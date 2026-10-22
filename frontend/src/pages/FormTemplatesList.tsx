import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { ErrorState } from '@/components/ErrorState';
import { EmptyState } from '@/components/EmptyState';
import apiClient from '@/api/client';
import type { FormTemplateMeta } from '@/types/form';

export function FormTemplatesListPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [selectedCollection, setSelectedCollection] = useState<string>('');
  const [title, setTitle] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);

  const deleteMutation = useMutation({
    mutationFn: (id: string) =>
      apiClient.delete<{ code: number }>(`/form-templates/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['form-templates'] });
    },
    onError: (err: unknown) => {
      console.error('删除模板失败', err);
    },
  });
  const createMutation = useMutation({
    mutationFn: (variables: { title: string; collectionName: string; description?: string }) =>
      apiClient.post<{ code: number; data: FormTemplateMeta }>('/form-templates', variables),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['form-templates'] });
      setShowCreate(false);
      setTitle('');
      setSelectedCollection('');
    },
    onError: (err: unknown) => {
      console.error('创建模板失败', err);
    },
  });

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['form-templates'],
    queryFn: async () => {
      const r = await apiClient.get<{ code: number; data: FormTemplateMeta[] }>('/form-templates');
      if (Array.isArray(r)) return r;
      return r.data ?? [];
    },
  });

  if (isLoading) return <p>{t('common.loading')}</p>;

  if (error) {
    return (
      <div>
        <h1>{t('formTemplates.pageTitle')}</h1>
        <ErrorState
          message={t('formTemplates.loadFailed')}
          onRetry={() => void refetch()}
        />
      </div>
    );
  }

  const templates = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>{t('formTemplates.pageTitle')} ({templates.length})</h1>
        <button
          onClick={() => setShowCreate(true)}
          style={{
            padding: '8px 16px',
            background: 'var(--color-bg-secondary)',
            color: 'var(--color-text-primary)',
            border: 'none',
            borderRadius: 4,
            cursor: 'pointer',
          }}
        >
          {t('formTemplates.newTemplate')}
        </button>
      </div>

      {showCreate && (
        <div style={{ padding: 16, background: 'var(--color-text-primary)', borderRadius: 8, marginTop: 16 }}>
          <div style={{ marginBottom: 8 }}>
            <label>{t('formTemplates.title')}</label>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              style={{ padding: 6, width: '100%' }}
              placeholder={t('formTemplates.titlePlaceholder')}
            />
          </div>
          <div style={{ marginBottom: 8 }}>
            <label>{t('formTemplates.collection')}</label>
            <input
              value={selectedCollection}
              onChange={(e) => setSelectedCollection(e.target.value)}
              style={{ padding: 6, width: '100%' }}
              placeholder={t('formTemplates.collectionPlaceholder')}
            />
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              onClick={() => createMutation.mutate({ title, collectionName: selectedCollection })}
              disabled={!title || !selectedCollection || createMutation.isPending}
              style={{ padding: '8px 16px', background: 'var(--color-success)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4 }}
            >
              {createMutation.isPending ? t('common.loading') : t('formTemplates.create')}
            </button>
            <button
              onClick={() => setShowCreate(false)}
              style={{ padding: '8px 16px', background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4 }}
            >
              {t('common.cancel')}
            </button>
          </div>
        </div>
      )}

      {editingId && (
        <div style={{ padding: 16, background: 'var(--color-bg-tertiary)', borderRadius: 8, marginTop: 16 }}>
          <p>{t('formTemplates.edit')} 模板 ID: {editingId}</p>
          <button
            onClick={() => setEditingId(null)}
            style={{ padding: '8px 16px', background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4 }}
          >
            {t('common.close')}
          </button>
        </div>
      )}

      <div style={{ marginTop: 16 }}>
        {templates.length === 0 ? (
          <EmptyState
            title={t('formTemplates.emptyTitle')}
            description={t('formTemplates.emptyDesc')}
          />
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ background: 'var(--color-bg-secondary)' }}>
                <th style={{ padding: 12, textAlign: 'left' }}>{t('formTemplates.titleCol')}</th>
                <th style={{ padding: 12, textAlign: 'left' }}>{t('formTemplates.collection')}</th>
                <th style={{ padding: 12, textAlign: 'left' }}>{t('formTemplates.createdAt')}</th>
                <th style={{ padding: 12, textAlign: 'right' }}>{t('formTemplates.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {templates.map((tpl) => (
                <tr key={tpl.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                  <td style={{ padding: 12 }}>{tpl.title}</td>
                  <td style={{ padding: 12, fontFamily: 'monospace' }}>{tpl.collection_name || '-'}</td>
                  <td style={{ padding: 12, color: 'var(--color-text-disabled)' }}>
                    {new Date(tpl.created_at).toLocaleString('zh-CN')}
                  </td>
                  <td style={{ padding: 12, textAlign: 'right' }}>
                    <button
                      onClick={() => setEditingId(tpl.id)}
                      style={{ padding: '4px 8px', marginRight: 8, background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                    >
                      {t('formTemplates.edit')}
                    </button>
                    <button
                      onClick={() => deleteMutation.mutate(tpl.id)}
                      disabled={deleteMutation.isPending && deleteMutation.variables === tpl.id}
                      style={{ padding: '4px 8px', background: 'var(--color-error)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                    >
                      {t('formTemplates.delete')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}