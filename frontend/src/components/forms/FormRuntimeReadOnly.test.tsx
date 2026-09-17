import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FormRuntime } from './FormRuntime';
import type { FieldDef, FormFull, FormRules } from '@/types/form';

/**
 * US-105:只读预览模式测试。
 *
 * 验证三件事(缺一即"假完成"):
 * 1. 所有输入被禁用(不可编辑)
 * 2. 被显隐规则隐藏的字段在只读模式下仍显示(便于检查完整布局)
 * 3. 不渲染提交按钮
 */
const fields: FieldDef[] = [
  { name: 'name', label: '姓名', type: 'text', required: false },
  { name: 'vip', label: '是否VIP', type: 'text', required: false },
];

const formWith = (rules: FormRules = {}): FormFull => ({
  id: 'f1',
  title: '报名表单',
  description: '',
  collection_name: 'signup',
  tenant_id: 't1',
  layout_json: '[]',
  rules_json: JSON.stringify(rules),
  created_at: '2026-01-01T00:00:00Z',
  updated_at: null,
  layout: [{ field: 'name' }, { field: 'vip' }],
  rules,
});

const renderForm = (readOnly: boolean, rules: FormRules = {}) =>
  render(
    <FormRuntime
      form={formWith(rules)}
      fields={fields}
      onSubmit={vi.fn()}
      readOnly={readOnly}
    />,
  );

describe('US-105 只读预览模式', () => {
  it('readOnly 时所有输入被禁用', () => {
    renderForm(true);
    expect(screen.getByPlaceholderText('姓名')).toBeDisabled();
    expect(screen.getByPlaceholderText('是否VIP')).toBeDisabled();
  });

  it('非 readOnly 时输入可编辑', () => {
    renderForm(false);
    expect(screen.getByPlaceholderText('姓名')).not.toBeDisabled();
  });

  it('readOnly 时不渲染提交按钮', () => {
    renderForm(true);
    expect(screen.queryByRole('button', { name: '提交' })).not.toBeInTheDocument();
  });

  it('非 readOnly 时渲染提交按钮', () => {
    renderForm(false);
    expect(screen.getByRole('button', { name: '提交' })).toBeInTheDocument();
  });

  it('readOnly 时忽略显隐规则,显示完整布局', () => {
    // name 字段受显隐规则控制:vip='yes' 才显示
    const rules: FormRules = {
      visibility: { name: { when: 'vip', op: 'eq', value: 'yes' } },
    };
    // 交互模式:vip 为空 → name 隐藏
    renderForm(false, rules);
    expect(screen.queryByPlaceholderText('姓名')).not.toBeInTheDocument();

    // 只读模式:忽略规则 → name 显示(便于检查完整布局)
    renderForm(true, rules);
    expect(screen.getByPlaceholderText('姓名')).toBeInTheDocument();
  });

  it('readOnly 时仍渲染 label 与帮助文本', () => {
    const withHelp = fields.map((f) =>
      f.name === 'name' ? { ...f, options: { helpText: '请填写真实姓名' } } : f,
    );
    render(
      <FormRuntime
        form={formWith()}
        fields={withHelp}
        onSubmit={vi.fn()}
        readOnly
      />,
    );
    expect(screen.getByText('请填写真实姓名')).toBeInTheDocument();
  });

  // 注:不使用 fireEvent.change 验证"值不变" —— jsdom 会强制写入 DOM value 绕过 disabled,
  // 该断言测的是 jsdom 行为而非组件行为。"输入被禁用"已由首个用例的 toBeDisabled() 覆盖。
});
