/**
 * FormulaEditor 公式字段配置器测试(PHASE133 核心模块补测).
 *
 * 覆盖:
 * - 打开时回显 initialValue
 * - 输入表达式触发 onChange
 * - 取消回调 onClose
 * - 保存回调携带当前表达式
 * - 无 initialValue 时默认为空
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FormulaEditor } from './FormulaEditor';

const setup = (props: Partial<React.ComponentProps<typeof FormulaEditor>> = {}) => {
  const onClose = vi.fn();
  const onSave = vi.fn();
  render(<FormulaEditor open onClose={onClose} onSave={onSave} {...props} />);
  return { onClose, onSave };
};

describe('FormulaEditor', () => {
  beforeEach(() => vi.clearAllMocks());

  it('渲染标题与运算符说明', () => {
    setup();
    expect(screen.getByText('编辑公式')).toBeInTheDocument();
    expect(screen.getByText(/支持的运算符/)).toBeInTheDocument();
  });

  it('无 initialValue 时表达式默认为空', () => {
    setup();
    expect(screen.getByLabelText('表达式')).toHaveValue('');
  });

  it('回显 initialValue', () => {
    setup({ initialValue: 'price * qty' });
    expect(screen.getByLabelText('表达式')).toHaveValue('price * qty');
  });

  it('输入表达式后保存回调携带最新值', () => {
    const { onSave } = setup();
    fireEvent.change(screen.getByLabelText('表达式'), { target: { value: 'a + b' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    expect(onSave).toHaveBeenCalledWith('a + b');
  });

  it('点击取消触发 onClose', () => {
    const { onClose, onSave } = setup();
    fireEvent.click(screen.getByRole('button', { name: '取消' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onSave).not.toHaveBeenCalled();
  });

  it('open=false 时不渲染对话框内容', () => {
    setup({ open: false });
    expect(screen.queryByText('编辑公式')).not.toBeInTheDocument();
  });
});
