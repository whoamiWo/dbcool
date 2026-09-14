/**
 * FilterBar 筛选条测试(Week 39,Step B).
 *
 * 覆盖:
 * - UI 交互:展开/收起/加筛选/删除/取消
 * - applyFilters 7 种 op(eq/neq/contains/gt/lt/empty/notEmpty)
 * - applySort asc/desc
 * - sortToQuery 序列化
 * - filtersToQuery 序列化(含 empty/notEmpty 不带 value)
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import {
  FilterBar,
  applyFilters,
  applySort,
  sortToQuery,
  filtersToQuery,
} from './FilterBar';
import type { FieldDef } from '@/types/collection';
import type { FilterRule, SortRule } from '@/types/view';

const fields: FieldDef[] = [
  { name: 'name', label: '姓名', type: 'text' },
  { name: 'age', label: '年龄', type: 'number' },
  { name: 'email', label: '邮箱', type: 'text' },
];

const wrap = (
  filters: FilterRule[] = [],
  onChange = vi.fn(),
  fieldsArg: FieldDef[] = fields,
) => render(<FilterBar fields={fieldsArg} filters={filters} onChange={onChange} />);

describe('FilterBar — UI', () => {
  beforeEach(() => vi.clearAllMocks());

  it('默认显示 + 加筛选 按钮', () => {
    wrap();
    expect(screen.getByRole('button', { name: /\+ 加筛选/ })).toBeInTheDocument();
  });

  it('渲染现有筛选:字段 label + op label + value', () => {
    wrap([{ field: 'name', op: 'eq', value: 'alice' }]);
    expect(screen.getByText('姓名')).toBeInTheDocument();
    expect(screen.getByText('=')).toBeInTheDocument();
    expect(screen.getByText('alice')).toBeInTheDocument();
  });

  it('empty/notEmpty op 不显示 value', () => {
    wrap([{ field: 'name', op: 'empty', value: 'xxx' }]);
    expect(screen.getByText('姓名')).toBeInTheDocument();
    expect(screen.getByText('为空')).toBeInTheDocument();
    // 之前有人传 value 'xxx' 也应被隐藏
    expect(screen.queryByText('xxx')).not.toBeInTheDocument();
  });

  it('点击 + 加筛选:展开编辑区', () => {
    wrap();
    fireEvent.click(screen.getByRole('button', { name: /\+ 加筛选/ }));
    expect(screen.getByRole('button', { name: /确定/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /取消/ })).toBeInTheDocument();
  });

  it('点击取消:收起编辑区', () => {
    wrap();
    fireEvent.click(screen.getByRole('button', { name: /\+ 加筛选/ }));
    fireEvent.click(screen.getByRole('button', { name: /取消/ }));
    expect(screen.queryByRole('button', { name: /确定/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /\+ 加筛选/ })).toBeInTheDocument();
  });

  it('确定添加:调用 onChange + 重置 draft', () => {
    const onChange = vi.fn();
    wrap([], onChange);
    fireEvent.click(screen.getByRole('button', { name: /\+ 加筛选/ }));

    // 输入 value — 第一个 input(没有 type attr,默认 text)
    const valueInput = document.querySelector('input') as HTMLInputElement;
    fireEvent.change(valueInput, { target: { value: 'alice' } });
    fireEvent.click(screen.getByRole('button', { name: /确定/ }));

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0]).toEqual([{ field: 'name', op: 'eq', value: 'alice' }]);
  });

  it('删除筛选:× 按钮触发 onChange(过滤掉)', () => {
    const onChange = vi.fn();
    wrap(
      [
        { field: 'name', op: 'eq', value: 'a' },
        { field: 'age', op: 'gt', value: '18' },
      ],
      onChange,
    );

    // 找到第一个 × 按钮并点击
    const removeButtons = screen.getAllByRole('button', { name: /^×$/ });
    fireEvent.click(removeButtons[0]);

    expect(onChange).toHaveBeenCalledWith([{ field: 'age', op: 'gt', value: '18' }]);
  });

  it('draft.field 为空时不允许添加', () => {
    const onChange = vi.fn();
    wrap([], onChange, []);  // 空 fields → draft.field = ''
    fireEvent.click(screen.getByRole('button', { name: /\+ 加筛选/ }));
    fireEvent.click(screen.getByRole('button', { name: /确定/ }));
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('applyFilters', () => {
  const rows = [
    { name: 'Alice', age: 30, email: 'alice@x.com' },
    { name: 'Bob', age: 25, email: '' },
    { name: 'Carol', age: 35, email: 'carol@x.com' },
  ];

  it('空筛选:返回全部', () => {
    expect(applyFilters(rows, [])).toEqual(rows);
  });

  it('eq 筛选', () => {
    expect(applyFilters(rows, [{ field: 'name', op: 'eq', value: 'Alice' }])).toEqual([rows[0]]);
  });

  it('neq 筛选', () => {
    expect(applyFilters(rows, [{ field: 'name', op: 'neq', value: 'Alice' }])).toEqual([rows[1], rows[2]]);
  });

  it('contains 筛选:子串匹配', () => {
    expect(applyFilters(rows, [{ field: 'email', op: 'contains', value: '@x.com' }])).toEqual([rows[0], rows[2]]);
  });

  it('gt 数值筛选', () => {
    expect(applyFilters(rows, [{ field: 'age', op: 'gt', value: '28' }])).toEqual([rows[0], rows[2]]);
  });

  it('lt 数值筛选', () => {
    expect(applyFilters(rows, [{ field: 'age', op: 'lt', value: '32' }])).toEqual([rows[0], rows[1]]);
  });

  it('empty:值为空字符串', () => {
    expect(applyFilters(rows, [{ field: 'email', op: 'empty', value: '' }])).toEqual([rows[1]]);
  });

  it('notEmpty:值非空', () => {
    expect(applyFilters(rows, [{ field: 'email', op: 'notEmpty', value: '' }])).toEqual([rows[0], rows[2]]);
  });

  it('多筛选:AND 关系', () => {
    expect(
      applyFilters(rows, [
        { field: 'age', op: 'gt', value: '20' },
        { field: 'email', op: 'notEmpty', value: '' },
      ]),
    ).toEqual([rows[0], rows[2]]);
  });
});

describe('applySort', () => {
  const rows = [
    { name: 'Carol', age: 35 },
    { name: 'Alice', age: 30 },
    { name: 'Bob', age: 25 },
  ];

  it('空 sort:原样返回', () => {
    expect(applySort(rows)).toEqual(rows);
    expect(applySort(rows, [])).toEqual(rows);
  });

  it('asc 字符串排序', () => {
    expect(applySort(rows, [{ field: 'name', direction: 'asc' }])).toEqual([rows[1], rows[2], rows[0]]);
  });

  it('desc 字符串排序', () => {
    expect(applySort(rows, [{ field: 'name', direction: 'desc' }])).toEqual([rows[0], rows[2], rows[1]]);
  });

  it('数值排序', () => {
    expect(applySort(rows, [{ field: 'age', direction: 'asc' }])).toEqual([rows[2], rows[1], rows[0]]);
  });

  it('多级排序:先 name 后 age', () => {
    const data = [
      { name: 'Alice', age: 30 },
      { name: 'Alice', age: 25 },
      { name: 'Bob', age: 10 },
    ];
    expect(applySort(data, [{ field: 'name', direction: 'asc' }, { field: 'age', direction: 'asc' }])).toEqual([
      { name: 'Alice', age: 25 },
      { name: 'Alice', age: 30 },
      { name: 'Bob', age: 10 },
    ]);
  });
});

describe('sortToQuery', () => {
  it('空 → 空字符串', () => {
    expect(sortToQuery()).toBe('');
    expect(sortToQuery([])).toBe('');
  });

  it('asc 单字段', () => {
    expect(sortToQuery([{ field: 'name', direction: 'asc' }])).toBe('name');
  });

  it('desc 单字段:加负号', () => {
    expect(sortToQuery([{ field: 'salary', direction: 'desc' }])).toBe('-salary');
  });

  it('多字段组合:name,-salary', () => {
    expect(
      sortToQuery([
        { field: 'name', direction: 'asc' },
        { field: 'salary', direction: 'desc' },
      ]),
    ).toBe('name,-salary');
  });

  it('过滤 field/direction 缺失的', () => {
    expect(
      sortToQuery([
        { field: '', direction: 'asc' },
        { field: 'name', direction: 'asc' },
      ]),
    ).toBe('name');
  });
});

describe('filtersToQuery', () => {
  it('空 → 空字符串', () => {
    expect(filtersToQuery()).toBe('');
    expect(filtersToQuery([])).toBe('');
  });

  it('单字段:含 value', () => {
    expect(filtersToQuery([{ field: 'name', op: 'eq', value: 'Alice' }])).toBe('name:eq:Alice');
  });

  it('多字段:name:contains:A,salary:gt:1000', () => {
    expect(
      filtersToQuery([
        { field: 'name', op: 'contains', value: 'A' },
        { field: 'salary', op: 'gt', value: '1000' },
      ]),
    ).toBe('name:contains:A,salary:gt:1000');
  });

  it('empty/notEmpty 不带 value', () => {
    expect(
      filtersToQuery([
        { field: 'email', op: 'notEmpty', value: '' },
      ]),
    ).toBe('email:notEmpty');
  });

  it('value 为 null/undefined 不带', () => {
    expect(
      filtersToQuery([
        { field: 'name', op: 'empty', value: null as any },
        { field: 'email', op: 'notEmpty', value: undefined as any },
      ]),
    ).toBe('name:empty,email:notEmpty');
  });
});
