import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta, FieldDef, FieldType } from '@/types/collection';
import type { FormFull, FormLayoutItem, FormRules, ValidationRule } from '@/types/form';
import { FormRuntime } from '@/components/forms/FormRuntime';

const FIELD_ICON: Record<FieldType, string> = {
  text: '📝',
  date: '📅',
  datetime: '⏰', // Week 41 D1.1
  boolean: '☑️',
  select: '📋',
  multiSelect: '📋',
  attachment: '📎', // Week 41 D1.2
  belongsTo: '🔗',
  hasMany: '🔗',
  formula: '🧮',
  number: '🔢',
};

export function FormDesignerPage() {
  const { collection, id } = useParams<{ collection: string; id?: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [title, setTitle] = useState('新表单');
  const [description, setDescription] = useState('');
  const [layout, setLayout] = useState<FormLayoutItem[]>([]);
  const [rules, setRules] = useState<FormRules>({});
  const [error, setError] = useState<string | null>(null);
  const [activeField, setActiveField] = useState<string | null>(null);
  const [dragOverIdx, setDragOverIdx] = useState<number | null>(null);
  const [fields, setFields] = useState<FieldDef[]>([]);

  const { data: collectionData } = useQuery({
    queryKey: ['collection', collection],
    queryFn: () => apiClient.get<CollectionMeta>(`/collections/${collection}`),
    enabled: !!collection,
  });

  const { data: formData } = useQuery({
    queryKey: ['form', id],
    queryFn: () => apiClient.get<FormFull>(`/forms/${id}`),
    enabled: !!id,
  });

  useEffect(() => {
    if (formData) {
      const f = formData;
      setTitle(f.title);
      setDescription(f.description);
      setLayout(f.layout);
      setRules(f.rules);
    }
  }, [formData]);

  /** 当 collectionData 字段变化时同步到可编辑状态。 */
  useEffect(() => {
    if (collectionData?.fields) {
      setFields(collectionData.fields);
    }
  }, [collectionData?.fields]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      const payload = {
        title,
        description,
        layout: JSON.stringify(layout),
        rules: JSON.stringify(rules),
        fields: fields,  // US-003: 提交字段属性(primaryKey/unique/defaultValue)
      };
      if (id) {
        return apiClient.put<FormFull>(`/forms/${id}`, payload);
      }
      return apiClient.post<FormFull>('/forms', {
        collectionName: collection,
        ...payload,
      });
    },
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['forms', collection] });
      if (!id) {
        navigate(`/designer/forms/${collection}/${res.id}/edit`);
      }
      setError(null);
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '保存失败');
    },
  });

  // —— Drag & Drop ——
  // 拖动源可能是「左侧新字段」(data: 'new:<name>') 或「已布局字段」(data: 'move:<name>')
  const handleDragStart = (e: React.DragEvent, fieldName: string, source: 'palette' | 'layout') => {
    e.dataTransfer.setData('application/x-form-field', source === 'palette' ? `new:${fieldName}` : `move:${fieldName}`);
    e.dataTransfer.effectAllowed = 'move';
  };

  const handleDragOver = (e: React.DragEvent, idx: number | 'end') => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    setDragOverIdx(idx === 'end' ? layout.length : idx);
  };

  const handleDragLeaveRow = () => {
    setDragOverIdx(null);
  };

  const handleDrop = (e: React.DragEvent, dropIdx: number | 'end') => {
    e.preventDefault();
    setDragOverIdx(null);
    const payload = e.dataTransfer.getData('application/x-form-field');
    if (!payload) return;
    const [op, fieldName] = payload.split(':');
    const targetIdx = dropIdx === 'end' ? layout.length : dropIdx;
    if (op === 'new') {
      if (layout.some((l) => l.field === fieldName)) return;
      const newLayout = [...layout];
      newLayout.splice(targetIdx, 0, { field: fieldName, span: 24 });
      setLayout(newLayout);
    } else if (op === 'move') {
      const currentIdx = layout.findIndex((l) => l.field === fieldName);
      if (currentIdx < 0) return;
      const newLayout = [...layout];
      const [moved] = newLayout.splice(currentIdx, 1);
      // 调整目标位置:如果拖到原位置之后,需要减 1
      const adjusted = currentIdx < targetIdx ? targetIdx - 1 : targetIdx;
      newLayout.splice(adjusted, 0, moved);
      setLayout(newLayout);
    }
  };

  const removeFromLayout = (fieldName: string) => {
    setLayout(layout.filter((l) => l.field !== fieldName));
    const newRules = { ...rules };
    delete newRules.visibility?.[fieldName];
    delete newRules.validation?.[fieldName];
    setRules(newRules);
    if (activeField === fieldName) setActiveField(null);
  };

  const moveField = (fieldName: string, direction: 'up' | 'down') => {
    const idx = layout.findIndex((l) => l.field === fieldName);
    if (idx < 0) return;
    const newIdx = direction === 'up' ? idx - 1 : idx + 1;
    if (newIdx < 0 || newIdx >= layout.length) return;
    const newLayout = [...layout];
    [newLayout[idx], newLayout[newIdx]] = [newLayout[newIdx], newLayout[idx]];
    setLayout(newLayout);
  };

  const toggleRequiredValidation = (fieldName: string, required: boolean) => {
    const fieldValidations: ValidationRule[] = rules.validation?.[fieldName] ?? [];
    const filtered = fieldValidations.filter((v) => v.type !== 'required');
    const updated = required ? [...filtered, { type: 'required' }] : filtered;
    setRules({ ...rules, validation: { ...rules.validation, [fieldName]: updated as any } });
  };

  const isFieldRequired = (fieldName: string) =>
    rules.validation?.[fieldName]?.some((v) => v.type === 'required') ?? false;

  /** US-003: 切换字段属性(primaryKey/unique/defaultValue/label)。 */
  const toggleFieldProp = (fieldName: string, prop: 'primaryKey' | 'unique' | 'defaultValue' | 'label', value: boolean | string) => {
    setFields(fields.map((f) =>
      f.name === fieldName ? { ...f, [prop]: value } : f
    ));
  };

  /** US-102: 更新字段 options 内的展示配置(placeholder/helpText)。 */
  const toggleOptionProp = (fieldName: string, key: string, value: string) => {
    setFields(fields.map((f) =>
      f.name === fieldName
        ? { ...f, options: { ...(f.options ?? {}), [key]: value } }
        : f
    ));
  };

  if (!collection) {
    return <p>缺少 collection 参数</p>;
  }

  const previewForm: FormFull = {
    id: 'preview',
    collection_name: collection,
    title,
    description,
    layout_json: JSON.stringify(layout),
    rules_json: JSON.stringify(rules),
    tenant_id: '',
    created_at: '',
    updated_at: null,
    layout,
    rules,
  };

  return (
    <div>
      <button
        onClick={() => navigate(`/designer/collections/${collection}`)}
        style={{ background: 'none', border: 'none', color: '#64748b', cursor: 'pointer', marginBottom: 16 }}
      >
        ← 返回 Collection
      </button>

      <h1>
        📋 {id ? '编辑' : '新建'}表单
        <span style={{ color: '#64748b', fontSize: 14, fontWeight: 'normal' }}>({collection})</span>
        {layout.length > 0 && (
          <span style={{ marginLeft: 12, fontSize: 13, color: '#10b981' }}>
            🎯 {layout.length} 字段 — 拖拽调整顺序
          </span>
        )}
      </h1>

      {error && (
        <div style={{ padding: 8, marginBottom: 12, background: '#fee2e2', color: '#991b1b', borderRadius: 4 }}>
          {error}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '200px 1fr 280px', gap: 16 }}>
        {/* 字段面板(可拖源) */}
        <div style={{ padding: 12, background: 'white', borderRadius: 8 }}>
          <h3 style={{ marginTop: 0 }}>可用字段</h3>
          <p style={{ fontSize: 11, color: '#64748b', margin: '0 0 8px' }}>拖拽到画布 ↘</p>
          {fields.length === 0 ? (
            <p style={{ color: '#94a3b8', fontSize: 12 }}>该 collection 还没有字段</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {fields.map((f) => {
                const added = layout.some((l) => l.field === f.name);
                return (
                  <div
                    key={f.name}
                    draggable
                    onDragStart={(e) => handleDragStart(e, f.name, 'palette')}
                    onDragEnd={handleDragLeaveRow}
                    onDoubleClick={() => !added && setLayout([...layout, { field: f.name, span: 24 }])}
                    style={{
                      padding: 6,
                      fontSize: 12,
                      background: added ? '#e2e8f0' : '#f1f5f9',
                      color: added ? '#94a3b8' : '#0f172a',
                      border: '1px solid #cbd5e1',
                      borderRadius: 4,
                      cursor: added ? 'not-allowed' : 'grab',
                      userSelect: 'none',
                      opacity: added ? 0.6 : 1,
                    }}
                    title={added ? '已在画布' : '拖动到画布,或双击追加到末尾'}
                  >
                    {added ? '✓ ' : ''}{FIELD_ICON[f.type] ?? '🧩'} {f.label ?? f.name}{' '}
                    <span style={{ color: '#94a3b8' }}>({f.type})</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* 画布 */}
        <div
          style={{ padding: 16, background: 'white', borderRadius: 8 }}
          onDragOver={(e) => handleDragOver(e, layout.length)}
          onDrop={(e) => handleDrop(e, 'end')}
        >
          <div style={{ marginBottom: 12 }}>
            <label style={{ display: 'block', fontWeight: 500 }}>表单标题</label>
            <input value={title} onChange={(e) => setTitle(e.target.value)} style={{ padding: 8, width: '100%', fontSize: 16 }} />
          </div>
          <div style={{ marginBottom: 16 }}>
            <label style={{ display: 'block', fontWeight: 500 }}>描述</label>
            <input value={description} onChange={(e) => setDescription(e.target.value)} style={{ padding: 8, width: '100%' }} />
          </div>

          <h3>布局</h3>
          {layout.length === 0 ? (
            <div
              onDragOver={(e) => handleDragOver(e, 0)}
              onDrop={(e) => handleDrop(e, 0)}
              style={{
                padding: 48,
                border: '2px dashed #cbd5e1',
                borderRadius: 8,
                textAlign: 'center',
                color: '#94a3b8',
                background: dragOverIdx === 0 ? '#dbeafe' : '#f8fafc',
              }}
            >
              从左侧拖动字段到这里
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              {layout.map((item, idx) => {
                const f = fieldMap(item.field, fields);
                const isDragOver = dragOverIdx === idx;
                return (
                  <div key={item.field}>
                    {/* 拖入占位符 */}
                    <div
                      onDragOver={(e) => { e.stopPropagation(); handleDragOver(e, idx); }}
                      onDrop={(e) => { e.stopPropagation(); handleDrop(e, idx); }}
                      style={{
                        height: 4,
                        background: isDragOver ? '#3b82f6' : 'transparent',
                        borderRadius: 2,
                        margin: '2px 0',
                        transition: 'all 0.1s',
                      }}
                    />
                    <div
                      draggable
                      onDragStart={(e) => handleDragStart(e, item.field, 'layout')}
                      onDragEnd={handleDragLeaveRow}
                      onClick={() => setActiveField(item.field)}
                      style={{
                        display: 'flex', alignItems: 'center', gap: 8,
                        padding: 8,
                        background: activeField === item.field ? '#dbeafe' : '#f8fafc',
                        border: `1px solid ${activeField === item.field ? '#3b82f6' : '#cbd5e1'}`,
                        borderRadius: 4,
                        cursor: 'grab',
                      }}
                    >
                      <span style={{ color: '#94a3b8', cursor: 'grab' }}>⋮⋮</span>
                      <span style={{ flex: 1 }}>
                        {idx + 1}. {FIELD_ICON[f?.type ?? 'text']} <strong>{f?.label ?? item.field}</strong>{' '}
                        <span style={{ color: '#94a3b8', fontSize: 12 }}>({f?.type ?? '?'})</span>
                        {isFieldRequired(item.field) && <span style={{ color: '#dc2626' }}> *</span>}
                      </span>
                      <button
                        onClick={(e) => { e.stopPropagation(); moveField(item.field, 'up'); }}
                        disabled={idx === 0}
                        style={{ padding: '2px 8px', cursor: idx === 0 ? 'not-allowed' : 'pointer' }}
                        title="上移"
                      >↑</button>
                      <button
                        onClick={(e) => { e.stopPropagation(); moveField(item.field, 'down'); }}
                        disabled={idx === layout.length - 1}
                        style={{ padding: '2px 8px', cursor: idx === layout.length - 1 ? 'not-allowed' : 'pointer' }}
                        title="下移"
                      >↓</button>
                      <button
                        onClick={(e) => e.stopPropagation()}
                        style={{ padding: '2px 8px', background: '#8b5cf6', color: 'white', border: 'none', borderRadius: 4, opacity: 0.4 }}
                        title="复制(预留)"
                      >⧉</button>
                      <button
                        onClick={(e) => { e.stopPropagation(); removeFromLayout(item.field); }}
                        style={{ padding: '2px 8px', background: '#dc2626', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                        title="删除"
                      >×</button>
                    </div>
                  </div>
                );
              })}
              {/* 末尾占位符 */}
              <div
                onDragOver={(e) => { e.stopPropagation(); handleDragOver(e, layout.length); }}
                onDrop={(e) => { e.stopPropagation(); handleDrop(e, layout.length); }}
                style={{
                  height: 8,
                  background: dragOverIdx === layout.length ? '#3b82f6' : 'transparent',
                  borderRadius: 2,
                  margin: '4px 0',
                }}
              />
            </div>
          )}

          <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
            <button
              onClick={() => saveMutation.mutate()}
              disabled={saveMutation.isPending}
              style={{
                padding: '8px 16px',
                background: saveMutation.isPending ? '#94a3b8' : '#1e293b',
                color: 'white', border: 'none', borderRadius: 4,
                cursor: saveMutation.isPending ? 'not-allowed' : 'pointer',
              }}
            >
              {saveMutation.isPending ? '保存中…' : '保存表单'}
            </button>
            {saveMutation.isSuccess && (
              <span style={{ alignSelf: 'center', color: '#10b981', fontSize: 12 }}>✅ 已保存</span>
            )}
          </div>
        </div>

        {/* 属性面板 */}
        <div style={{ padding: 12, background: 'white', borderRadius: 8 }}>
          <h3 style={{ marginTop: 0 }}>字段属性</h3>
          {!activeField ? (
            <p style={{ color: '#94a3b8', fontSize: 12 }}>点击画布中的字段查看属性</p>
          ) : (
            <div>
              <p>
                <strong>{fields.find((f) => f.name === activeField)?.label ?? activeField}</strong>
                <br />
                <span style={{ color: '#94a3b8', fontSize: 12 }}>
                  {fields.find((f) => f.name === activeField)?.type}
                </span>
              </p>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
                <input
                  type="checkbox"
                  checked={isFieldRequired(activeField)}
                  onChange={(e) => toggleRequiredValidation(activeField, e.target.checked)}
                />
                必填
              </label>
              {/* US-003: 主键/唯一/默认值 */}
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
                <input
                  type="checkbox"
                  checked={fields.find((f) => f.name === activeField)?.primaryKey ?? false}
                  onChange={(e) => toggleFieldProp(activeField, 'primaryKey', e.target.checked)}
                />
                主键
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
                <input
                  type="checkbox"
                  checked={fields.find((f) => f.name === activeField)?.unique ?? false}
                  onChange={(e) => toggleFieldProp(activeField, 'unique', e.target.checked)}
                />
                唯一
              </label>
              <div style={{ marginBottom: 8 }}>
                <label style={{ fontSize: 12, color: '#64748b' }}>默认值</label>
                <input
                  type="text"
                  value={fields.find((f) => f.name === activeField)?.defaultValue ?? ''}
                  onChange={(e) => toggleFieldProp(activeField, 'defaultValue', e.target.value)}
                  style={{ padding: 4, width: '100%', fontSize: 12 }}
                />
              </div>
              {/* US-102: 标签 / 占位符 / 帮助文本 */}
              <div style={{ marginBottom: 8 }}>
                <label style={{ fontSize: 12, color: '#64748b' }}>标签(label)</label>
                <input
                  type="text"
                  value={fields.find((f) => f.name === activeField)?.label ?? ''}
                  onChange={(e) => toggleFieldProp(activeField, 'label', e.target.value)}
                  style={{ padding: 4, width: '100%', fontSize: 12 }}
                />
              </div>
              <div style={{ marginBottom: 8 }}>
                <label style={{ fontSize: 12, color: '#64748b' }}>占位符(placeholder)</label>
                <input
                  type="text"
                  value={String((fields.find((f) => f.name === activeField)?.options as Record<string, unknown> | undefined)?.placeholder ?? '')}
                  onChange={(e) => toggleOptionProp(activeField, 'placeholder', e.target.value)}
                  style={{ padding: 4, width: '100%', fontSize: 12 }}
                />
              </div>
              <div style={{ marginBottom: 8 }}>
                <label style={{ fontSize: 12, color: '#64748b' }}>帮助文本(helpText)</label>
                <input
                  type="text"
                  value={String((fields.find((f) => f.name === activeField)?.options as Record<string, unknown> | undefined)?.helpText ?? '')}
                  onChange={(e) => toggleOptionProp(activeField, 'helpText', e.target.value)}
                  style={{ padding: 4, width: '100%', fontSize: 12 }}
                />
              </div>
              <p style={{ fontSize: 12, color: '#94a3b8', marginTop: 12 }}>
                💡 可拖拽画布字段重排序。显隐规则、校验规则、提交动作见下方。
              </p>
            </div>
          )}
        </div>
      </div>

      <div style={{ marginTop: 24 }}>
        <h2>👀 实时预览</h2>
        <div style={{ padding: 24, background: '#f8fafc', borderRadius: 8 }}>
          {layout.length > 0 ? (
            <FormRuntime
              form={previewForm}
              fields={fields}
              onSubmit={() => alert('预览模式,不会真的提交')}
              submitLabel="预览提交"
            />
          ) : (
            <p style={{ color: '#94a3b8', textAlign: 'center' }}>添加字段后看预览</p>
          )}
        </div>
      </div>
    </div>
  );
}

function fieldMap(name: string, fields: FieldDef[]): FieldDef | undefined {
  return fields.find((f) => f.name === name);
}
