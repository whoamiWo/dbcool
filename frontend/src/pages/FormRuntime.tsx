import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { FormRuntime as FormRuntimeComponent } from '@/components/forms/FormRuntime';

/**
 * 表单运行时页面 — 用户填表并提交.
 * URL: /forms/:formId/fill
 */
export function FormRuntimePage() {
  const { formId } = useParams<{ formId: string }>();
  const navigate = useNavigate();

  const { data: formData, isLoading } = useQuery({
    queryKey: ['form', formId],
    queryFn: async () => {
      // 后端 envelope: {code, message, data: FormFull}
      const r = await apiClient.get(`/forms/${formId}`);
      // 兼容 vitest mock 直接返 FormFull + 真后端 envelope
      return (r as any)?.data ?? r ?? null;
    },
    enabled: !!formId,
  });

  const { data: collectionData } = useQuery({
    queryKey: ['collection', formData?.collection_name],
    queryFn: async () => {
      // 后端 envelope: {code, message, data: CollectionMeta}
      const r = await apiClient.get(
        `/collections/${formData?.collection_name}`
      );
      return (r as any)?.data ?? r ?? null;
    },
    enabled: !!formData?.collection_name,
  });

  const submitMutation = useMutation({
    mutationFn: async (payload: Record<string, unknown>) => {
      const collectionName = formData?.collection_name;
      if (!collectionName) throw new Error('collection 不存在');
      // 后端 envelope: {code, message, data: { id: string }}
      const r = await apiClient.post<{ code: number; data: { id: string } }>(
        `/collections/${collectionName}/records`,
        payload
      );
      // 返回 { id } 让 onSuccess 用
      return Array.isArray(r) ? r : (r.data);
    },
    onSuccess: () => {
      alert('提交成功!');
      navigate(`/designer/collections/${formData?.collection_name}`);
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      alert('提交失败:' + (e.response?.data?.message ?? '未知错误'));
    },
  });

  if (isLoading) return <p>加载中…</p>;
  if (!formData) return <p>表单不存在</p>;
  if (!collectionData) return <p>关联的 Collection 不存在</p>;

  // 解析 layout_json / rules_json 给 FormRuntime 组件用
  let parsedLayout: { field: string; span?: number }[] = [];
  let parsedRules: { validation?: Record<string, unknown[]>; visibility?: Record<string, unknown> } = {};
  try { parsedLayout = JSON.parse(formData.layout_json ?? '[]'); } catch (e) { console.error('layout_json parse failed', e); }
  try { parsedRules = JSON.parse(formData.rules_json ?? '{}'); } catch (e) { console.error('rules_json parse failed', e); }
  const formForComponent = {
    ...formData,
    layout: parsedLayout,
    rules: parsedRules,
  };

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      <button
        onClick={() => navigate(`/designer/collections/${formData.collection_name}`)}
        style={{ background: 'none', border: 'none', color: '#64748b', cursor: 'pointer', marginBottom: 16 }}
      >
        ← 返回
      </button>
      <div
        style={{
          padding: 24,
          background: 'white',
          borderRadius: 8,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
        }}
      >
        <FormRuntimeComponent
          form={formForComponent}
          fields={collectionData.fields ?? []}
          onSubmit={(data) => { submitMutation.mutate(data); }}
          submitLabel={submitMutation.isPending ? '提交中…' : '提交'}
        />
      </div>
    </div>
  );
}
