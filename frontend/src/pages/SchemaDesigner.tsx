import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { FormulaEditor } from '@/components/views/FormulaEditor';
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


const FIELD_TYPE_LABELS: Record<string, string> = {
  text: '文本',
  email: '邮箱',
  url: '网址',
  phone: '电话',
  number: '数字',
  currency: '货币',
  percent: '百分比',
  duration: '时长',
  rating: '评分',
  autonumber: '自动编号',
  boolean: '布尔',
  date: '日期',
  datetime: '日期时间',
  createdTime: '创建时间',
  lastModifiedTime: '修改时间',
  select: '单选',
  multiSelect: '多选',
  attachment: '附件',
  belongsTo: '属于',
  hasMany: '拥有多个',
  createdBy: '创建人',
  lastModifiedBy: '修改人',
  formula: '公式',
  rollup: '汇总',
  lookup: '查找',
};

export function SchemaDesignerPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
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
      setError(e.response?.data?.message ?? '创建失败');
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
      setError('Collection 名称必须是小写字母开头的英文/数字/下划线');
      return;
    }
    if (fields.length === 0) {
      setError('至少添加一个字段');
      return;
    }
    createMutation.mutate();
  };

  return (
    <div>
      <h1>📐 Schema Designer</h1>
      <p style={{ color: 'var(--color-text-disabled)' }}>
        定义一个新的数据表,字段将存为 JSONB(支持零锁表扩展).
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
          <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>
            Collection 名称 <span style={{ color: 'var(--color-error)' }}>*</span>
          </label>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="customer"
            style={{ padding: 8, fontSize: 14, width: 300, fontFamily: 'monospace' }}
          />
          <div style={{ fontSize: 12, color: 'var(--color-text-disabled)', marginTop: 4 }}>
            英文小写字母开头,只允许字母数字下划线
          </div>
        </div>

        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>
            显示标题
          </label>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="客户"
            style={{ padding: 8, fontSize: 14, width: 300 }}
          />
        </div>

        <h3 style={{ marginBottom: 8 }}>字段列表</h3>
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
            {fields.map((f, i) => (
              <tr key={i} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                <td style={{ padding: 8 }}>
                  <input
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
                    {FIELD_TYPES.map((t) => {
                      const label = FIELD_TYPE_LABELS[t] || t;
                      const isAuto = AUTO_FIELD_TYPES.has(t);
                      const isDerived = DERIVED_FIELD_TYPES.has(t);
                      const suffix = isAuto ? ' (自动)' : isDerived ? ' (派生)' : '';
                      return <option key={t} value={t}>{label}{suffix}</option>;
                    })}
                  </select>
                  {f.type === 'formula' && (
                    <button
                      onClick={() => setFormulaTarget(i)}
                      style={{
                        marginLeft: 4,
                        padding: '2px 6px',
                        fontSize: 12,
                        background: 'var(--color-bg-secondary)',
                        border: '1px solid var(--color-border-light)',
                        borderRadius: 4,
                        cursor: 'pointer',
                      }}
                      title="编辑公式表达式"
                    >
                      🧮
                    </button>
                  )}
                </td>
                <td style={{ padding: 8 }}>
                  <input
                    type="checkbox"
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
                    删除
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        <div style={{ display: 'flex', gap: 8 }}>
          <button onClick={addField} style={{ padding: '8px 16px', cursor: 'pointer' }}>
            + 添加字段
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
            {createMutation.isPending ? '创建中…' : '保存 Collection'}
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
    </div>
  );
}
