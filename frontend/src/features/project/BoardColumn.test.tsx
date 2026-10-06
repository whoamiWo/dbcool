import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DndContext } from '@dnd-kit/core';
import { BoardColumn } from './BoardColumn';

// BoardColumn 内部的 SortableCard 需要 DndContext/SortableContext，
// 通过 BoardColumn 整体渲染（内部包含这两个 Provider）来测真实渲染结果。

const PAST_DATE = '2020-01-01T00:00:00Z';
const FUTURE_DATE = '2099-12-31T00:00:00Z';

describe('BoardColumn 卡片字段渲染', () => {
  it('checklists 有值时渲染清单项与进度（3/5）', () => {
    render(
      <BoardColumn
        columnId="col-1"
        title="待办"
        type="TODO"
        cards={[
          {
            id: 'task-1',
            title: '带清单的任务',
            status: 'TODO',
            priority: 'MEDIUM',
            progress: 0,
            checklists: [
              {
                id: 'cl-1',
                title: '验收项',
                items: [
                  { id: 'i1', title: '第一项', done: true },
                  { id: 'i2', title: '第二项', done: true },
                  { id: 'i3', title: '第三项', done: true },
                  { id: 'i4', title: '第四项', done: false },
                  { id: 'i5', title: '第五项', done: false },
                ],
              },
            ],
          },
        ]}
        onAddCard={() => {}}
      />,
    );

    // 清单标题
    expect(screen.getByText('验收项')).toBeInTheDocument();
    // 前 3 项渲染为 Chip
    expect(screen.getByText('第一项')).toBeInTheDocument();
    expect(screen.getByText('第二项')).toBeInTheDocument();
    expect(screen.getByText('第三项')).toBeInTheDocument();
    // 进度 3/5
    expect(screen.getByText('3/5 已完成')).toBeInTheDocument();
  });

  it('dueDate 为过去时渲染 error 色态，未来为非 error', () => {
    render(
      <DndContext>
        <BoardColumn
          columnId="col-1"
          title="待办"
          type="TODO"
          cards={[
            { id: 't-past', title: '逾期任务', status: 'TODO', priority: 'LOW', progress: 0, dueDate: PAST_DATE },
            { id: 't-future', title: '未来任务', status: 'TODO', priority: 'LOW', progress: 0, dueDate: FUTURE_DATE },
          ]}
          onAddCard={() => {}}
        />
      </DndContext>,
    );

    // 两个日期 Chip 都渲染
    const pastText = screen.getByText('2020/01/01');
    const futureText = screen.getByText('2099/12/31');
    expect(pastText).toBeInTheDocument();
    expect(futureText).toBeInTheDocument();

    // 过期日期 Chip 带 error 色（class 含MuiChip-colorError），未来为 default
    const pastChip = pastText.closest('.MuiChip-root');
    const futureChip = futureText.closest('.MuiChip-root');
    expect(pastChip?.className).toMatch(/MuiChip-colorError/);
    expect(futureChip?.className).not.toMatch(/MuiChip-colorError/);
  });

  it('无 dueDate 时不渲染日期占位', () => {
    render(
      <BoardColumn
        columnId="col-1"
        title="待办"
        type="TODO"
        cards={[{ id: 't-none', title: '无日期任务', status: 'TODO', priority: 'LOW', progress: 0 }]}
        onAddCard={() => {}}
      />,
    );

    // 不存在任何日期 Chip（CalendarToday 图标不存在）
    expect(screen.queryByText(/\/\d{2}\/\d{2}$/)).toBeNull();
    const chipRoots = document.querySelectorAll('.MuiChip-root');
    // 仅 2 个 Chip：priority + 列头计数（无日期、无标签、无清单项）
    expect(chipRoots.length).toBe(2);
  });

  it('清单超过 3 项时显示 "+N项" 溢出提示', () => {
    render(
      <BoardColumn
        columnId="col-1"
        title="待办"
        type="TODO"
        cards={[
          {
            id: 'task-2',
            title: '长清单任务',
            status: 'TODO',
            priority: 'MEDIUM',
            progress: 0,
            checklists: [
              {
                id: 'cl-2',
                title: '长清单',
                items: [
                  { id: 'j1', title: '项A', done: true },
                  { id: 'j2', title: '项B', done: false },
                  { id: 'j3', title: '项C', done: false },
                  { id: 'j4', title: '项D', done: false },
                ],
              },
            ],
          },
        ]}
        onAddCard={() => {}}
      />,
    );

    // 前 3 项直接渲染
    expect(screen.getByText('项A')).toBeInTheDocument();
    expect(screen.getByText('项B')).toBeInTheDocument();
    expect(screen.getByText('项C')).toBeInTheDocument();
    expect(screen.queryByText('项D')).not.toBeInTheDocument();
    // 溢出提示
    expect(screen.getByText('+1项')).toBeInTheDocument();
    // 进度 1/4
    expect(screen.getByText('1/4 已完成')).toBeInTheDocument();
  });

  it('无 checklists 时不渲染清单区块（无 "+0项"、无进度文本）', () => {
    render(
      <BoardColumn
        columnId="col-1"
        title="待办"
        type="TODO"
        cards={[{ id: 't-plain', title: '普通任务', status: 'TODO', priority: 'HIGH', progress: 0 }]}
        onAddCard={() => {}}
      />,
    );

    expect(screen.queryByText(/已完成$/)).toBeNull();
  });
});
