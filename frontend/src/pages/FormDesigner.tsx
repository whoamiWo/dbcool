import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { ApiResponse, CollectionMeta, FieldDef } from '@/types/collection';
import type { FormFull, FormLayoutItem, FormRules, ValidationRule } from '@/types/form';
import { FormRuntime } from '@/components/forms/FormRuntime';

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

  const { data: collectionData } = useQuery({
    queryKey: ['collection', collection],
    queryFn: () =>
      apiClient.get<ApiResponse<CollectionMeta>>(`/collections/${collection}`),
    enabled: !!collection,
  });

  const fields: FieldDef[] = collectionData?.data.fields ?? [];

  const { data: formData } = useQuery({
    queryKey: ['form', id],
    queryFn: () => apiClient.get<ApiResponse<FormFull>>(`/forms/${id}`),
    enabled: !!id,
  });

  useEffect(() => {
    if (formData?.data) {
      const f = formData.data;
      setTitle(f.title);
      setDescription(f.description);
      setLayout(f.layout);
      setRules(f.rules);
    }
  }, [formData]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      const payload = {
        title,
        description,
        layout: JSON.stringify(layout),
        rules: JSON.stringify(rules),
      };
      if (id) {
        return apiClient.put<ApiResponse<FormFull>>(`/forms/${id}`, payload);
      }
      return apiClient.post<ApiResponse<FormFull>>('/forms', {
        collectionName: collection,
        ...payload,
      });
    },
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['forms', collection] });
      if (!id) {
        navigate(`/designer/forms/${collection}/${res.data.id}/edit`);
      }
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '保存失败');
    },
  });

  const addToLayout = (fieldName: string) => {
    if (layout.some((l) => l.field === fieldName)) return;
    setLayout([...layout, { field: fieldName, span: 24 }]);
  };

  const removeFromLayout = (fieldName: string) => {
    setLayout(layout.filter((l) => l.field !== fieldName));
    const newRules = { ...rules };
    delete newRules.visibility?.[fieldName];
    delete newRules.validation?.[fieldName];
    setRules(newRules);
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
    setRules({ ...rules, validation: { ...rules.validation, [fieldName]: updated } });
  };

  const isFieldRequired = (fieldName: string) =>
    rules.validation?.[fieldName]?.some((v) => v.type === 'required') ?? false;

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
      </h1>

      {error && (
        <div style={{ padding: 8, marginBottom: 12, background: '#fee2e2', color: '#991b1b', borderRadius: 4 }}>
          {error}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '200px 1fr 280px', gap: 16 }}>
        <div style={{ padding: 12, background: 'white', borderRadius: 8 }}>
          <h3 style={{ marginTop: 0 }}>可用字段</h3>
          {fields.length === 0 ? (
            <p style={{ color: '#94a3b8', fontSize: 12 }}>该 collection 还没有字段</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {fields.map((f) => {
                const added = layout.some((l) => l.field === f.name);
                return (
                  <button
                    key={f.name}
                    onClick={() => addToLayout(f.name)}
                    disabled={added}
                    style={{
                      padding: 6, fontSize: 12,
                      background: added ? '#e2e8f0' : '#f1f5f9',
                      color: added ? '#94a3b8' : '#0f172a',
                      border: '1px solid #cbd5e1',
                      borderRadius: 4,
                      cursor: added ? 'not-allowed' : 'pointer',
                      textAlign: 'left',
                    }}
                  >
                    {added ? '✓ ' : '+ '}{f.label ?? f.name}{' '}
                    <span style={{ color: '#94a3b8' }}>({f.type})</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        <div style={{ padding: 16, background: 'white', borderRadius: 8 }}>
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
            <p style={{ color: '#94a3b8' }}>从左侧添加字段</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {layout.map((item, idx) => {
                const f = fieldMap(item.field, fields);
                return (
                  <div
                    key={item.field}
                    onClick={() => setActiveField(item.field)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 8,
                      padding: 8,
                      background: activeField === item.field ? '#dbeafe' : '#f8fafc',
                      border: '1px solid #cbd5e1', borderRadius: 4,
                      cursor: 'pointer',
                    }}
                  >
                    <span style={{ flex: 1 }}>
                      {idx + 1}. <strong>{f?.label ?? item.field}</strong>{' '}
                      <span style={{ color: '#94a3b8', fontSize: 12 }}>({f?.type})</span>
                      {isFieldRequired(item.field) && <span style={{ color: '#dc2626' }}> *</span>}
                    </span>
                    <button
                      onClick={(e) => { e.stopPropagation(); moveField(item.field, 'up'); }}
                      disabled={idx === 0}
                      style={{ padding: '2px 8px', cursor: idx === 0 ? 'not-allowed' : 'pointer' }}
                    >↑</button>
                    <button
                      onClick={(e) => { e.stopPropagation(); moveField(item.field, 'down'); }}
                      disabled={idx === layout.length - 1}
                      style={{ padding: '2px 8px', cursor: idx === layout.length - 1 ? 'not-allowed' : 'pointer' }}
                    >↓</button>
                    <button
                      onClick={(e) => { e.stopPropagation(); removeFromLayout(item.field); }}
                      style={{ padding: '2px 8px', background: '#dc2626', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                    >删</button>
                  </div>
                );
              })}
            </div>
          )}

          <div style={{ marginTop: 16 }}>
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
          </div>
        </div>

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
              <p style={{ fontSize: 12, color: '#94a3b8', marginTop: 12 }}>
                💡 Week 8 MVP:简化属性。Week 9+ 加显隐规则、联动等。
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