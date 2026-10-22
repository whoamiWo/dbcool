/**
 * PHASE 4.2 覆盖率补测：components/Screen.tsx
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Screen } from './Screen';
import { useIsMobile } from '../hooks/useIsMobile';

vi.mock('../hooks/useIsMobile');

describe('Screen', () => {
  const mockChildren = <div data-testid="child">Child Content</div>;

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('桌面端直接渲染 children', () => {
    (useIsMobile as ReturnType<typeof vi.fn>).mockReturnValue(false);
    render(<Screen>{mockChildren}</Screen>);
    expect(screen.getByTestId('child')).toBeInTheDocument();
    expect(screen.queryByText(/桌面端/i)).not.toBeInTheDocument();
  });

  it('移动端首次显示警告横幅', () => {
    (useIsMobile as ReturnType<typeof vi.fn>).mockReturnValue(true);
    localStorage.setItem('desktop-warning-dismissed', '0');
    render(<Screen>{mockChildren}</Screen>);
    expect(screen.getByText(/桌面端/i)).toBeInTheDocument();
    expect(screen.getByTestId('child')).toBeInTheDocument();
  });

  it('点击关闭按钮后隐藏横幅并持久化', () => {
    (useIsMobile as ReturnType<typeof vi.fn>).mockReturnValue(true);
    localStorage.setItem('desktop-warning-dismissed', '0');
    render(<Screen>{mockChildren}</Screen>);
    const btn = screen.getByRole('button', { name: /知道了/i });
    fireEvent.click(btn);
    expect(localStorage.getItem('desktop-warning-dismissed')).toBe('1');
    expect(screen.queryByText(/桌面端/i)).not.toBeInTheDocument();
  });

  it('localStorage 已标记则不显示横幅', () => {
    (useIsMobile as ReturnType<typeof vi.fn>).mockReturnValue(true);
    localStorage.setItem('desktop-warning-dismissed', '1');
    render(<Screen>{mockChildren}</Screen>);
    expect(screen.queryByText(/桌面端/i)).not.toBeInTheDocument();
  });
});
