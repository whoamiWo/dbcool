import { Fragment, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type {
  CollectionMeta,
  FieldDef,
  MigrationJob,
  MutationResponse,
} from '@/types/collection';

export function SchemaEditorPage() {
  const { name } = useParams<{ name: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [fields, setFields] = useState<FieldDef[]>([]);
  const [removedFields, setRemovedFields] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pollingJob, setPollingJob] = useState<string | null>(null);

  const { data: metaData, isLoading } = useQuery({
    queryKey: ['collection', name],
    queryFn: () => apiClient.get<CollectionMeta>(`/collections/${name}`),
    enabled: !!name,
  });

  useEffect(() => {
    if (metaData) {
      setTitle(metaData.title);
      setDescription(metaData.description);
      setFields(metaData.fields ?? []);
    }
  }, [metaData]);

  /**
   * US-004: 加载全部 collection,供关联字段(belongsTo/hasMany)选择目标表。
   * 后端 RelationResolver.expandRelations 依赖 options.target 展开关联,
   * 此前前端无任何入口设置它 —— 关联字段实际不可用。
   */
  const { data: collectionsData } = useQuery({
    queryKey: ['collections'],
    queryFn: async () => {
      const r = await apiClient.get<CollectionMeta[]>('/collections');
      // 兼容 vitest mock 直接返数组 + 真后端 envelope
      return (Array.isArray(r) ? r : (r as unknown as { data?: CollectionMeta[] })?.data) ?? [];
    },
  });
  const collections: CollectionMeta[] = collectionsData ?? [];

  const jobQuery = useQuery({
    queryKey: ['migration-job', pollingJob],
    queryFn: () =>
      apiClient.get<MigrationJob>(`/collections/_jobs/${pollingJob}`),
    enabled: !!pollingJob,
    refetchInterval: (q) => {
      const status = q.state.data?.status;
      return status === 'COMPLETED' || status === 'FAILED' ? false : 2000;
    },
  });

  useEffect(() => {
    if (jobQuery.data?.status === 'COMPLETED') {
      queryClient.invalidateQueries({ queryKey: ['collection', name] });
      setPollingJob(null);
    }
  }, [jobQuery, name, queryClient]);

  const updateMetaMutation = useMutation({
    mutationFn: () =>
      apiClient.patch<CollectionMeta>(`/collections/${name}`, {
        title,
        description,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['collection', name] });
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '更新失败');
    },
  });

  const handleAddField = () => {
    setFields([
      ...fields,
      { name: `field_${fields.length + 1}`, type: 'text', required: false },
    ]);
  };

  const handleRemoveField = (index: number) => {
    const removed = fields[index];
    if (window.confirm(`确定删除字段 "${removed.name}"?这会从物理表删除该列。`)) {
      setRemovedFields([...removedFields, removed.name]);
      setFields(fields.filter((_, i) => i !== index));
    }
  };

  const handleRenameField = (index: number, newName: string) => {
    const updated = [...fields];
    updated[index] = { ...updated[index], name: newName };
    setFields(updated);
  };

  const handleTypeChange = (index: number, newType: FieldDef['type']) => {
    const updated = [...fields];
    updated[index] = { ...updated[index], type: newType };
    setFields(updated);
  };

  /** US-004: 设置字段 options(如关联字段的 target 目标表)。 */
  const handleOptionChange = (index: number, key: string, value: string) => {
    const updated = [...fields];
    updated[index] = {
      ...updated[index],
      options: { ...(updated[index].options ?? {}), [key]: value },
    };
    setFields(updated);
  };

  const handleSubmit = async () => {
    setError(null);
    if (!name) return;

    try {
      await updateMetaMutation.mutateAsync();

      for (const removed of removedFields) {
        const res = await apiClient.delete<MutationResponse>(
          `/collections/${name}/fields/${removed}`
        );
        if (res.async && res.job_id) {
          setPollingJob(res.job_id);
        }
      }
      setRemovedFields([]);

      for (const f of fields) {
        const wasInOriginal = metaData?.fields?.some((orig) => orig.name === f.name);
        if (!wasInOriginal) {
          const res = await apiClient.post<MutationResponse>(
            `/collections/${name}/fields`,
            f
          );
          if (res.async && res.job_id) {
            setPollingJob(res.job_id);
          }
        }
      }
      queryClient.invalidateQueries({ queryKey: ['collection', name] });
    } catch (e) {
      const err = e as { response?: { data?: { message?: string } } };
      setError(err.response?.data?.message ?? '保存失败');
    }
  };

  if (isLoading) return <p>加载中…</p>;
  if (!metaData) return <p>Collection 不存在</p>;

  return (
    <div>
      <button
        onClick={() => navigate(`/designer/collections/${name}`)}
        style={{
          background: 'none',
          border: 'none',
          color: 'var(--color-text-disabled)',
          cursor: 'pointer',
          marginBottom: 16,
        }}
      >
        ← 返回详情
      </button>

      <h1>
        ✏️ 编辑 Schema
        <span style={{ color: 'var(--color-text-disabled)', fontSize: 14, fontWeight: 'normal' }}>({name})</span>
      </h1>

      {pollingJob && (
        <div
          style={{
            padding: 12,
            marginBottom: 16,
            background: 'rgba(59,130,246,0.1)',
            borderRadius: 4,
            color: 'var(--color-info)',
          }}
        >
          ⏳ 异步迁移进行中...Job: <code>{pollingJob}</code>
          <br />
          状态: <strong>{jobQuery.data?.status ?? 'PENDING'}</strong>
          {jobQuery.data?.status === 'COMPLETED' && ' ✅ 已完成'}
          {jobQuery.data?.status === 'FAILED' &&
            ` ❌ 失败: ${jobQuery.data?.error}`}
        </div>
      )}

      {error && (
        <div
          style={{
            padding: 8,
            marginBottom: 12,
            background: 'rgba(239,68,68,0.2)',
            color: 'var(--color-error)',
            borderRadius: 4,
          }}
        >
          {error}
        </div>
      )}

      <div
        style={{
          padding: 16,
          background: 'var(--color-text-primary)',
          borderRadius: 8,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
        }}
      >
        <div style={{ marginBottom: 12 }}>
          <label style={{ display: 'block', marginBottom: 4 }}>标题</label>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            style={{ padding: 8, width: 300 }}
          />
        </div>
        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 4 }}>描述</label>
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            style={{ padding: 8, width: '100%', maxWidth: 500 }}
          />
        </div>

        <h3>字段列表({fields.length})</h3>
        <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 12 }}>
          <thead>
            <tr style={{ background: 'var(--color-bg-secondary)' }}>
              <th style={{ padding: 8, textAlign: 'left' }}>字段名</th>
              <th style={{ padding: 8, textAlign: 'left' }}>类型</th>
              <th style={{ padding: 8, textAlign: 'left' }}>必填</th>
              <th style={{ padding: 8 }}></th>
            </tr>
          </thead>
          <tbody>
            {fields.map((f, i) => {
              const isRelation = f.type === 'belongsTo' || f.type === 'hasMany';
              const targetName = String(
                (f.options as Record<string, unknown> | undefined)?.target ?? ''
              );
              return (
              <Fragment key={i}>
              <tr style={{ borderTop: '1px solid var(--color-border-light)' }}>
                <td style={{ padding: 8 }}>
                  <input
                    value={f.name}
                    onChange={(e) => handleRenameField(i, e.target.value)}
                    style={{ padding: 4, fontFamily: 'monospace', width: '100%' }}
                  />
                </td>
                <td style={{ padding: 8 }}>
                  <select
                    value={f.type}
                    onChange={(e) =>
                      handleTypeChange(i, e.target.value as FieldDef['type'])
                    }
                    style={{ padding: 4 }}
                  >
                    <option value="text">text</option>
                    <option value="number">number</option>
                    <option value="boolean">boolean</option>
                    <option value="date">date</option>
                    <option value="datetime">datetime (Week 41 D1.1)</option>
                    <option value="select">select</option>
                    <option value="multiSelect">multiSelect</option>
                    <option value="attachment">attachment (Week 41 D1.2)</option>
                    <option value="belongsTo">belongsTo</option>
                    <option value="hasMany">hasMany</option>
                    <option value="formula">formula</option>
                  </select>
                </td>
                <td style={{ padding: 8 }}>
                  <input
                    type="checkbox"
                    checked={f.required}
                    onChange={(e) => {
                      const updated = [...fields];
                      updated[i] = { ...updated[i], required: e.target.checked };
                      setFields(updated);
                    }}
                  />
                </td>
                <td style={{ padding: 8, textAlign: 'center' }}>
                  <button
                    onClick={() => handleRemoveField(i)}
                    style={{
                      background: 'var(--color-error)',
                      color: 'var(--color-text-primary)',
                      border: 'none',
                      padding: '4px 8px',
                      borderRadius: 4,
                      cursor: 'pointer',
                    }}
                  >
                    删除
                  </button>
                </td>
              </tr>
              {/* US-004: 关联字段额外配置行 —— 选择目标表并显示对方字段 */}
              {isRelation && (
                <tr>
                  <td colSpan={4} style={{ padding: '4px 8px 12px', background: 'var(--color-text-primary)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <span style={{ fontSize: 12, color: 'var(--color-bg-elevated)' }}>关联表(target):</span>
                      <select
                        value={targetName}
                        onChange={(e) => handleOptionChange(i, 'target', e.target.value)}
                        aria-label={`字段 ${f.name} 关联表`}
                        style={{ padding: 4, fontSize: 12 }}
                      >
                        <option value="">-- 选择关联表 --</option>
                        {collections
                          .filter((c) => c.name !== name)
                          .map((c) => (
                            <option key={c.name} value={c.name}>
                              {c.title || c.name}
                            </option>
                          ))}
                      </select>
                      <TargetFieldsPreview collectionName={targetName} />
                    </div>
                  </td>
                </tr>
              )}
              </Fragment>
              );
            })}
          </tbody>
        </table>

        <div style={{ display: 'flex', gap: 8 }}>
          <button onClick={handleAddField} style={{ padding: '8px 16px', cursor: 'pointer' }}>
            + 添加字段
          </button>
          <button
            onClick={handleSubmit}
            disabled={updateMetaMutation.isPending || !!pollingJob}
            style={{
              padding: '8px 16px',
              background: updateMetaMutation.isPending || pollingJob ? 'var(--color-text-muted)' : 'var(--color-bg-secondary)',
              color: 'var(--color-text-primary)',
              border: 'none',
              cursor: updateMetaMutation.isPending || pollingJob ? 'not-allowed' : 'pointer',
            }}
          >
            {updateMetaMutation.isPending ? '保存中…' : '保存修改'}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * US-004: 展示关联目标表的字段 —— 验收要求「选关联表时显示对方字段」。
 */
function TargetFieldsPreview({ collectionName }: { collectionName: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['collection', collectionName],
    queryFn: async () => {
      const r = await apiClient.get<CollectionMeta>(`/collections/${collectionName}`);
      return (r as unknown as { data?: CollectionMeta })?.data ?? r ?? null;
    },
    enabled: !!collectionName,
  });

  if (!collectionName) return null;
  if (isLoading) return <span style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>加载字段…</span>;

  const targetFields = data?.fields ?? [];
  if (targetFields.length === 0) {
    return <span style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>该表暂无字段</span>;
  }
  return (
    <span style={{ fontSize: 11, color: 'var(--color-text-disabled)' }}>
      对方字段({targetFields.length}):{' '}
      {targetFields.map((f) => f.label ?? f.name).join('、')}
    </span>
  );
}