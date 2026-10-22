/**
 * RollupEditor 聚合字段配置器测试(PHASE133 核心模块补测).
 *
 * 覆盖:
 * - 关联字段下拉渲染 relationFields(label 回退 name)
 * - 目标字段禁用且显示占位
 * - 聚合方式默认 SUM 与切换
 * - 保存按钮在关联/目标字段缺失时禁用
 * - 回显 initialValue
 * - 取消回调 onClose
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { RollupEditor } from './RollupEditor';

const relationFields = [
  { name: 'orders', label: '订单', type: 'relation' },
  { name: 'items', type: 'relation' },
];

const setup = (props: Partial<React.ComponentProps<typeof RollupEditor>> = {}) => {
  const onClose = vi.fn();
  const onSave = vi.fn();
  render(
    <RollupEditor
      open
      onClose={onClose}
      onSave={onSave}
      relationFields={relationFields}
      {...props}
    />,
  );
  return { onClose, onSave };
};

describe('RollupEditor', () => {
  beforeEach(() => vi.clearAllMocks());

  it('渲染标题与聚合方式说明', () => {
    setup();
    expect(screen.getByText('编辑聚合字段')).toBeInTheDocument();
    expect(screen.getByText(/SUM\/COUNT\/AVG\/MIN\/MAX/)).toBeInTheDocument();
  });

  it('关联字段下拉包含 label 回退 name 与类型', () => {
    setup();
    expect(screen.getByRole('option', { name: '订单 (relation)' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'items (relation)' })).toBeInTheDocument();
  });

  it('目标字段禁用且显示占位', () => {
    setup();
    expect(screen.getByLabelText('目标字段')).toBeDisabled();
    expect(screen.getByRole('option', { name: '暂无可用字段' })).toBeInTheDocument();
  });

  it('聚合方式默认为 SUM', () => {
    setup();
    expect(screen.getByLabelText('聚合方式')).toHaveValue('SUM');
  });

  it('初始状态下保存按钮禁用(缺少关联/目标字段)', () => {
    setup();
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled();
  });

  it('回显 initialValue 并允许保存', () => {
    const { onSave } = setup({
      initialValue: { relationField: 'orders', targetField: 'amount', agg: 'AVG' },
    });
    expect(screen.getByLabelText('关联字段')).toHaveValue('orders');
    expect(screen.getByLabelText('聚合方式')).toHaveValue('AVG');
    const save = screen.getByRole('button', { name: '保存' });
    expect(save).toBeEnabled();
    fireEvent.click(save);
    expect(onSave).toHaveBeenCalledWith({
      relationField: 'orders',
      targetField: 'amount',
      agg: 'AVG',
    });
  });

  it('切换聚合方式后保存携带新值', () => {
    const { onSave } = setup({
      initialValue: { relationField: 'orders', targetField: 'amount' },
    });
    fireEvent.change(screen.getByLabelText('聚合方式'), { target: { value: 'COUNT' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    expect(onSave).toHaveBeenCalledWith({
      relationField: 'orders',
      targetField: 'amount',
      agg: 'COUNT',
    });
  });

  it('点击取消触发 onClose', () => {
    const { onClose, onSave } = setup();
    fireEvent.click(screen.getByRole('button', { name: '取消' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onSave).not.toHaveBeenCalled();
  });
});
