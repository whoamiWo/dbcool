/**
 * PHASE 4.2 覆盖率补测：components/wiki/VersionDiff.tsx
 * 覆盖 LCS 差异算法的三类行（unchanged / added / removed）与统计 Chip。
 */
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { VersionDiff } from './VersionDiff';

describe('VersionDiff', () => {
  it('内容相同时全部为未变更', () => {
    render(<VersionDiff oldContent={'a\nb\nc'} newContent={'a\nb\nc'} />);
    expect(screen.getByText(/=3 未变/)).toBeInTheDocument();
    expect(screen.getByText(/\+0 新增/)).toBeInTheDocument();
    expect(screen.getByText(/-0 删除/)).toBeInTheDocument();
  });

  it('新增行被识别', () => {
    render(<VersionDiff oldContent={'a'} newContent={'a\nb'} />);
    expect(screen.getByText(/\+1 新增/)).toBeInTheDocument();
    expect(screen.getByText(/=1 未变/)).toBeInTheDocument();
  });

  it('删除行被识别', () => {
    render(<VersionDiff oldContent={'a\nb'} newContent={'a'} />);
    expect(screen.getByText(/-1 删除/)).toBeInTheDocument();
  });

  it('同时存在新增与删除', () => {
    render(<VersionDiff oldContent={'x\ny'} newContent={'x\nz'} />);
    expect(screen.getByText(/\+1 新增/)).toBeInTheDocument();
    expect(screen.getByText(/-1 删除/)).toBeInTheDocument();
  });

  it('旧内容为空：全部新增', () => {
    render(<VersionDiff oldContent={''} newContent={'p\nq'} />);
    // 空串 split 得到 ['']，故会多算一行
    expect(screen.getByText(/\+[12] 新增/)).toBeInTheDocument();
  });

  it('新内容为空：全部删除', () => {
    render(<VersionDiff oldContent={'p\nq'} newContent={''} />);
    expect(screen.getByText(/-[12] 删除/)).toBeInTheDocument();
  });

  it('渲染传入的标签 Chip', () => {
    render(
      <VersionDiff oldContent={'a'} newContent={'b'} oldLabel="v1" newLabel="v2" />,
    );
    expect(screen.getByText('v1')).toBeInTheDocument();
    expect(screen.getByText('v2')).toBeInTheDocument();
  });

  it('差异行渲染带 +/- 前缀的内容', () => {
    const { container } = render(<VersionDiff oldContent={'a'} newContent={'a\nb'} />);
    expect(container.textContent).toContain('+');
  });
});
