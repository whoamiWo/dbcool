import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ChannelList } from './ChannelList';
import type { ImChannel } from './api';

const channel = (over: Partial<ImChannel> = {}): ImChannel => ({
  id: 'c1',
  name: '频道一',
  type: 'PUBLIC',
  topic: '',
  createdBy: 'u1',
  createdAt: '2026-01-15T10:00:00Z',
  updatedAt: '2026-01-15T10:00:00Z',
  archived: false,
  ...over,
});

const user = {
  id: 'u1',
  username: 'alice',
  tenant_id: 'tenant_default',
  roles: [],
  display_name: 'Alice',
} as never;

const baseProps = () => ({
  channels: [] as ImChannel[],
  selectedChannel: null as ImChannel | null,
  onSelect: vi.fn(),
  onRefresh: vi.fn(),
  onCreateChannel: vi.fn(),
  currentUser: user,
  getUnreadCount: vi.fn().mockResolvedValue({ data: { unread_count: 0 } }),
});

describe('ChannelList', () => {
  beforeEach(() => vi.clearAllMocks());

  it('渲染「对话」标题与新建/刷新按钮', () => {
    render(<ChannelList {...baseProps()} />);
    expect(screen.getByText('对话')).toBeInTheDocument();
    expect(screen.getByText('新建')).toBeInTheDocument();
    expect(screen.getByText('刷新')).toBeInTheDocument();
  });

  it('空列表显示「暂无频道」', () => {
    render(<ChannelList {...baseProps()} />);
    expect(screen.getByText('暂无频道')).toBeInTheDocument();
  });

  it('显示当前登录用户名', () => {
    render(<ChannelList {...baseProps()} />);
    expect(screen.getByText(/当前:alice/)).toBeInTheDocument();
  });

  it('渲染频道名称与类型(群聊/私聊)', () => {
    const props = baseProps();
    props.channels = [
      channel({ id: 'c1', name: '产品组', type: 'PUBLIC' }),
      channel({ id: 'c2', name: '小张', type: 'PRIVATE' }),
    ];
    render(<ChannelList {...props} />);
    expect(screen.getByText('产品组')).toBeInTheDocument();
    expect(screen.getByText('群聊')).toBeInTheDocument();
    expect(screen.getByText('小张')).toBeInTheDocument();
    expect(screen.getByText('私聊')).toBeInTheDocument();
  });

  it('点击频道触发 onSelect', () => {
    const props = baseProps();
    const c = channel({ id: 'c9', name: '点我频道' });
    props.channels = [c];
    render(<ChannelList {...props} />);
    fireEvent.click(screen.getByText('点我频道'));
    expect(props.onSelect).toHaveBeenCalledWith(c);
  });

  it('未读数 > 0 显示角标并按 channelId 拉取', async () => {
    const props = baseProps();
    props.channels = [channel({ id: 'cu', name: '有未读' })];
    props.getUnreadCount = vi.fn().mockResolvedValue({ data: { unread_count: 3 } });
    render(<ChannelList {...props} />);
    expect(await screen.findByText('3')).toBeInTheDocument();
    expect(props.getUnreadCount).toHaveBeenCalledWith('cu');
  });

  it('未读数 > 99 显示 99+', async () => {
    const props = baseProps();
    props.channels = [channel({ id: 'cu2', name: '很多未读' })];
    props.getUnreadCount = vi.fn().mockResolvedValue({ data: { unread_count: 150 } });
    render(<ChannelList {...props} />);
    expect(await screen.findByText('99+')).toBeInTheDocument();
  });

  it('未读拉取失败时降级为 0,不显示角标', async () => {
    const props = baseProps();
    props.channels = [channel({ id: 'cf', name: '未读失败' })];
    props.getUnreadCount = vi.fn().mockRejectedValue(new Error('boom'));
    render(<ChannelList {...props} />);
    // 频道名仍应渲染,不因未读失败而崩溃
    expect(await screen.findByText('未读失败')).toBeInTheDocument();
  });

  it('点击「新建」触发 onCreateChannel,「刷新」触发 onRefresh', () => {
    const props = baseProps();
    render(<ChannelList {...props} />);
    fireEvent.click(screen.getByText('新建'));
    expect(props.onCreateChannel).toHaveBeenCalled();
    fireEvent.click(screen.getByText('刷新'));
    expect(props.onRefresh).toHaveBeenCalled();
  });

  it('无 name 时回退到 topic 或「未命名频道」', () => {
    const props = baseProps();
    props.channels = [
      channel({ id: 't1', name: '', topic: '临时话题' }),
      channel({ id: 't2', name: '', topic: '' }),
    ];
    render(<ChannelList {...props} />);
    expect(screen.getByText('临时话题')).toBeInTheDocument();
    expect(screen.getByText('未命名频道')).toBeInTheDocument();
  });

  it('R3:按频道类型渲染分组头(公开频道/私有频道)', () => {
    const props = baseProps();
    props.channels = [
      channel({ id: 'c1', name: '产品组', type: 'PUBLIC' }),
      channel({ id: 'c2', name: '小张', type: 'PRIVATE' }),
    ];
    render(<ChannelList {...props} />);
    expect(screen.getByText('公开频道 (1)')).toBeInTheDocument();
    expect(screen.getByText('私有频道 (1)')).toBeInTheDocument();
  });

  it('R3:点击分组头折叠后再展开,频道可见性随之切换', () => {
    const props = baseProps();
    props.channels = [channel({ id: 'c1', name: '折叠测试频道', type: 'PUBLIC' })];
    render(<ChannelList {...props} />);

    // 默认展开
    expect(screen.getByText('折叠测试频道')).toBeInTheDocument();

    const groupHeader = screen.getByText('公开频道 (1)');
    fireEvent.click(groupHeader);
    // 折叠后频道隐藏
    expect(screen.queryByText('折叠测试频道')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('公开频道 (1)'));
    // 再次点击恢复展开
    expect(screen.getByText('折叠测试频道')).toBeInTheDocument();
  });

  it('R3:折叠分组不影响其他分组的频道显示', () => {
    const props = baseProps();
    props.channels = [
      channel({ id: 'c1', name: '公开频道A', type: 'PUBLIC' }),
      channel({ id: 'c2', name: '私有频道B', type: 'PRIVATE' }),
    ];
    render(<ChannelList {...props} />);
    fireEvent.click(screen.getByText('公开频道 (1)'));
    expect(screen.queryByText('公开频道A')).not.toBeInTheDocument();
    expect(screen.getByText('私有频道B')).toBeInTheDocument();
  });
});
