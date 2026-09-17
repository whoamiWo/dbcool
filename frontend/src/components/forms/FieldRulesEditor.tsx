import type {
  FieldDef,
  FormRules,
  ValidationRule,
  ValidationType,
  VisibilityOperator,
  VisibilityRule,
} from '@/types/form';

/**
 * 字段规则编辑器 — US-103(显隐规则) + US-104(校验规则)。
 *
 * <p>数据结构与 FormRuntime 运行时契约严格一致:
 * <ul>
 *   <li>validation: rules.validation[field] = ValidationRule[](每项 {type,value?,message?})</li>
 *   <li>visibility: rules.visibility[field] = 单个 VisibilityRule {when,op,value}</li>
 * </ul>
 * 只负责编辑,执行由 FormRuntime 完成。
 */
interface FieldRulesEditorProps {
  fieldName: string;
  /** 同表单其他字段,供显隐规则选择触发字段 */
  allFields: FieldDef[];
  rules: FormRules;
  onChange: (next: FormRules) => void;
}

const VALIDATION_TYPES: { type: ValidationType; label: string; input: 'number' | 'text' | 'none' }[] = [
  { type: 'minLength', label: '最小长度', input: 'number' },
  { type: 'maxLength', label: '最大长度', input: 'number' },
  { type: 'min', label: '最小值', input: 'number' },
  { type: 'max', label: '最大值', input: 'number' },
  { type: 'pattern', label: '正则表达式', input: 'text' },
  { type: 'email', label: '邮箱格式', input: 'none' },
];

const OP_LABELS: Record<VisibilityOperator, string> = {
  eq: '等于',
  neq: '不等于',
  in: '属于',
  notIn: '不属于',
  empty: '为空',
  notEmpty: '不为空',
  gt: '大于',
  lt: '小于',
};

/** empty / notEmpty 不需要填写比较值。 */
const VALUELESS_OPS: VisibilityOperator[] = ['empty', 'notEmpty'];

const inputStyle: React.CSSProperties = {
  padding: 4,
  fontSize: 12,
  border: '1px solid #cbd5e1',
  borderRadius: 4,
  width: '100%',
};

export function FieldRulesEditor({ fieldName, allFields, rules, onChange }: FieldRulesEditorProps) {
  const list: ValidationRule[] = rules.validation?.[fieldName] ?? [];
  const vis = rules.visibility?.[fieldName];

  const getValue = (type: ValidationType) => list.find((v) => v.type === type)?.value;
  const has = (type: ValidationType) => list.some((v) => v.type === type);

  /**
   * 归一化:空串与 NaN(number 输入清空时 valueAsNumber 返回 NaN)都视为"无值"。
   * 否则清空输入会写入 value: NaN 的脏规则。
   */
  const norm = (v?: string | number): string | number | undefined => {
    if (v === undefined || v === '') return undefined;
    if (typeof v === 'number' && Number.isNaN(v)) return undefined;
    return v;
  };

  /**
   * 设置某校验规则。
   * - 有输入项(number/text):依靠 value,空值即移除
   * - 无输入项(email):由 enabled 显式控制增删(该类规则无 value)
   */
  const setValidation = (type: ValidationType, raw?: string | number, enabled?: boolean) => {
    const value = norm(raw);
    const filtered = list.filter((v) => v.type !== type);
    const shouldAdd = enabled !== undefined ? enabled : value !== undefined;
    const next: ValidationRule[] = shouldAdd
      ? [...filtered, value !== undefined ? { type, value } : { type }]
      : filtered;
    onChange({ ...rules, validation: { ...rules.validation, [fieldName]: next } });
  };

  /** 设置显隐规则;undefined 表示清除。 */
  const setVisibility = (next?: VisibilityRule) => {
    const v = { ...(rules.visibility ?? {}) };
    if (!next) delete v[fieldName];
    else v[fieldName] = next;
    onChange({ ...rules, visibility: v });
  };

  const others = allFields.filter((f) => f.name !== fieldName);
  const needsValue = vis ? !VALUELESS_OPS.includes(vis.op) : false;

  return (
    <div style={{ marginTop: 12 }}>
      {/* ===== US-104: 校验规则 ===== */}
      <div style={{ borderTop: '1px solid #e2e8f0', paddingTop: 8 }}>
        <strong style={{ fontSize: 12 }}>校验规则</strong>
        {VALIDATION_TYPES.map(({ type, label, input }) => (
          <div
            key={type}
            style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}
          >
            <span style={{ fontSize: 11, width: 68, color: '#64748b', flexShrink: 0 }}>{label}</span>
            {input === 'none' ? (
              <label style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11 }}>
                <input
                  type="checkbox"
                  checked={has(type)}
                  onChange={(e) => setValidation(type, undefined, e.target.checked)}
                  aria-label={label}
                />
                启用
              </label>
            ) : (
              <input
                type={input === 'number' ? 'number' : 'text'}
                value={getValue(type) === undefined ? '' : String(getValue(type))}
                onChange={(e) =>
                  setValidation(
                    type,
                    input === 'number' ? e.target.valueAsNumber : e.target.value,
                  )
                }
                placeholder={input === 'number' ? '数值' : '如 ^\\d{4}$'}
                style={inputStyle}
                aria-label={label}
              />
            )}
          </div>
        ))}
      </div>

      {/* ===== US-103: 显隐规则 ===== */}
      <div style={{ borderTop: '1px solid #e2e8f0', paddingTop: 8, marginTop: 8 }}>
        <strong style={{ fontSize: 12 }}>显隐规则</strong>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}>
          <span style={{ fontSize: 11, width: 68, color: '#64748b', flexShrink: 0 }}>当字段</span>
          <select
            value={vis?.when ?? ''}
            onChange={(e) => {
              const when = e.target.value;
              if (!when) return setVisibility(undefined);
              setVisibility({ when, op: vis?.op ?? 'eq', value: vis?.value });
            }}
            style={inputStyle}
            aria-label="触发字段"
          >
            <option value="">(不限制显示)</option>
            {others.map((f) => (
              <option key={f.name} value={f.name}>
                {f.label ?? f.name}
              </option>
            ))}
          </select>
        </div>

        {vis && (
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}>
              <span style={{ fontSize: 11, width: 68, color: '#64748b', flexShrink: 0 }}>条件</span>
              <select
                value={vis.op}
                onChange={(e) =>
                  setVisibility({ ...vis, op: e.target.value as VisibilityOperator })
                }
                style={inputStyle}
                aria-label="比较符"
              >
                {(Object.keys(OP_LABELS) as VisibilityOperator[]).map((op) => (
                  <option key={op} value={op}>
                    {OP_LABELS[op]}
                  </option>
                ))}
              </select>
            </div>

            {needsValue && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}>
                <span style={{ fontSize: 11, width: 68, color: '#64748b', flexShrink: 0 }}>比较值</span>
                <input
                  type="text"
                  value={vis.value === undefined ? '' : String(vis.value)}
                  onChange={(e) => setVisibility({ ...vis, value: e.target.value })}
                  style={inputStyle}
                  aria-label="比较值"
                />
              </div>
            )}

            <button
              type="button"
              onClick={() => setVisibility(undefined)}
              style={{
                marginTop: 6,
                padding: '3px 8px',
                fontSize: 11,
                background: '#fee2e2',
                color: '#991b1b',
                border: 'none',
                borderRadius: 4,
                cursor: 'pointer',
              }}
            >
              清除显隐规则
            </button>
          </>
        )}
      </div>
    </div>
  );
}
