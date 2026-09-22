import { useState } from 'react';
import type { FieldDef, FormFull, FormLayoutItem } from '@/types/form';

interface FormRuntimeProps {
  form: FormFull;
  fields: FieldDef[];
  onSubmit: (data: Record<string, unknown>) => Promise<void> | void;
  submitLabel?: string;
  /**
   * US-106:提交动作为 workflow 时的触发器。
   * 由调用方注入(而非组件内部直接调 apiClient),保持组件可测试。
   */
  onTriggerWorkflow?: (workflowId: string, data: Record<string, unknown>) => Promise<void> | void;
  /**
   * US-105:只读预览模式。
   * 启用后:禁用全部输入、显示所有字段(忽略显隐规则以便检查完整布局)、隐藏提交按钮。
   */
  readOnly?: boolean;
}

/**
 * 运行时表单渲染器 — Week 8 MVP:
 * - 根据 layout 顺序显示字段
 * - 支持 visibility 显隐
 * - 支持 validation 校验
 * - 提交时调用 onSubmit
 */
export function FormRuntime({
  form,
  fields,
  onSubmit,
  submitLabel = '提交',
  onTriggerWorkflow,
  readOnly = false,
}: FormRuntimeProps) {
  const [data, setData] = useState<Record<string, unknown>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  /** US-106:提交后动作(stay/workflow)展示给用户的提示。 */
  const [submitMessage, setSubmitMessage] = useState<string | null>(null);

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

  /**
   * US-106:执行提交后动作(rules.submit)。
   *
   * <p>三种动作:
   * <ul>
   *   <li><b>stay</b>:停留并显示提示 message</li>
   *   <li><b>redirect</b>:跳转到 url</li>
   *   <li><b>workflow</b>:触发工作流 —— 通过 onTriggerWorkflow 回调执行,
   *       保持本组件不直接依赖 apiClient(便于测试注入)</li>
   * </ul>
   */
  const runSubmitRule = async (payload: Record<string, unknown>) => {
    const rule = form.rules.submit;
    if (!rule) return;
    if (rule.action === 'stay') {
      setSubmitMessage(rule.message ?? '提交成功');
    } else if (rule.action === 'redirect' && rule.url) {
      window.location.href = rule.url;
    } else if (rule.action === 'workflow' && rule.workflowId) {
      await onTriggerWorkflow?.(rule.workflowId, payload);
      setSubmitMessage(rule.message ?? '已触发工作流');
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    setSubmitting(true);
    setSubmitMessage(null);
    try {
      await onSubmit(data);
      // US-106: 提交成功后执行配置的后续动作
      await runSubmitRule(data);
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

  /**
   * US-102:读取字段 options 中的展示配置。
   * 复用既有 options Map 承载 placeholder / helpText,避免改动后端 FieldDef 签名。
   */
  const optStr = (f: FieldDef, key: string): string | undefined => {
    const v = f.options?.[key];
    return typeof v === 'string' && v.length > 0 ? v : undefined;
  };

  const renderField = (item: FormLayoutItem) => {
    const f = fieldMap.get(item.field);
    if (!f) return null;
    // US-105:只读预览时忽略显隐规则,展示完整布局以便上线前检查
    if (!readOnly && !isVisible(f.name)) return null;
    const span = item.span ?? 24;
    const helpText = optStr(f, 'helpText');

    return (
      <div key={f.name} style={{ gridColumn: `span ${span}`, marginBottom: 12 }}>
        <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>
          {f.label ?? f.name}
          {f.required && <span style={{ color: 'var(--color-error)' }}> *</span>}
          <span style={{ color: 'var(--color-text-muted)', fontSize: 12, marginLeft: 8 }}>({f.type})</span>
        </label>
        {renderInput(f)}
        {helpText && (
          <div style={{ color: 'var(--color-text-disabled)', fontSize: 12, marginTop: 2 }}>
            {helpText}
          </div>
        )}
        {errors[f.name] && (
          <div style={{ color: 'var(--color-error)', fontSize: 12, marginTop: 2 }}>
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
            placeholder={optStr(f, 'placeholder') ?? f.label ?? f.name}
          />
        );
      case 'number':
        return (
          <input
            type="number"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            style={{ ...inputStyle, width: '100%' }}
            placeholder={optStr(f, 'placeholder') ?? f.label ?? f.name}
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
      case 'datetime':
        // Week 41 D1.1: datetime 与 date 共用 TIMESTAMPTZ,前端按 datetime-local 渲染(精确到分钟)
        return (
          <input
            type="datetime-local"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            style={{ ...inputStyle, width: '100%' }}
          />
        );
      case 'attachment':
        // Week 41 D1.2: Week 42+ 接 MinIO 后会升级为完整上传 UI
        // Week 41 暂为 placeholder 输入框,接收 storageKey 字符串
        return (
          <div style={{ display: 'flex', gap: 4 }}>
            <input
              value={value}
              onChange={(e) => onChange(e.target.value)}
              style={{ ...inputStyle, width: '100%' }}
              placeholder="storageKey (Week 42+ 接入 MinIO)"
            />
            <button
              type="button"
              disabled
              style={{
                padding: '4px 8px',
                background: 'var(--color-border-light)',
                color: 'var(--color-text-disabled)',
                border: 'none',
                borderRadius: 4,
                cursor: 'not-allowed',
                fontSize: 12,
              }}
              title="Week 42+ D1.4 MinIO 集成"
            >
              上传(Week 42+)
            </button>
          </div>
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
        <p style={{ color: 'var(--color-text-disabled)', marginTop: 0 }}>{form.description}</p>
      )}
      {/* US-105: fieldset[disabled] 一次性禁用内部所有表单控件(含各类型 input 与按钮) */}
      <fieldset
        disabled={readOnly}
        style={{ border: 'none', padding: 0, margin: 0, minWidth: 0 }}
      >
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(24, 1fr)',
            gap: 12,
          }}
        >
          {form.layout.map(renderField)}
        </div>
      </fieldset>
      {/* US-105: 只读预览无需提交 */}
      {!readOnly && (
        <button
          type="submit"
          disabled={submitting}
          style={{
            marginTop: 16,
            padding: '10px 24px',
            background: submitting ? 'var(--color-text-muted)' : 'var(--color-bg-secondary)',
            color: 'var(--color-text-primary)',
            border: 'none',
            borderRadius: 4,
            cursor: submitting ? 'not-allowed' : 'pointer',
          }}
        >
          {submitting ? '提交中…' : submitLabel}
        </button>
      )}
      {/* US-106: 提交后动作提示(stay / workflow) */}
      {submitMessage && (
        <div
          role="status"
          style={{
            marginTop: 12,
            padding: '8px 12px',
            background: 'rgba(16,185,129,0.1)',
            color: 'var(--color-success)',
            borderRadius: 4,
            fontSize: 13,
          }}
        >
          {submitMessage}
        </div>
      )}
    </form>
  );
}

const inputStyle: React.CSSProperties = {
  padding: 8,
  fontSize: 14,
  border: '1px solid var(--color-border-medium)',
  borderRadius: 4,
};