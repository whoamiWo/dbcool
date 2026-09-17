import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FieldRulesEditor } from './FieldRulesEditor';
import { FormRuntime } from './FormRuntime';
import type { FieldDef, FormFull, FormRules } from '@/types/form';

/**
 * US-103(显隐规则) + US-104(校验规则)测试。
 *
 * 分两层验证,避免"UI 能配但运行时不执行"的假完成:
 * 1. 编辑器:配置变更正确写入 FormRules 数据结构
 * 2. 集成:编辑器产出的规则被 FormRuntime 真实执行
 */

const fields: FieldDef[] = [
  { name: 'name', label: '姓名', type: 'text', required: false },
  { name: 'vip', label: '是否VIP', type: 'text', required: false },
];

const baseRules: FormRules = {};

const renderEditor = (rules: FormRules, onChange = vi.fn()) => {
  render(
    <FieldRulesEditor
      fieldName="name"
      allFields={fields}
      rules={rules}
      onChange={onChange}
    />,
  );
  return onChange;
};

describe('FieldRulesEditor — US-104 校验规则', () => {
  it('输入最小长度写入 validation 规则', () => {
    const onChange = renderEditor(baseRules);
    fireEvent.change(screen.getByLabelText('最小长度'), { target: { value: '3' } });
    expect(onChange).toHaveBeenCalled();
    const next = onChange.mock.calls[0][0] as FormRules;
    expect(next.validation?.name).toEqual([{ type: 'minLength', value: 3 }]);
  });

  it('清空输入移除对应规则', () => {
    const rules: FormRules = { validation: { name: [{ type: 'minLength', value: 3 }] } };
    const onChange = renderEditor(rules);
    fireEvent.change(screen.getByLabelText('最小长度'), { target: { value: '' } });
    const next = onChange.mock.calls[0][0] as FormRules;
    expect(next.validation?.name ?? []).toHaveLength(0);
  });

  it('勾选邮箱格式添加 email 规则', () => {
    const onChange = renderEditor(baseRules);
    fireEvent.click(screen.getByLabelText('邮箱格式'));
    const next = onChange.mock.calls[0][0] as FormRules;
    expect(next.validation?.name?.some((v) => v.type === 'email')).toBe(true);
  });

  it('正则规则写入字符串 value', () => {
    const onChange = renderEditor(baseRules);
    fireEvent.change(screen.getByLabelText('正则表达式'), { target: { value: '^\\d{4}$' } });
    const next = onChange.mock.calls[0][0] as FormRules;
    expect(next.validation?.name).toEqual([{ type: 'pattern', value: '^\\d{4}$' }]);
  });
});

describe('FieldRulesEditor — US-103 显隐规则', () => {
  it('选择触发字段写入 visibility 规则', () => {
    const onChange = renderEditor(baseRules);
    fireEvent.change(screen.getByLabelText('触发字段'), { target: { value: 'vip' } });
    const next = onChange.mock.calls[0][0] as FormRules;
    expect(next.visibility?.name).toMatchObject({ when: 'vip', op: 'eq' });
  });

  it('可切换比较符', () => {
    const rules: FormRules = { visibility: { name: { when: 'vip', op: 'eq', value: 'yes' } } };
    const onChange = renderEditor(rules);
    fireEvent.change(screen.getByLabelText('比较符'), { target: { value: 'neq' } });
    const next = onChange.mock.calls[0][0] as FormRules;
    expect(next.visibility?.name?.op).toBe('neq');
  });

  it('empty 操作符不渲染比较值输入', () => {
    const rules: FormRules = { visibility: { name: { when: 'vip', op: 'empty' } } };
    renderEditor(rules);
    expect(screen.queryByLabelText('比较值')).not.toBeInTheDocument();
  });

  it('清除按钮移除显隐规则', () => {
    const rules: FormRules = { visibility: { name: { when: 'vip', op: 'eq', value: 'yes' } } };
    const onChange = renderEditor(rules);
    fireEvent.click(screen.getByText('清除显隐规则'));
    const next = onChange.mock.calls[0][0] as FormRules;
    expect(next.visibility?.name).toBeUndefined();
  });
});

/**
 * 集成验证:编辑器产出的数据结构必须被 FormRuntime 真实执行。
 * (FormRuntime 读 rules.validation[name] / rules.visibility[name])
 */
const formWith = (rules: FormRules): FormFull => ({
  id: 'f1',
  title: '订单表单',
  description: '',
  collection_name: 'orders',
  tenant_id: 't1',
  layout_json: '[]',
  rules_json: JSON.stringify(rules),
  created_at: '2026-01-01T00:00:00Z',
  updated_at: null,
  layout: [{ field: 'name' }, { field: 'vip' }],
  rules,
});

describe('US-103/104 集成 — 规则在 FormRuntime 真实生效', () => {
  it('minLength 校验:输入过短时报错', async () => {
    const onSubmit = vi.fn();
    render(
      <FormRuntime
        form={formWith({ validation: { name: [{ type: 'minLength', value: 5 }] } })}
        fields={fields}
        onSubmit={onSubmit}
      />,
    );
    fireEvent.change(screen.getByPlaceholderText('姓名'), { target: { value: 'ab' } });
    fireEvent.click(screen.getByRole('button', { name: '提交' }));
    expect(await screen.findByText(/至少 5 个字符/)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('pattern 校验:不匹配正则时报错', async () => {
    const onSubmit = vi.fn();
    render(
      <FormRuntime
        form={formWith({ validation: { name: [{ type: 'pattern', value: '^\\d{4}$' }] } })}
        fields={fields}
        onSubmit={onSubmit}
      />,
    );
    fireEvent.change(screen.getByPlaceholderText('姓名'), { target: { value: 'abc' } });
    fireEvent.click(screen.getByRole('button', { name: '提交' }));
    expect(await screen.findByText('格式不正确')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('显隐规则:条件不满足时字段隐藏,满足时出现', () => {
    const rules: FormRules = {
      visibility: { name: { when: 'vip', op: 'eq', value: 'yes' } },
    };
    render(
      <FormRuntime form={formWith(rules)} fields={fields} onSubmit={vi.fn()} />,
    );
    // vip 为空 → 姓名应隐藏
    expect(screen.queryByPlaceholderText('姓名')).not.toBeInTheDocument();
    // 填入 yes → 姓名出现
    fireEvent.change(screen.getByPlaceholderText('是否VIP'), { target: { value: 'yes' } });
    expect(screen.getByPlaceholderText('姓名')).toBeInTheDocument();
  });
});
