import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import apiClient from '@/api/client';

/**
 * PHASE128: 表单公开分享弹窗（复用 sharePanel 样式）。
 * 功能：
 * - 生成/重新生成 share_token
 * - 复制链接到剪贴板
 * - 撤销分享（将 share_token 置为 null）
 */
interface FormShareDialogProps {
  open: boolean;
  onClose: () => void;
  formId: string;
}

export function FormShareDialog({ open, onClose, formId }: FormShareDialogProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [shareToken, setShareToken] = useState<string | null>(null);

  const generateMutation = useMutation({
    mutationFn: async () => {
      const r = await apiClient.post(`/forms/${formId}/share`);
      // 后端 envelope: {code, data: {share_token}}
      return (r as any)?.data?.share_token ?? null;
    },
    onSuccess: (token) => {
      setShareToken(token);
      queryClient.invalidateQueries({ queryKey: ['forms'] });
    },
  });

  const revokeMutation = useMutation({
    mutationFn: async () => {
      await apiClient.delete(`/forms/${formId}/share`);
    },
    onSuccess: () => {
      setShareToken(null);
      queryClient.invalidateQueries({ queryKey: ['forms'] });
      onClose();
    },
  });

  const copyLink = async () => {
    if (!shareToken) return;
    const url = `${window.location.origin}/forms/public/${shareToken}`;
    try {
      await navigator.clipboard.writeText(url);
      alert(t('formPublic.shareCopySuccess'));
    } catch {
      prompt(t('formPublic.shareUrlLabel'), url);
    }
  };

  if (!open) return null;

  const baseUrl = `${window.location.origin}/forms/public/`;
  const shareUrl = shareToken ? `${baseUrl}${shareToken}` : '';

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.5)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 9999,
      }}
      onClick={onClose}
    >
      <div
        style={{
          background: 'var(--color-bg-primary)',
          borderRadius: 8,
          padding: 24,
          minWidth: 400,
          maxWidth: 600,
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <h3 style={{ margin: '0 0 16px' }}>{t('formPublic.shareDialogTitle')}</h3>

        {!shareToken ? (
          <div style={{ textAlign: 'center', padding: '24px 0' }}>
            <p style={{ color: 'var(--color-text-secondary)', marginBottom: 16 }}>
              {t('formPublic.shareNotEnabled')}
            </p>
            <button
              onClick={() => generateMutation.mutate()}
              disabled={generateMutation.isPending}
              style={{
                padding: '8px 16px',
                background: 'var(--color-primary)',
                color: '#fff',
                border: 'none',
                borderRadius: 4,
                cursor: 'pointer',
              }}
            >
              {generateMutation.isPending ? t('common.loading') : t('formPublic.shareToggleOn')}
            </button>
          </div>
        ) : (
          <>
            <div style={{ marginBottom: 16 }}>
              <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>
                {t('formPublic.shareUrlLabel')}
              </label>
              <input
                readOnly
                value={shareUrl}
                style={{
                  width: '100%',
                  padding: '8px',
                  fontFamily: 'monospace',
                  fontSize: 12,
                  border: '1px solid var(--color-border)',
                  borderRadius: 4,
                }}
              />
            </div>

            <div style={{ display: 'flex', gap: 8, justifyContent: 'space-between' }}>
              <div>
                <button
                  onClick={copyLink}
                  style={{
                    padding: '8px 16px',
                    background: 'var(--color-success)',
                    color: '#fff',
                    border: 'none',
                    borderRadius: 4,
                    cursor: 'pointer',
                  }}
                >
                  📋 {t('common.copy')}
                </button>
                <button
                  onClick={() => generateMutation.mutate()}
                  disabled={generateMutation.isPending}
                  style={{
                    marginLeft: 8,
                    padding: '8px 16px',
                    background: 'var(--color-info)',
                    color: '#fff',
                    border: 'none',
                    borderRadius: 4,
                    cursor: 'pointer',
                  }}
                >
                  🔄 {t('formPublic.shareRegenerate')}
                </button>
              </div>

              <div>
                <button
                  onClick={() => {
                    if (confirm(t('formPublic.shareRevoke'))) {
                      revokeMutation.mutate();
                    }
                  }}
                  disabled={revokeMutation.isPending}
                  style={{
                    padding: '8px 16px',
                    background: 'var(--color-error)',
                    color: '#fff',
                    border: 'none',
                    borderRadius: 4,
                    cursor: 'pointer',
                  }}
                >
                  {revokeMutation.isPending ? t('common.loading') : t('formPublic.shareRevoke')}
                </button>
                <button
                  onClick={onClose}
                  style={{
                    marginLeft: 8,
                    padding: '8px 16px',
                    background: 'var(--color-bg-secondary)',
                    color: 'var(--color-text-primary)',
                    border: 'none',
                    borderRadius: 4,
                    cursor: 'pointer',
                  }}
                >
                  {t('common.close')}
                </button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
