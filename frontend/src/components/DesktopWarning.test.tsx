import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DesktopWarning } from './DesktopWarning';

describe('DesktopWarning — PHASE98 R5 移动端横幅', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('传入 onDismiss 时渲染"知道了"关闭按钮（此前因未传参而永远不渲染）', () => {
    const onDismiss = vi.fn();
    render(<DesktopWarning onDismiss={onDismiss} />);

    const btn = screen.getByRole('button', { name: '知道了' });
    fireEvent.click(btn);
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('未传 onDismiss 时不渲染关闭按钮（保持向后兼容）', () => {
    render(<DesktopWarning />);
    expect(screen.queryByRole('button', { name: '知道了' })).toBeNull();
  });

  /**
   * 关键回归：横幅不得脱离文档流遮挡内容。
   * 原实现是 `position:'fixed'`，会在不占据空间的情况下盖住页面顶部；
   * 改为 sticky 后进入文档流，内容自然下推。
   */
  it('横幅使用 sticky 定位而非 fixed（避免无补偿地遮挡首屏）', () => {
    render(<DesktopWarning onDismiss={() => {}} />);
    const region = screen.getByRole('region', { name: '移动端访问提示' });
    expect(getComputedStyle(region).position).toBe('sticky');
  });
});
