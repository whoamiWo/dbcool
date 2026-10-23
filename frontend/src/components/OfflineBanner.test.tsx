/**
 * PHASE 4.2 覆盖率补测：components/OfflineBanner.tsx
 * 覆盖：在线时不渲染、离线时渲染提示、online/offline 事件切换、卸载清理。
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { OfflineBanner } from './OfflineBanner';

function setNavigatorOnline(value: boolean) {
  Object.defineProperty(window.navigator, 'onLine', { value, configurable: true });
}

afterEach(() => {
  setNavigatorOnline(true);
});

describe('OfflineBanner', () => {
  it('在线时不渲染任何内容', () => {
    setNavigatorOnline(true);
    const { container } = render(<OfflineBanner />);
    expect(container).toBeEmptyDOMElement();
  });

  it('离线时渲染离线提示', () => {
    setNavigatorOnline(false);
    render(<OfflineBanner />);
    expect(screen.getByText(/离线状态/)).toBeInTheDocument();
  });

  it('收到 offline 事件后显示提示', () => {
    setNavigatorOnline(true);
    render(<OfflineBanner />);
    expect(screen.queryByText(/离线状态/)).not.toBeInTheDocument();

    act(() => {
      setNavigatorOnline(false);
      window.dispatchEvent(new Event('offline'));
    });
    expect(screen.getByText(/离线状态/)).toBeInTheDocument();
  });

  it('收到 online 事件后隐藏提示', () => {
    setNavigatorOnline(false);
    const { container } = render(<OfflineBanner />);
    expect(screen.getByText(/离线状态/)).toBeInTheDocument();

    act(() => {
      setNavigatorOnline(true);
      window.dispatchEvent(new Event('online'));
    });
    expect(container).toBeEmptyDOMElement();
  });

  it('卸载后移除事件监听（不报错）', () => {
    setNavigatorOnline(true);
    const { unmount } = render(<OfflineBanner />);
    expect(() => {
      unmount();
      window.dispatchEvent(new Event('offline'));
    }).not.toThrow();
  });
});
