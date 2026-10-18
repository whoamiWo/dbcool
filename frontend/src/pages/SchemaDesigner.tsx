import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import apiClient from '@/api/client';
import { FormulaEditor } from '@/components/views/FormulaEditor';
import { Screen } from '@/components/Screen';
import type { CollectionMeta, FieldDef, FieldType } from '@/types/collection';

/**
 * 字段类型清单 — PHASE71 方案 B:前端硬编码对齐后端 AsyncMigrationService.mapJsonbType。
 * 契约测试 FieldTypesContract.test.ts 断言本清单 ⊆ 后端支持类型,防漂移。
 *
 * 分类(与后端映射一致):
 * - 基础文本: text / email / url / phone
 * - 数值: number / currency / percent / duration / rating / autonumber
 * - 布尔: boolean
 * - 时间: date / datetime / createdTime / lastModifiedTime
 * - 枚举: select / multiSelect
 * - 文件: attachment
 * - 关联: belongsTo / hasMany / createdBy / lastModifiedBy
 * - 派生(只读,读取时计算): formula / rollup / lookup
 * - 系统自动(只读,不落用户输入): createdTime / lastModifiedTime / createdBy / lastModifiedBy / autonumber
 */
const FIELD_TYPES: FieldType[] = [
  // 基础文本
  'text', 'email', 'url', 'phone',
  // 数值
  'number', 'currency', 'percent', 'duration', 'rating', 'autonumber',
  // 布尔
  'boolean',
  // 时间
  'date', 'datetime', 'createdTime', 'lastModifiedTime',
  // 枚举
  'select', 'multiSelect',
  // 文件
  'attachment',
  // 关联
  'belongsTo', 'hasMany', 'createdBy', 'lastModifiedBy',
  // 派生
  'formula', 'rollup', 'lookup',
];

/** 自动填充字段(不可手工编辑) — T3 渲染只读。 */
const AUTO_FIELD_TYPES: ReadonlySet<string> = new Set([
  'createdTime', 'lastModifiedTime', 'createdBy', 'lastModifiedBy', 'autonumber',
]);

/** 派生字段(只读,读取时计算,不落物理列) — T3 渲染只读。 */
const DERIVED_FIELD_TYPES: ReadonlySet<string> = new Set(['formula', 'rollup', 'lookup']);

/** 获取字段类型显示标签(带 i18n 支持) */
function getFieldTypeLabel(t: TFunction, type: string): string {
  const key = `schemaDesigner.type.${type}`;
  return t(key);
}

