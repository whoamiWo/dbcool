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

  /** US-106:提交动作为 workflow 时触发后端工作流。 */
  const triggerWorkflow = async (workflowId: string, payload: Record<string, unknown>) => {
    try {
      await apiClient.post(`/workflows/${workflowId}/trigger`, payload);
    } catch (err) {
      // 记录已提交成功但工作流触发失败,不阻断用户(数据已入库)
      // eslint-disable-next-line no-console
      console.error('触发工作流失败:', err);
    }
  };

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
      // US-106:redirect 动作由 FormRuntime 内部执行(window.location.href),
      // 此处不能再 navigate,否则会覆盖配置的跳转目标
      let rule: { submit?: { action?: string } } = {};
      try {
        rule = JSON.parse(formData?.rules_json ?? '{}');
      } catch (e) {
        // eslint-disable-next-line no-console
        console.error('rules_json parse failed', e);
      }
      if (rule.submit?.action === 'redirect') return;

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
        style={{ background: 'none', border: 'none', color: 'var(--color-text-disabled)', cursor: 'pointer', marginBottom: 16 }}
      >
        ← 返回
      </button>
      <div
        style={{
          padding: 24,
          background: 'var(--color-text-primary)',
          borderRadius: 8,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
        }}
      >
        <FormRuntimeComponent
          form={formForComponent}
          fields={collectionData.fields ?? []}
          onSubmit={(data) => { submitMutation.mutate(data); }}
          submitLabel={submitMutation.isPending ? '提交中…' : '提交'}
          onTriggerWorkflow={triggerWorkflow}
        />
      </div>
    </div>
  );
}
