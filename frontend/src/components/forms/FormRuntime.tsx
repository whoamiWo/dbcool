import { useState } from 'react';
import type { FieldDef, FormFull, FormLayoutItem } from '@/types/form';

interface FormRuntimeProps {
  form: FormFull;
  fields: FieldDef[];
  onSubmit: (data: Record<string, unknown>) => Promise<void> | void;
  submitLabel?: string;
}

/**
 * 运行时表单渲染器 — Week 8 MVP:
 * - 根据 layout 顺序显示字段
 * - 支持 visibility 显隐
 * - 支持 validation 校验
 * - 提交时调用 onSubmit
 */
export function FormRuntime({ form, fields, onSubmit, submitLabel = '提交' }: FormRuntimeProps) {
  const [data, setData] = useState<Record<string, unknown>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  const fieldMap = new Map(fields.map((f) => [f.name, f]));

  const isVisible = (fieldName: string): boolean => {
    const rule = form.rules.visibility?.[fieldName];
    if (!rule) return true;
    const triggerVal = data[rule.when];
    const targetVal = rule.value;
    switch (rule.op) {
      case 'eq': return triggerVal === targetVal;
      case 'neq': return triggerVal !== targetVal;
      case 'in': return Array.isArray(targetVal) && targetVal.includes(triggerVal);
      case 'notIn': return Array.isArray(targetVal) && !targetVal.includes(triggerVal);
      case 'empty': return triggerVal == null || triggerVal === '';
      case 'notEmpty': return triggerVal != null && triggerVal !== '';
      case 'gt': return Number(triggerVal) > Number(targetVal);
      case 'lt': return Number(triggerVal) < Number(targetVal);
      default: return true;
    }
  };

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};
    for (const f of fields) {
      if (!isVisible(f.name)) continue;
      const rules = form.rules.validation?.[f.name] ?? [];
      const val = data[f.name];
      for (const r of rules) {
        switch (r.type) {
          case 'required':
            if (val == null || val === '') {
              newErrors[f.name] = r.message ?? `${f.label ?? f.name} 必填`;
            }
            break;
          case 'minLength':
            if (typeof val === 'string' && val.length < Number(r.value)) {
              newErrors[f.name] = r.message ?? `至少 ${r.value} 个字符`;
            }
            break;
          case 'maxLength':
            if (typeof val === 'string' && val.length > Number(r.value)) {
              newErrors[f.name] = r.message ?? `最多 ${r.value} 个字符`;
            }
            break;
          case 'min':
            if (Number(val) < Number(r.value)) {
              newErrors[f.name] = r.message ?? `不能小于 ${r.value}`;
            }
            break;
          case 'max':
            if (Number(val) > Number(r.value)) {
              newErrors[f.name] = r.message ?? `不能大于 ${r.value}`;
            }
            break;
          case 'pattern':
            if (typeof val === 'string' && r.value && !new RegExp(String(r.value)).test(val)) {
              newErrors[f.name] = r.message ?? '格式不正确';
            }
            break;
          case 'email':
            if (typeof val === 'string' && !/^[^@]+@[^@]+\.[^@]+$/.test(val)) {
              newErrors[f.name] = r.message ?? '邮箱格式不正确';
            }
            break;
        }
      }
    }
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    setSubmitting(true);
    try {
      await onSubmit(data);
      setData({});
    } catch (err) {
      // 静默失败:onSubmit 通常自己处理错误并显示给用户
      // 不重置 data,允许用户修改后重新提交
      // eslint-disable-next-line no-console
      console.error('FormRuntime submit error:', err);
    } finally {
      setSubmitting(false);
    }
  };

  const renderField = (item: FormLayoutItem) => {
    const f = fieldMap.get(item.field);
    if (!f) return null;
    if (!isVisible(f.name)) return null;
    const span = item.span ?? 24;

    return (
      <div key={f.name} style={{ gridColumn: `span ${span}`, marginBottom: 12 }}>
        <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>
          {f.label ?? f.name}
          {f.required && <span style={{ color: '#dc2626' }}> *</span>}
          <span style={{ color: '#94a3b8', fontSize: 12, marginLeft: 8 }}>({f.type})</span>
        </label>
        {renderInput(f)}
        {errors[f.name] && (
          <div style={{ color: '#dc2626', fontSize: 12, marginTop: 2 }}>
            {errors[f.name]}
          </div>
        )}
      </div>
    );
  };

  const renderInput = (f: FieldDef) => {
    const value = (data[f.name] ?? '') as string;
    const onChange = (v: string) => setData({ ...data, [f.name]: v });

    switch (f.type) {
      case 'text':
        return (
          <input
            value={value}
            onChange={(e) => onChange(e.target.value)}
            style={{ ...inputStyle, width: '100%' }}
            placeholder={f.label ?? f.name}
          />
        );
      case 'number':
        return (
          <input
            type="number"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            style={{ ...inputStyle, width: '100%' }}
          />
        );
      case 'boolean':
        return (
          <input
            type="checkbox"
            checked={value === 'true'}
            onChange={(e) => onChange(e.target.checked ? 'true' : 'false')}
          />
        );
      case 'date':
        return (
          <input
            type="date"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            style={{ ...inputStyle, width: '100%' }}
          />
        );
      case 'select':
        return (
          <select value={value} onChange={(e) => onChange(e.target.value)} style={{ ...inputStyle, width: '100%' }}>
            <option value="">--请选择--</option>
            {Object.entries((f.options ?? {}) as Record<string, string>).map(([k, v]) => (
              <option key={k} value={k}>{v}</option>
            ))}
          </select>
        );
      case 'multiSelect':
        return (
          <select
            multiple
            value={Array.isArray(value) ? value : []}
            onChange={(e) => {
              const opts = Array.from(e.target.selectedOptions).map((o) => o.value);
              onChange(opts.join(','));
            }}
            style={{ ...inputStyle, width: '100%', minHeight: 80 }}
          >
            {Object.entries((f.options ?? {}) as Record<string, string>).map(([k, v]) => (
              <option key={k} value={k}>{v}</option>
            ))}
          </select>
        );
      default:
        return (
          <input
            value={value}
            onChange={(e) => onChange(e.target.value)}
            style={{ ...inputStyle, width: '100%' }}
            placeholder={`(暂不支持类型 ${f.type})`}
          />
        );
    }
  };

  return (
    <form onSubmit={handleSubmit}>
      <h2 style={{ marginTop: 0 }}>{form.title}</h2>
      {form.description && (
        <p style={{ color: '#64748b', marginTop: 0 }}>{form.description}</p>
      )}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(24, 1fr)',
          gap: 12,
        }}
      >
        {form.layout.map(renderField)}
      </div>
      <button
        type="submit"
        disabled={submitting}
        style={{
          marginTop: 16,
          padding: '10px 24px',
          background: submitting ? '#94a3b8' : '#1e293b',
          color: 'white',
          border: 'none',
          borderRadius: 4,
          cursor: submitting ? 'not-allowed' : 'pointer',
        }}
      >
        {submitting ? '提交中…' : submitLabel}
      </button>
    </form>
  );
}

const inputStyle: React.CSSProperties = {
  padding: 8,
  fontSize: 14,
  border: '1px solid #cbd5e1',
  borderRadius: 4,
};