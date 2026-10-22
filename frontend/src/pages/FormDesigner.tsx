import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useIsMobile } from '@/hooks/useIsMobile';
import apiClient from '@/api/client';
import type { CollectionMeta, FieldDef, FieldType } from '@/types/collection';
import type { FormFull, FormLayoutItem, FormRules, SubmitAction, ValidationRule } from '@/types/form';
import { FormRuntime } from '@/components/forms/FormRuntime';
import { FieldRulesEditor } from '@/components/forms/FieldRulesEditor';

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
  rollup: '📊',
  lookup: '🔍',
  number: '🔢',
  // T1 新增格式类型
  email: '✉️',
  url: '🔗',
  phone: '📞',
  // T1 新增数值类型
  currency: '💰',
  percent: '%',
  duration: '⏱️',
  // T1 新增评分类型
  rating: '⭐',
  // T1 新增系统类型 (只读)
  createdTime: '🕐',
  lastModifiedTime: '🕐',
  createdBy: '👤',
  lastModifiedBy: '👤',
  autonumber: '#️⃣',
};

export function FormDesignerPage() {
  const { collection, id } = useParams<{ collection: string; id?: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { t } = useTranslation();
  const isMobile = useIsMobile();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [layout, setLayout] = useState<FormLayoutItem[]>([]);
  const [rules, setRules] = useState<FormRules>({});
  const [error, setError] = useState<string | null>(null);
  const [activeField, setActiveField] = useState<string | null>(null);
  const [dragOverIdx, setDragOverIdx] = useState<number | null>(null);
  const [fields, setFields] = useState<FieldDef[]>([]);
  /** US-105:预览是否只读(禁用输入、显示全部字段、隐藏提交)。 */
  const [previewReadOnly, setPreviewReadOnly] = useState(false);

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

  /** 新建模式:表单标题默认「新表单」(可 i18n);编辑模式由表单数据回填。 */
  useEffect(() => {
    if (!formData && !id) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：新建模式默认标题，仅挂载/切 id 时执行
      setTitle(t('formDesigner.newForm'));
    }
  }, [formData, id, t]);

  useEffect(() => {
    if (formData) {
      const f = formData;
      // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：异步/条件/初始化模式，非连续渲染
      setTitle(f.title);
      setDescription(f.description);
      setLayout(f.layout);
      setRules(f.rules);
    }
  }, [formData]);

  /** 当 collectionData 字段变化时同步到可编辑状态。 */
   
  useEffect(() => {
    if (collectionData?.fields) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：异步/条件/初始化模式，非连续渲染
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
      setError(e.response?.data?.message ?? t('formDesigner.saveFailed'));
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
    return <p>{t('formDesigner.missingCollectionParam')}</p>;
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
        style={{ background: 'none', border: 'none', color: 'var(--color-text-disabled)', cursor: 'pointer', marginBottom: 16 }}
      >
        {t('formDesigner.backToCollection')}
      </button>

      <h1>
        📋 {id ? t('formDesigner.editForm') : t('formDesigner.newForm')}
        <span style={{ color: 'var(--color-text-disabled)', fontSize: 14, fontWeight: 'normal' }}>({collection})</span>
        {layout.length > 0 && (
          <span style={{ marginLeft: 12, fontSize: 13, color: 'var(--color-success)' }}>
            🎯 {t('formDesigner.fieldCountHint', { count: layout.length })}
          </span>
        )}
      </h1>

      {error && (
        <div style={{ padding: 8, marginBottom: 12, background: 'rgba(239,68,68,0.2)', color: 'var(--color-error)', borderRadius: 4 }}>
          {error}
        </div>
      )}

      {/* US-108: 移动端单列堆叠,平板双列,桌面三列 */}
      <div style={{ display: 'grid', gridTemplateColumns: isMobile ? '1fr' : '200px 1fr 280px', gap: isMobile ? 8 : 16 }}>
        {/* 字段面板(可拖源) */}
        <div style={{ padding: 12, background: 'var(--color-text-primary)', borderRadius: 8 }}>
          <h3 style={{ marginTop: 0 }}>{t('formDesigner.availableFields')}</h3>
          <p style={{ fontSize: 11, color: 'var(--color-text-disabled)', margin: '0 0 8px' }}>{t('formDesigner.dragToCanvas')}</p>
          {fields.length === 0 ? (
            <p style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>{t('formDesigner.noFields')}</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {fields.map((f) => {
                const added = layout.some((l) => l.field === f.name);
                return (
                  // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- 拖拽手柄用 HTML5 DnD，非点击交互，不赋 button 语义以免误导 AT
                  <div
                    key={f.name}
                    draggable
                    onDragStart={(e) => handleDragStart(e, f.name, 'palette')}
                    onDragEnd={handleDragLeaveRow}
                    onDoubleClick={() => !added && setLayout([...layout, { field: f.name, span: 24 }])}
                    style={{
                      padding: 6,
                      fontSize: 12,
                      background: added ? 'var(--color-border-light)' : 'var(--color-bg-secondary)',
                      color: added ? 'var(--color-text-muted)' : 'var(--color-bg-primary)',
                      border: '1px solid var(--color-border-medium)',
                      borderRadius: 4,
                      cursor: added ? 'not-allowed' : 'grab',
                      userSelect: 'none',
                      opacity: added ? 0.6 : 1,
                    }}
                    title={added ? t('formDesigner.fieldAdded') : t('formDesigner.fieldNotAdded')}
                  >
                    {added ? '✓ ' : ''}{FIELD_ICON[f.type] ?? '🧩'} {f.label ?? f.name}{' '}
                    <span style={{ color: 'var(--color-text-muted)' }}>({f.type})</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* 画布 */}
        <div role="presentation"
          style={{ padding: 16, background: 'var(--color-text-primary)', borderRadius: 8 }}
          onDragOver={(e) => handleDragOver(e, layout.length)}
          onDrop={(e) => handleDrop(e, 'end')}
        >
          <div style={{ marginBottom: 12 }}>
            <label style={{ display: 'block', fontWeight: 500 }} htmlFor="f-lbl-1">{t('formDesigner.formTitle')}</label>
            <input id="f-lbl-1" value={title} onChange={(e) => setTitle(e.target.value)} style={{ padding: 8, width: '100%', fontSize: 16 }} />
          </div>
          <div style={{ marginBottom: 16 }}>
            <label style={{ display: 'block', fontWeight: 500 }} htmlFor="f-lbl-2">{t('formDesigner.description')}</label>
            <input id="f-lbl-2" value={description} onChange={(e) => setDescription(e.target.value)} style={{ padding: 8, width: '100%' }} />
          </div>

          <h3>{t('formDesigner.layout')}</h3>
          {layout.length === 0 ? (
            <div role="presentation"
              onDragOver={(e) => handleDragOver(e, 0)}
              onDrop={(e) => handleDrop(e, 0)}
              style={{
                padding: 48,
                border: '2px dashed var(--color-border-medium)',
                borderRadius: 8,
                textAlign: 'center',
                color: 'var(--color-text-muted)',
                background: dragOverIdx === 0 ? 'rgba(59,130,246,0.1)' : 'var(--color-text-primary)',
              }}
            >
              {t('formDesigner.dropFieldsHere')}
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              {layout.map((item, idx) => {
                const f = fieldMap(item.field, fields);
                const isDragOver = dragOverIdx === idx;
                return (
                  <div key={item.field}>
                    {/* 拖入占位符 */}
                    <div role="presentation"
                      onDragOver={(e) => { e.stopPropagation(); handleDragOver(e, idx); }}
                      onDrop={(e) => { e.stopPropagation(); handleDrop(e, idx); }}
                      style={{
                        height: 4,
                        background: isDragOver ? 'var(--color-info)' : 'transparent',
                        borderRadius: 2,
                        margin: '2px 0',
                        transition: 'all 0.1s',
                      }}
                    />
                    <div role="button" tabIndex={0} onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); e.currentTarget.click(); } }}
                      draggable
                      onDragStart={(e) => handleDragStart(e, item.field, 'layout')}
                      onDragEnd={handleDragLeaveRow}
                      onClick={() => setActiveField(item.field)}
                      style={{
                        display: 'flex', alignItems: 'center', gap: 8,
                        padding: 8,
                        background: activeField === item.field ? 'rgba(59,130,246,0.1)' : 'var(--color-text-primary)',
                        border: `1px solid ${activeField === item.field ? 'var(--color-info)' : 'var(--color-border-medium)'}`,
                        borderRadius: 4,
                        cursor: 'grab',
                      }}
                    >
                      <span style={{ color: 'var(--color-text-muted)', cursor: 'grab' }}>⋮⋮</span>
                      <span style={{ flex: 1 }}>
                        {idx + 1}. {FIELD_ICON[f?.type ?? 'text']} <strong>{f?.label ?? item.field}</strong>{' '}
                        <span style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>({f?.type ?? '?'})</span>
                        {isFieldRequired(item.field) && <span style={{ color: 'var(--color-error)' }}> *</span>}
                      </span>
                      <button
                        onClick={(e) => { e.stopPropagation(); moveField(item.field, 'up'); }}
                        disabled={idx === 0}
                        style={{ padding: '2px 8px', cursor: idx === 0 ? 'not-allowed' : 'pointer' }}
                        title={t('formDesigner.moveUp')}
                      >↑</button>
                      <button
                        onClick={(e) => { e.stopPropagation(); moveField(item.field, 'down'); }}
                        disabled={idx === layout.length - 1}
                        style={{ padding: '2px 8px', cursor: idx === layout.length - 1 ? 'not-allowed' : 'pointer' }}
                        title={t('formDesigner.moveDown')}
                      >↓</button>
                      <button
                        onClick={(e) => e.stopPropagation()}
                        style={{ padding: '2px 8px', background: 'var(--color-secondary-500)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, opacity: 0.4 }}
                        title={t('formDesigner.duplicate')}
                      >⧉</button>
                      <button
                        onClick={(e) => { e.stopPropagation(); removeFromLayout(item.field); }}
                        style={{ padding: '2px 8px', background: 'var(--color-error)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                        title={t('formDesigner.removeField')}
                      >×</button>
                    </div>
                  </div>
                );
              })}
              {/* 末尾占位符（HTML5 DnD 拖放区，纯装饰性容器 → presentation） */}
              <div role="presentation"
                onDragOver={(e) => { e.stopPropagation(); handleDragOver(e, layout.length); }}
                onDrop={(e) => { e.stopPropagation(); handleDrop(e, layout.length); }}
                style={{
                  height: 8,
                  background: dragOverIdx === layout.length ? 'var(--color-info)' : 'transparent',
                  borderRadius: 2,
                  margin: '4px 0',
                }}
              />
            </div>
          )}

          {/* US-106: 提交后动作(表单级规则) */}
          <div style={{ marginTop: 16, borderTop: '1px solid var(--color-border-light)', paddingTop: 12 }}>
            <strong style={{ fontSize: 13 }}>{t('formDesigner.afterSubmit')}</strong>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6 }}>
              <select
                value={rules.submit?.action ?? 'stay'}
                onChange={(e) =>
                  setRules({
                    ...rules,
                    submit: { action: e.target.value as SubmitAction },
                  })
                }
                style={{ padding: 6, fontSize: 12, borderRadius: 4, border: '1px solid var(--color-border-medium)' }}
                aria-label={t('formDesigner.afterSubmit')}
              >
                <option value="stay">{t('formDesigner.actionStay')}</option>
                <option value="redirect">{t('formDesigner.actionRedirect')}</option>
                <option value="workflow">{t('formDesigner.actionWorkflow')}</option>
              </select>
            </div>

            {rules.submit?.action === 'redirect' && (
              <input
                type="text"
                value={rules.submit.url ?? ''}
                onChange={(e) => setRules({ ...rules, submit: { ...rules.submit!, url: e.target.value } })}
                placeholder={t('formDesigner.redirectPlaceholder')}
                aria-label={t('formDesigner.redirectUrl')}
                style={{ marginTop: 6, padding: 6, fontSize: 12, width: '100%', borderRadius: 4, border: '1px solid var(--color-border-medium)' }}
              />
            )}

            {rules.submit?.action === 'workflow' && (
              <input
                type="text"
                value={rules.submit.workflowId ?? ''}
                onChange={(e) =>
                  setRules({ ...rules, submit: { ...rules.submit!, workflowId: e.target.value } })
                }
                placeholder={t('formDesigner.workflowIdPlaceholder')}
                aria-label={t('formDesigner.workflowId')}
                style={{ marginTop: 6, padding: 6, fontSize: 12, width: '100%', borderRadius: 4, border: '1px solid var(--color-border-medium)' }}
              />
            )}

            {rules.submit?.action && rules.submit.action !== 'redirect' && (
              <input
                type="text"
                value={rules.submit.message ?? ''}
                onChange={(e) =>
                  setRules({ ...rules, submit: { ...rules.submit!, message: e.target.value } })
                }
                placeholder={rules.submit.action === 'workflow' ? t('formDesigner.workflowMessagePlaceholder') : t('formDesigner.stayMessagePlaceholder')}
                aria-label={t('formDesigner.submitMessage')}
                style={{ marginTop: 6, padding: 6, fontSize: 12, width: '100%', borderRadius: 4, border: '1px solid var(--color-border-medium)' }}
              />
            )}
          </div>

          <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
            <button
              onClick={() => saveMutation.mutate()}
              disabled={saveMutation.isPending}
              style={{
                padding: '8px 16px',
                background: saveMutation.isPending ? 'var(--color-text-muted)' : 'var(--color-bg-secondary)',
                color: 'var(--color-text-primary)', border: 'none', borderRadius: 4,
                cursor: saveMutation.isPending ? 'not-allowed' : 'pointer',
              }}
            >
              {saveMutation.isPending ? t('formDesigner.saving') : t('formDesigner.saveForm')}
            </button>
            {saveMutation.isSuccess && (
              <span style={{ alignSelf: 'center', color: 'var(--color-success)', fontSize: 12 }}>{t('formDesigner.saved')}</span>
            )}
          </div>
        </div>

        {/* 属性面板 */}
        <div style={{ padding: 12, background: 'var(--color-text-primary)', borderRadius: 8 }}>
          <h3 style={{ marginTop: 0 }}>{t('formDesigner.fieldProperties')}</h3>
          {!activeField ? (
            <p style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>{t('formDesigner.selectFieldHint')}</p>
          ) : (
            <div>
              <p>
                <strong>{fields.find((f) => f.name === activeField)?.label ?? activeField}</strong>
                <br />
                <span style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>
                  {fields.find((f) => f.name === activeField)?.type}
                </span>
              </p>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
                <input
                  type="checkbox"
                  checked={isFieldRequired(activeField)}
                  onChange={(e) => toggleRequiredValidation(activeField, e.target.checked)}
                />
                {t('formDesigner.required')}
              </label>
              {/* US-003: 主键/唯一/默认值 */}
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
                <input
                  type="checkbox"
                  checked={fields.find((f) => f.name === activeField)?.primaryKey ?? false}
                  onChange={(e) => toggleFieldProp(activeField, 'primaryKey', e.target.checked)}
                />
                {t('formDesigner.primaryKey')}
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
                <input
                  type="checkbox"
                  checked={fields.find((f) => f.name === activeField)?.unique ?? false}
                  onChange={(e) => toggleFieldProp(activeField, 'unique', e.target.checked)}
                />
                {t('formDesigner.unique')}
              </label>
              <div style={{ marginBottom: 8 }}>
                <label style={{ fontSize: 12, color: 'var(--color-text-disabled)' }} htmlFor="f-lbl-3">{t('formDesigner.defaultValue')}</label>
                <input id="f-lbl-3"
                  type="text"
                  value={fields.find((f) => f.name === activeField)?.defaultValue ?? ''}
                  onChange={(e) => toggleFieldProp(activeField, 'defaultValue', e.target.value)}
                  style={{ padding: 4, width: '100%', fontSize: 12 }}
                />
              </div>
              {/* US-102: 标签 / 占位符 / 帮助文本 */}
              <div style={{ marginBottom: 8 }}>
                <label style={{ fontSize: 12, color: 'var(--color-text-disabled)' }} htmlFor="f-lbl-4">{t('formDesigner.label')}</label>
                <input id="f-lbl-4"
                  type="text"
                  value={fields.find((f) => f.name === activeField)?.label ?? ''}
                  onChange={(e) => toggleFieldProp(activeField, 'label', e.target.value)}
                  style={{ padding: 4, width: '100%', fontSize: 12 }}
                />
              </div>
              <div style={{ marginBottom: 8 }}>
                <label style={{ fontSize: 12, color: 'var(--color-text-disabled)' }} htmlFor="f-lbl-5">{t('formDesigner.placeholder')}</label>
                <input id="f-lbl-5"
                  type="text"
                  value={String((fields.find((f) => f.name === activeField)?.options as Record<string, unknown> | undefined)?.placeholder ?? '')}
                  onChange={(e) => toggleOptionProp(activeField, 'placeholder', e.target.value)}
                  style={{ padding: 4, width: '100%', fontSize: 12 }}
                />
              </div>
              <div style={{ marginBottom: 8 }}>
                <label style={{ fontSize: 12, color: 'var(--color-text-disabled)' }} htmlFor="f-lbl-6">{t('formDesigner.helpText')}</label>
                <input id="f-lbl-6"
                  type="text"
                  value={String((fields.find((f) => f.name === activeField)?.options as Record<string, unknown> | undefined)?.helpText ?? '')}
                  onChange={(e) => toggleOptionProp(activeField, 'helpText', e.target.value)}
                  style={{ padding: 4, width: '100%', fontSize: 12 }}
                />
              </div>
              {/* US-103 显隐规则 + US-104 校验规则 */}
              <FieldRulesEditor
                fieldName={activeField}
                allFields={fields}
                rules={rules}
                onChange={setRules}
              />
              <p style={{ fontSize: 12, color: 'var(--color-text-muted)', marginTop: 12 }}>
                {t('formDesigner.previewHint')}
              </p>
            </div>
          )}
        </div>
      </div>

      <div style={{ marginTop: 24 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
          <h2 style={{ margin: 0 }}>{t('formDesigner.livePreview')}</h2>
          <label
            style={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 4, color: 'var(--color-bg-elevated)' }}
          >
            <input
              type="checkbox"
              checked={previewReadOnly}
              onChange={(e) => setPreviewReadOnly(e.target.checked)}
            />
            {t('formDesigner.readOnlyMode')}
          </label>
        </div>
        <div style={{ padding: 24, background: 'var(--color-text-primary)', borderRadius: 8 }}>
          {layout.length > 0 ? (
            <FormRuntime
              form={previewForm}
              fields={fields}
              onSubmit={() => alert(t('formDesigner.previewSubmitAlert'))}
              submitLabel={t('formDesigner.previewSubmitLabel')}
              readOnly={previewReadOnly}
            />
          ) : (
            <p style={{ color: 'var(--color-text-muted)', textAlign: 'center' }}>{t('formDesigner.previewEmpty')}</p>
          )}
        </div>
      </div>
    </div>
  );
}

function fieldMap(name: string, fields: FieldDef[]): FieldDef | undefined {
  return fields.find((f) => f.name === name);
}
