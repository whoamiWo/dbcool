import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { FormRuntime } from './FormRuntime';
import type { FieldDef, FormFull, FormRules } from '@/types/form';

/**
 * US-106:提交后动作(rules.submit)测试。
 *
 * 验证三种动作在 FormRuntime 中被真实执行,而非仅存在于数据结构:
 * - stay:显示提示文案
 * - workflow:触发 onTriggerWorkflow 回调并携带提交数据
 * - redirect:跳转 URL
 */
const fields: FieldDef[] = [
  { name: 'name', label: '姓名', type: 'text', required: false },
];

const formWith = (rules: FormRules): FormFull => ({
  id: 'f1',
  title: '报名表单',
  description: '',
  collection_name: 'signup',
  tenant_id: 't1',
  layout_json: '[]',
  rules_json: JSON.stringify(rules),
  created_at: '2026-01-01T00:00:00Z',
  updated_at: null,
  layout: [{ field: 'name' }],
  rules,
});

const fillAndSubmit = async (value = '张三') => {
  fireEvent.change(screen.getByPlaceholderText('姓名'), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: '提交' }));
};

describe('US-106 提交后动作', () => {
  beforeEach(() => vi.clearAllMocks());

  it('stay:显示配置的提示文案', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(
      <FormRuntime
        form={formWith({ submit: { action: 'stay', message: '报名成功,请等待审核' } })}
        fields={fields}
        onSubmit={onSubmit}
      />,
    );

    await fillAndSubmit();

    expect(await screen.findByRole('status')).toHaveTextContent('报名成功,请等待审核');
    expect(onSubmit).toHaveBeenCalledWith({ name: '张三' });
  });

  it('stay 无 message 时回退默认提示', async () => {
    render(
      <FormRuntime
        form={formWith({ submit: { action: 'stay' } })}
        fields={fields}
        onSubmit={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    await fillAndSubmit();

    expect(await screen.findByRole('status')).toHaveTextContent('提交成功');
  });

  it('workflow:触发回调并携带提交数据', async () => {
    const onTriggerWorkflow = vi.fn().mockResolvedValue(undefined);
    render(
      <FormRuntime
        form={formWith({ submit: { action: 'workflow', workflowId: 'wf-1', message: '已提交审批' } })}
        fields={fields}
        onSubmit={vi.fn().mockResolvedValue(undefined)}
        onTriggerWorkflow={onTriggerWorkflow}
      />,
    );

    await fillAndSubmit();

    await waitFor(() => {
      expect(onTriggerWorkflow).toHaveBeenCalledWith('wf-1', { name: '张三' });
    });
    expect(await screen.findByRole('status')).toHaveTextContent('已提交审批');
  });

  it('redirect:跳转到配置的 URL', async () => {
    // jsdom 中 window.location.href 赋值会抛"Not implemented",故用 defineProperty 拦截
    const hrefSetter = vi.fn();
    const original = window.location;
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...original, set href(v: string) { hrefSetter(v); }, get href() { return original.href; } },
    });

    render(
      <FormRuntime
        form={formWith({ submit: { action: 'redirect', url: '/thanks' } })}
        fields={fields}
        onSubmit={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    await fillAndSubmit();

    await waitFor(() => {
      expect(hrefSetter).toHaveBeenCalledWith('/thanks');
    });

    Object.defineProperty(window, 'location', { configurable: true, value: original });
  });

  it('未配置 submit 规则时不显示提示', async () => {
    render(
      <FormRuntime
        form={formWith({})}
        fields={fields}
        onSubmit={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    await fillAndSubmit();

    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
  });

  it('校验失败时不执行提交动作', async () => {
    const onTriggerWorkflow = vi.fn();
    render(
      <FormRuntime
        form={formWith({
          submit: { action: 'workflow', workflowId: 'wf-1' },
          validation: { name: [{ type: 'required' }] },
        })}
        fields={[{ ...fields[0], required: true }]}
        onSubmit={vi.fn()}
        onTriggerWorkflow={onTriggerWorkflow}
      />,
    );

    // 不填直接提交 → required 校验失败
    fireEvent.click(screen.getByRole('button', { name: '提交' }));

    expect(await screen.findByText(/必填/)).toBeInTheDocument();
    expect(onTriggerWorkflow).not.toHaveBeenCalled();
  });
});