export function SchemaDesignerPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [title, setTitle] = useState('');
  const [fields, setFields] = useState<FieldDef[]>([]);
  const [error, setError] = useState<string | null>(null);
  /** US-107: 公式编辑器状态(仅 formula 字段触发)。 */
  const [formulaTarget, setFormulaTarget] = useState<number | null>(null);

  const createMutation = useMutation({
    mutationFn: async () => {
      const res = await apiClient.post<CollectionMeta>(
        '/collections',
        { name, title: title || name, description: '', fields },
      );
      return res;
    },
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: ['collections'] });
      navigate(`/designer/collections/${created.name}`);
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? t('schemaDesigner.createFailed'));
    },
  });

  const addField = () => {
    setFields([
      ...fields,
      { name: `field_${fields.length + 1}`, type: 'text', required: false },
    ]);
  };

  const updateField = (index: number, patch: Partial<FieldDef>) => {
    setFields(fields.map((f, i) => (i === index ? { ...f, ...patch } : f)));
  };

  const removeField = (index: number) => {
    setFields(fields.filter((_, i) => i !== index));
  };

  const handleSubmit = () => {
    setError(null);
    if (!name || !/^[a-z][a-z0-9_]{0,63}$/.test(name)) {
      setError(t('schemaDesigner.nameInvalid'));
      return;
    }
    if (fields.length === 0) {
      setError(t('schemaDesigner.noFields'));
      return;
    }
    createMutation.mutate();
  };

  return (
    <Screen>
      <h1>{t('schemaDesigner.title')}</h1>
      <p style={{ color: 'var(--color-text-disabled)' }}>
        {t('schemaDesigner.subtitle')}
      </p>

      <div
        style={{
          padding: 16,
          background: 'var(--color-text-primary)',
          borderRadius: 8,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          maxWidth: 800,
        }}
      >
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

        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }} htmlFor="s-lbl-1">
            {t('schemaDesigner.collectionName')} <span style={{ color: 'var(--color-error)' }}>*</span>
          </label>
          <input id="s-lbl-1"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('schemaDesigner.collectionNamePlaceholder')}
            style={{ padding: 8, fontSize: 14, width: 300, fontFamily: 'monospace' }}
          />
          <div style={{ fontSize: 12, color: 'var(--color-text-disabled)', marginTop: 4 }}>
            {t('schemaDesigner.collectionNameHint')}
          </div>
        </div>

        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }} htmlFor="s-lbl-2">
            {t('schemaDesigner.displayTitle')}
          </label>
          <input id="s-lbl-2"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder={t('schemaDesigner.displayTitlePlaceholder')}
            style={{ padding: 8, fontSize: 14, width: 300 }}
          />
        </div>

        <h3 style={{ marginBottom: 8 }}>{t('schemaDesigner.fields')}</h3>
        <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 12 }}>
          <thead>
            <tr style={{ background: 'var(--color-bg-secondary)' }}>
              <th style={{ padding: 8, textAlign: 'left' }}>{t('schemaDesigner.colFieldName')}</th>
              <th style={{ padding: 8, textAlign: 'left' }}>{t('schemaDesigner.colType')}</th>
              <th style={{ padding: 8, textAlign: 'left' }}>{t('schemaDesigner.colRequired')}</th>
              <th style={{ padding: 8 }} aria-label={t('schemaDesigner.colAction')}></th>
            </tr>
          </thead>
          <tbody>
            {fields.map((f, i) => (
              <tr key={i} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                <td style={{ padding: 8 }}>
                  <input
                    aria-label={t('schemaDesigner.fieldAriaFieldName')}
                    value={f.name}
                    onChange={(e) => updateField(i, { name: e.target.value })}
                    style={{ padding: 4, width: '100%', fontFamily: 'monospace' }}
                  />
                </td>
                <td style={{ padding: 8 }}>
                  <select
                    value={f.type}
                    onChange={(e) => {
                      const newType = e.target.value as FieldType;
                      const updates: Partial<FieldDef> = { type: newType };
                      // 自动字段强制只读且不需要手动设置 required
                      if (AUTO_FIELD_TYPES.has(newType) || DERIVED_FIELD_TYPES.has(newType)) {
                        updates.required = false;
                      }
                      updateField(i, updates);
                    }}
                    style={{ padding: 4 }}
                    disabled={AUTO_FIELD_TYPES.has(f.type) || DERIVED_FIELD_TYPES.has(f.type)}
                  >
                    {FIELD_TYPES.map((tType) => {
                      const label = getFieldTypeLabel(t, tType) || tType;
                      const isAuto = AUTO_FIELD_TYPES.has(tType);
                      const isDerived = DERIVED_FIELD_TYPES.has(tType);
                      const suffix = isAuto ? t('schemaDesigner.autoSuffix') : isDerived ? t('schemaDesigner.derivedSuffix') : '';
                      return <option key={tType} value={tType}>{label}{suffix}</option>;
                    })}
                  </select>
                  {f.type === 'formula' && (
                    <button
                      onClick={() => setFormulaTarget(i)}
                      aria-label={t('schemaDesigner.fieldAriaFormula')}
                      title={t('schemaDesigner.fieldAriaFormula')}
                      style={{
                        marginLeft: 4,
                        padding: '2px 6px',
                        fontSize: 12,
                        background: 'var(--color-bg-secondary)',
                        border: '1px solid var(--color-border-light)',
                        borderRadius: 4,
                        cursor: 'pointer',
                      }}
                    >
                      🧮
                    </button>
                  )}
                </td>
                <td style={{ padding: 8 }}>
                  <input
                    type="checkbox"
                    aria-label={t('schemaDesigner.fieldAriaRequired')}
                    checked={f.required}
                    onChange={(e) => updateField(i, { required: e.target.checked })}
                  />
                </td>
                <td style={{ padding: 8, textAlign: 'center' }}>
                  <button
                    onClick={() => removeField(i)}
                    style={{
                      background: 'var(--color-error)',
                      color: 'var(--color-text-primary)',
                      border: 'none',
                      padding: '4px 8px',
                      borderRadius: 4,
                      cursor: 'pointer',
                    }}
                  >
                    {t('schemaDesigner.removeField')}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        <div style={{ display: 'flex', gap: 8 }}>
          <button onClick={addField} style={{ padding: '8px 16px', cursor: 'pointer' }}>
            {t('schemaDesigner.addField')}
          </button>
          <button
            onClick={handleSubmit}
            disabled={createMutation.isPending}
            style={{
              padding: '8px 16px',
              background: createMutation.isPending ? 'var(--color-text-muted)' : 'var(--color-bg-secondary)',
              color: 'var(--color-text-primary)',
              border: 'none',
              cursor: createMutation.isPending ? 'not-allowed' : 'pointer',
            }}
          >
            {createMutation.isPending ? t('schemaDesigner.saving') : t('schemaDesigner.saveCollection')}
          </button>
        </div>

        {/* US-107: 公式编辑器弹窗 */}
        {formulaTarget !== null && (
          <FormulaEditor
            open={true}
            onClose={() => setFormulaTarget(null)}
            onSave={(expr) => {
              updateField(formulaTarget, { options: { ...fields[formulaTarget].options, formula: expr } });
              setFormulaTarget(null);
            }}
            initialValue={fields[formulaTarget]?.options?.formula as string | undefined}
          />
        )}
      </div>
    </Screen>
  );
}
