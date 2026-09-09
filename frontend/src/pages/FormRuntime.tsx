import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { ApiResponse, CollectionMeta } from '@/types/collection';
import type { FormFull } from '@/types/form';
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
    queryFn: () => apiClient.get<ApiResponse<FormFull>>(`/forms/${formId}`),
    enabled: !!formId,
  });

  const { data: collectionData } = useQuery({
    queryKey: ['collection', formData?.data.collection_name],
    queryFn: () =>
      apiClient.get<ApiResponse<CollectionMeta>>(
        `/collections/${formData?.data.collection_name}`
      ),
    enabled: !!formData?.data.collection_name,
  });

  const submitMutation = useMutation({
    mutationFn: async (payload: Record<string, unknown>) => {
      const collectionName = formData?.data.collection_name;
      if (!collectionName) throw new Error('collection 不存在');
      return apiClient.post<ApiResponse<{ id: string }>>(
        `/collections/${collectionName}/records`,
        payload
      );
    },
    onSuccess: () => {
      alert('提交成功!');
      navigate(`/designer/collections/${formData?.data.collection_name}`);
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      alert('提交失败:' + (e.response?.data?.message ?? '未知错误'));
    },
  });

  if (isLoading) return <p>加载中…</p>;
  if (!formData?.data) return <p>表单不存在</p>;
  if (!collectionData?.data) return <p>关联的 Collection 不存在</p>;

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      <button
        onClick={() => navigate(`/designer/collections/${formData.data.collection_name}`)}
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
          form={formData.data}
          fields={collectionData.data.fields ?? []}
          onSubmit={(data) => submitMutation.mutateAsync(data)}
          submitLabel={submitMutation.isPending ? '提交中…' : '提交'}
        />
      </div>
    </div>
  );
}
