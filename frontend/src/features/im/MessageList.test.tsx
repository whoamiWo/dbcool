import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MessageList } from './MessageList';
import type { ImMessage } from './api';

// MessageList 直接依赖 api 的表情函数,全部 mock 掉避免真实网络
vi.mock('./api', () => ({
  addReaction: vi.fn().mockResolvedValue({}),
  removeReaction: vi.fn().mockResolvedValue({}),
  getReactions: vi.fn().mockResolvedValue({ data: [] }),
}));

const msg = (over: Partial<ImMessage> = {}): ImMessage => ({
  id: 'm1',
  channelId: 'c1',
  senderId: 'user-aaaa-0001',
  content: '默认内容',
  contentType: 'TEXT',
  createdAt: '2026-01-15T10:00:00Z',
  ...over,
});

describe('MessageList', () => {
  beforeEach(() => vi.clearAllMocks());

  it('渲染普通消息内容', () => {
    render(<MessageList currentUserId="me" messages={[msg({ content: '第一条消息' })]} />);
    expect(screen.getByText('第一条消息')).toBeInTheDocument();
  });

  // 保住项 #3:软删除必须渲染为「该消息已删除」,不得隐藏
  it('软删除消息渲染为「该消息已删除」', () => {
    render(
      <MessageList
        currentUserId="user-aaaa-0001"
        messages={[msg({ senderId: 'user-aaaa-0001', deletedAt: '2026-01-15T11:00:00Z' })]}
      />,
    );
    expect(screen.getByText('该消息已删除')).toBeInTheDocument();
  });

  it('软删除消息不显示编辑/删除按钮', () => {
    render(
      <MessageList
        currentUserId="user-aaaa-0001"
        messages={[msg({ senderId: 'user-aaaa-0001', deletedAt: '2026-01-15T11:00:00Z' })]}
      />,
    );
    expect(screen.queryByText('编辑')).not.toBeInTheDocument();
    expect(screen.queryByText('删除')).not.toBeInTheDocument();
  });

  it('本人消息显示编辑/删除按钮', () => {
    render(<MessageList currentUserId="me-1" messages={[msg({ senderId: 'me-1' })]} />);
    expect(screen.getByText('编辑')).toBeInTheDocument();
    expect(screen.getByText('删除')).toBeInTheDocument();
  });

  it('他人消息不显示编辑/删除按钮', () => {
    render(<MessageList currentUserId="me-1" messages={[msg({ senderId: 'other-1' })]} />);
    expect(screen.queryByText('编辑')).not.toBeInTheDocument();
    expect(screen.queryByText('删除')).not.toBeInTheDocument();
  });

  it('点击编辑进入编辑态,保存触发 onEditMessage', () => {
    const onEditMessage = vi.fn();
    render(
      <MessageList
        currentUserId="me-1"
        messages={[msg({ id: 'e1', senderId: 'me-1', content: '原文' })]}
        onEditMessage={onEditMessage}
      />,
    );
    fireEvent.click(screen.getByText('编辑'));
    const input = screen.getByDisplayValue('原文');
    fireEvent.change(input, { target: { value: '改后' } });
    fireEvent.click(screen.getByText('保存'));
    expect(onEditMessage).toHaveBeenCalledWith('e1', '改后');
  });

  it('删除需 window.confirm 确认后触发 onDeleteMessage', () => {
    const spy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const onDeleteMessage = vi.fn();
    render(
      <MessageList
        currentUserId="me-1"
        messages={[msg({ id: 'del-1', senderId: 'me-1' })]}
        onDeleteMessage={onDeleteMessage}
      />,
    );
    fireEvent.click(screen.getByText('删除'));
    expect(onDeleteMessage).toHaveBeenCalledWith('del-1');
    spy.mockRestore();
  });

  it('confirm 取消时不删除', () => {
    const spy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const onDeleteMessage = vi.fn();
    render(
      <MessageList
        currentUserId="me-1"
        messages={[msg({ id: 'del-2', senderId: 'me-1' })]}
        onDeleteMessage={onDeleteMessage}
      />,
    );
    fireEvent.click(screen.getByText('删除'));
    expect(onDeleteMessage).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  it('canLoadMore 时显示「加载更早消息」并触发 onLoadMore', () => {
    const onLoadMore = vi.fn();
    render(
      <MessageList currentUserId="me" messages={[]} canLoadMore onLoadMore={onLoadMore} />,
    );
    fireEvent.click(screen.getByRole('button', { name: /加载更早消息/ }));
    expect(onLoadMore).toHaveBeenCalled();
  });

  it('点击消息触发 onMessageClick', () => {
    const onMessageClick = vi.fn();
    render(
      <MessageList
        currentUserId="me"
        messages={[msg({ id: 'click-1', content: '点我' })]}
        onMessageClick={onMessageClick}
      />,
    );
    fireEvent.click(screen.getByText('点我'));
    expect(onMessageClick).toHaveBeenCalledWith(expect.objectContaining({ id: 'click-1' }));
  });
});
