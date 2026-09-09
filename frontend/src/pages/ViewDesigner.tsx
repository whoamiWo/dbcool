import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { ApiResponse, CollectionMeta, FieldDef } from '@/types/collection';
import type { CreateViewRequest, ViewFull, ViewType } from '@/types/view';

/** 视图设计器(Week 9) — 创建/编辑视图 */
export function ViewDesignerPage() {
  const { collection, id } = useParams<{ collection: string; id?: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [name, setName] = useState('new_view');
  const [title, setTitle] = useState('新视图');
  const [type, setType] = useState<ViewType>('table');
  const [groupBy, setGroupBy] = useState<string>('');
  const [pageSize, setPageSize] = useState(20);
  const [error, setError] = useState<string | null>(null);

  const { data: collectionData } = useQuery({
    queryKey: ['collection', collection],
    queryFn: () => apiClient.get<CollectionMeta>(`/collections/${collection}`),
    enabled: !!collection,
  });
  const fields: FieldDef[] = collectionData?.data.fields ?? [];

  const { data: viewData } = useQuery({
    queryKey: ['view', id],
    queryFn: () => apiClient.get<ViewFull>(`/views/${id}`),
    enabled: !!id,
  });

  useEffect(() => {
    if (viewData?.data) {
      const v = viewData.data;
      setName(v.name);
      setTitle(v.title);
      setType(v.type);
    }
  }, [viewData]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      const config: Record<string, unknown> = {};
      if (type === 'table') config.pageSize = pageSize;
      if (type === 'kanban') config.groupBy = groupBy;

      const body: CreateViewRequest = {
        collectionName: collection!,
        name,
        title,
        type,
        config: JSON.stringify(config),
      };
      if (id) {
        return apiClient.put<ViewFull>(`/views/${id}`, body);
      }
      return apiClient.post<ViewFull>('/views', body);
    },
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['views', collection] });
      const viewId = id ?? res.data.id;
      navigate(`/views/${viewId}/run`);
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '保存失败');
    },
  });

  if (!collection) return <p>缺少 collection 参数</p>;
  if (!/^[a-z][a-z0-9_]{0,63}$/.test(name)) {
    setError('name 必须是小写字母开头的英文/数字/下划线');
  }

  return (
    <div>
      <button
        onClick={() => navigate(`/designer/collections/${collection}`)}
        style={{ background: 'none', border: 'none', color: '#64748b', cursor: 'pointer', marginBottom: 16 }}
      >
        ← 返回 {collection}
      </button>
      <h1>{id ? '编辑' : '新建'}视图</h1>

      {error && (
        <div style={{ padding: 8, marginBottom: 12, background: '#fee2e2', color: '#991b1b', borderRadius: 4 }}>
          {error}
        </div>
      )}

      <div
        style={{
          padding: 16,
          background: 'white',
          borderRadius: 8,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          maxWidth: 600,
        }}
      >
        <div style={{ marginBottom: 12 }}>
          <label style={{ display: 'block', fontWeight: 500 }}>技术名称</label>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            style={{ padding: 8, width: '100%', fontFamily: 'monospace' }}
          />
        </div>
        <div style={{ marginBottom: 12 }}>
          <label style={{ display: 'block', fontWeight: 500 }}>显示标题</label>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            style={{ padding: 8, width: '100%' }}
          />
        </div>
        <div style={{ marginBottom: 12 }}>
          <label style={{ display: 'block', fontWeight: 500 }}>类型</label>
          <select value={type} onChange={(e) => setType(e.target.value as ViewType)} style={{ padding: 8, width: '100%' }}>
            <option value="table">表格视图</option>
            <option value="kanban">看板视图</option>
            <option value="detail">详情视图</option>
          </select>
        </div>

        {type === 'kanban' && (
          <div style={{ marginBottom: 12 }}>
            <label style={{ display: 'block', fontWeight: 500 }}>按哪个字段分组</label>
            <select value={groupBy} onChange={(e) => setGroupBy(e.target.value)} style={{ padding: 8, width: '100%' }}>
              <option value="">-- 选择字段 --</option>
              {fields.map((f) => (
                <option key={f.name} value={f.name}>
                  {f.label ?? f.name}
                </option>
              ))}
            </select>
          </div>
        )}

        {type === 'table' && (
          <div style={{ marginBottom: 12 }}>
            <label style={{ display: 'block', fontWeight: 500 }}>每页条数</label>
            <input
              type="number"
              value={pageSize}
              onChange={(e) => setPageSize(Number(e.target.value))}
              style={{ padding: 8, width: '100%' }}
            />
          </div>
        )}

        <div style={{ padding: 8, background: '#f8fafc', borderRadius: 4, fontSize: 12, color: '#64748b' }}>
          💡 Week 9 MVP:筛选/排序/列控制在视图打开后操作。本周先把视图创建跑通。
        </div>

        <button
          onClick={() => saveMutation.mutate()}
          disabled={saveMutation.isPending}
          style={{
            marginTop: 16,
            padding: '8px 16px',
            background: saveMutation.isPending ? '#94a3b8' : '#1e293b',
            color: 'white',
            border: 'none',
            borderRadius: 4,
            cursor: saveMutation.isPending ? 'not-allowed' : 'pointer',
          }}
        >
          {saveMutation.isPending ? '保存中…' : '保存并打开'}
        </button>
      </div>
    </div>
  );
}
