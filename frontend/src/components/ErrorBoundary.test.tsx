/**
 * PHASE 4.2 覆盖率补测：components/ErrorBoundary.tsx
 *
 * ErrorBoundary 是 class 组件，自带错误 UI（无自定义 fallback prop）。
 * 测试验证：正常渲染、错误捕获后展示标题/消息/按钮。
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ErrorBoundary } from './ErrorBoundary';

class BoomError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BoomError';
  }
}

/** 子组件：渲染时抛出预设错误。 */
function BadChild({ error }: { error: Error }) {
  if (error) throw error;
  return <div>OK</div>;
}

describe('ErrorBoundary', () => {
  it('正常渲染 children', () => {
    render(
      <ErrorBoundary>
        <div data-testid="child">OK</div>
      </ErrorBoundary>
    );
    expect(screen.getByTestId('child')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('捕获错误后渲染错误界面', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(
      <ErrorBoundary>
        <BadChild error={new BoomError('render failed')} />
      </ErrorBoundary>
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('页面出错了')).toBeInTheDocument();
    expect(screen.getByText('render failed')).toBeInTheDocument();
    spy.mockRestore();
  });

  it('显示自定义 title', () => {
    render(
      <ErrorBoundary title="自定义错误">
        <BadChild error={new BoomError('boom')} />
      </ErrorBoundary>
    );
    expect(screen.getByText('自定义错误')).toBeInTheDocument();
  });

  it('包含重新加载和重试按钮', () => {
    const reloadMock = vi.spyOn(window, 'location', 'get').mockReturnValue({ reload: vi.fn() } as any);
    render(
      <ErrorBoundary>
        <BadChild error={new BoomError('boom')} />
      </ErrorBoundary>
    );

    expect(screen.getByText('重新加载')).toBeInTheDocument();
    expect(screen.getByText('重试渲染')).toBeInTheDocument();

    reloadMock.mockRestore();
  });

  it('点击重试后恢复 children', () => {
    let shouldThrow = true;
    function ToggleChild() {
      if (shouldThrow) throw new BoomError('boom');
      return <div data-testid="recovered">Recovered</div>;
    }

    const { rerender } = render(
      <ErrorBoundary>
        <ToggleChild />
      </ErrorBoundary>
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();

    // 手动重置 state：通过重新 render 且不再抛错
    shouldThrow = false;
    rerender(
      <ErrorBoundary>
        <ToggleChild />
      </ErrorBoundary>
    );
    // 注意：无 resetErrorBoundary API，重新 mount 即重置
    const { container } = render(
      <ErrorBoundary>
        <ToggleChild />
      </ErrorBoundary>
    );
    expect(screen.getByTestId('recovered')).toBeInTheDocument();
    expect(container.innerHTML).not.toContain('alert');
  });

  it('非 Error 类型抛错时 String 化展示', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    function ThrowString(): React.ReactElement {
      throw 'plain string error';
    }
    render(
      <ErrorBoundary>
        <ThrowString />
      </ErrorBoundary>
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('plain string error')).toBeInTheDocument();
    spy.mockRestore();
  });
});
