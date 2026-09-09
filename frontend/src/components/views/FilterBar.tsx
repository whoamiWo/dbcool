import { useState } from 'react';
import type { FieldDef, FilterRule, SortRule } from '@/types/view';

/**
 * 筛选条(US-202) — Week 9 MVP:简化版,客户端筛选。
 */
export function FilterBar({
  fields,
  filters,
  onChange,
}: {
  fields: FieldDef[];
  filters: FilterRule[];
  onChange: (filters: FilterRule[]) => void;
}) {
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState<FilterRule>({ field: fields[0]?.name ?? '', op: 'eq', value: '' });

  const add = () => {
    if (!draft.field) return;
    onChange([...filters, draft]);
    setDraft({ field: fields[0]?.name ?? '', op: 'eq', value: '' });
    setAdding(false);
  };

  const remove = (idx: number) => {
    onChange(filters.filter((_, i) => i !== idx));
  };

  return (
    <div
      style={{
        padding: 12,
        background: '#f1f5f9',
        borderRadius: 4,
        marginBottom: 12,
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        flexWrap: 'wrap',
      }}
    >
      <strong style={{ fontSize: 13 }}>筛选:</strong>
      {filters.map((f, i) => {
        const field = fields.find((x) => x.name === f.field);
        return (
          <span
            key={i}
            style={{
              padding: '4px 8px',
              background: 'white',
              border: '1px solid #cbd5e1',
              borderRadius: 4,
              fontSize: 12,
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
            }}
          >
            <strong>{field?.label ?? f.field}</strong>
            <span style={{ color: '#64748b' }}>{opLabel(f.op)}</span>
            {f.op !== 'empty' && f.op !== 'notEmpty' && (
              <span style={{ color: '#1e293b' }}>{String(f.value ?? '')}</span>
            )}
            <button
              onClick={() => remove(i)}
              style={{
                background: 'transparent',
                border: 'none',
                cursor: 'pointer',
                color: '#dc2626',
                marginLeft: 4,
              }}
            >
              ×
            </button>
          </span>
        );
      })}

      {adding ? (
        <div style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
          <select
            value={draft.field}
            onChange={(e) => setDraft({ ...draft, field: e.target.value })}
            style={{ padding: 4, fontSize: 12 }}
          >
            {fields.map((f) => (
              <option key={f.name} value={f.name}>
                {f.label ?? f.name}
              </option>
            ))}
          </select>
          <select
            value={draft.op}
            onChange={(e) =>
              setDraft({ ...draft, op: e.target.value as FilterRule['op'] })
            }
            style={{ padding: 4, fontSize: 12 }}
          >
            <option value="eq">等于</option>
            <option value="neq">不等于</option>
            <option value="contains">包含</option>
            <option value="gt">大于</option>
            <option value="lt">小于</option>
            <option value="empty">为空</option>
            <option value="notEmpty">不为空</option>
          </select>
          {draft.op !== 'empty' && draft.op !== 'notEmpty' && (
            <input
              value={String(draft.value ?? '')}
              onChange={(e) => setDraft({ ...draft, value: e.target.value })}
              style={{ padding: 4, fontSize: 12, width: 100 }}
              placeholder="值"
            />
          )}
          <button
            onClick={add}
            style={{ padding: '4px 8px', fontSize: 12, background: '#1e293b', color: 'white', border: 'none', borderRadius: 4 }}
          >
            确定
          </button>
          <button
            onClick={() => setAdding(false)}
            style={{ padding: '4px 8px', fontSize: 12, background: 'transparent', border: 'none' }}
          >
            取消
          </button>
        </div>
      ) : (
        <button
          onClick={() => setAdding(true)}
          style={{
            padding: '4px 8px',
            fontSize: 12,
            background: 'white',
            border: '1px dashed #94a3b8',
            borderRadius: 4,
            cursor: 'pointer',
          }}
        >
          + 加筛选
        </button>
      )}
    </div>
  );
}

function opLabel(op: FilterRule['op']): string {
  return {
    eq: '=',
    neq: '≠',
    contains: '包含',
    gt: '>',
    lt: '<',
    empty: '为空',
    notEmpty: '不为空',
  }[op];
}

/** 客户端筛选 */
export function applyFilters<T extends Record<string, unknown>>(
  rows: T[],
  filters: FilterRule[]
): T[] {
  if (filters.length === 0) return rows;
  return rows.filter((row) => filters.every((f) => matchFilter(row[f.field], f)));
}

function matchFilter(value: unknown, rule: FilterRule): boolean {
  switch (rule.op) {
    case 'eq': return value === rule.value;
    case 'neq': return value !== rule.value;
    case 'contains': return String(value ?? '').includes(String(rule.value ?? ''));
    case 'gt': return Number(value) > Number(rule.value);
    case 'lt': return Number(value) < Number(rule.value);
    case 'empty': return value == null || value === '';
    case 'notEmpty': return value != null && value !== '';
    default: return true;
  }
}

/** 客户端排序 */
export function applySort<T extends Record<string, unknown>>(
  rows: T[],
  sort?: SortRule[]
): T[] {
  if (!sort || sort.length === 0) return rows;
  const sorted = [...rows];
  sorted.sort((a, b) => {
    for (const rule of sort) {
      const av = a[rule.field];
      const bv = b[rule.field];
      let cmp = 0;
      if (typeof av === 'number' && typeof bv === 'number') cmp = av - bv;
      else cmp = String(av ?? '').localeCompare(String(bv ?? ''));
      if (cmp !== 0) return rule.direction === 'asc' ? cmp : -cmp;
    }
    return 0;
  });
  return sorted;
}
