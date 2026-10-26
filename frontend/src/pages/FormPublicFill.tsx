import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { FormRuntime as FormRuntimeComponent } from '@/components/forms/FormRuntime';

/**
 * PHASE10: 公开填写页（免登录）。
 *
 * URL: /forms/public/:token
 * 行为：
 * - token 有效：渲染现有表单布局，提交调用 /api/forms/public/:token/submit
 * - token 无效/已撤销：显示友好错误（后端统一 404，不泄露资源是否存在）
 *
 * 视觉：独立于 AppLayout（无导航栏/侧边栏），单列居中卡片。
 */
export function FormPublicFillPage() {
  const { t } = useTranslation();
  const { token } = useParams<{ token: string }>();

  const { data: formData, isLoading } = useQuery({
    queryKey: ['formPublic', token],
    queryFn: async () => {
      // 后端 envelope: {code, message, data: FormPublic}
      const r = await apiClient.get(`/forms/public/${token}`);
      return (r as any)?.data ?? r ?? null;
    },
    enabled: !!token,
  });

  const { data: collectionData } = useQuery({
    queryKey: ['collection', formData?.collection_name],
    queryFn: async () => {
      if (!formData?.collection_name) return null;
      const r = await apiClient.get(`/collections/${formData.collection_name}`);
      return (r as any)?.data ?? r ?? null;
    },
    enabled: !!formData?.collection_name,
  });

  const submitMutation = useMutation({
    mutationFn: async (payload: Record<string, unknown>) => {
      const r = await apiClient.post<{ code: number; data: { id: string } }>(
        `/api/forms/public/${token}/submit`,
        payload
      );
      return Array.isArray(r) ? r : r.data;
    },
    onSuccess: () => {
      alert(t('formPublic.submitSuccess'));
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      alert(`${t('formPublic.submitFailed')}: ${e.response?.data?.message ?? ''}`);
    },
  });

  if (!token) return <p>{t('formPublic.invalidToken')}</p>;
  if (isLoading) return <p>{t('formPublic.loading')}</p>;
  if (!formData) return <p>{t('formPublic.formNotFound')}</p>;

  // 解析 layout_json / rules_json 供 FormRuntime 组件使用
  let parsedLayout: { field: string; span?: number }[] = [];
  let parsedRules: { validation?: Record<string, unknown[]>; visibility?: Record<string, unknown> } = {};
  try { parsedLayout = JSON.parse(formData.layout_json ?? '[]'); } catch (e) { console.error('layout_json parse failed', e); }
  try { parsedRules = JSON.parse(formData.rules_json ?? '{}'); } catch (e) { console.error('rules_json parse failed', e); }
  const formForComponent = { ...formData, layout: parsedLayout, rules: parsedRules };

  return (
    <div style={{ maxWidth: 640, margin: '0 auto', padding: 24 }}>
      <h2 style={{ marginBottom: 8 }}>{formData.title ?? t('formPublic.defaultTitle')}</h2>
      {formData.description ? (
        <p style={{ color: 'var(--color-text-secondary)', marginBottom: 24 }}>{formData.description}</p>
      ) : null}

      <FormRuntimeComponent
        form={formForComponent}
        fields={collectionData?.fields ?? []}
        onSubmit={(data: Record<string, unknown>) => { submitMutation.mutate(data); }}
        submitLabel={submitMutation.isPending ? t('formPublic.submitting') : t('formPublic.submit')}
      />

      <p style={{ marginTop: 24, fontSize: 12, color: 'var(--color-text-disabled)' }}>
        {t('formPublic.poweredBy')}
      </p>
    </div>
  );
}
