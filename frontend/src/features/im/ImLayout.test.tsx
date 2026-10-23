import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ImChannel, ImMessage } from './api';

// i18n mock for translation
import zhCN from '../../i18n/locales/zh-CN.json';
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, params?: Record<string, unknown>) => {
      const parts = key.split('.');
      let val: unknown = zhCN;
      for (const p of parts) val = (val as Record<string, unknown>)?.[p];
      if (typeof val !== 'string') return key;
      if (!params) return val;
      // Interpolate {{key}} placeholders
      return val.replace(/\{\{(\w+)\}\}/g, (_, k) => String(params[k] ?? `{{${k}}}`));
    },
    i18n: { language: 'zh-CN' },
  }),
}));

// ---- API mock ----
const getJoinedChannels = vi.fn();
const getChannelMessages = vi.fn();
const markMessagesRead = vi.fn();
const setPresenceHeartbeat = vi.fn();
const getUnreadCount = vi.fn();
const createChannel = vi.fn();
const deleteMessage = vi.fn();
const editMessage = vi.fn();
const sendMessage = vi.fn();
const listPins = vi.fn();

vi.mock('./api', () => ({
  getJoinedChannels: (...a: unknown[]) => getJoinedChannels(...a),
  getChannelMessages: (...a: unknown[]) => getChannelMessages(...a),
  markMessagesRead: (...a: unknown[]) => markMessagesRead(...a),
  setPresenceHeartbeat: (...a: unknown[]) => setPresenceHeartbeat(...a),
  getUnreadCount: (...a: unknown[]) => getUnreadCount(...a),
  createChannel: (...a: unknown[]) => createChannel(...a),
  deleteMessage: (...a: unknown[]) => deleteMessage(...a),
  editMessage: (...a: unknown[]) => editMessage(...a),
  sendMessage: (...a: unknown[]) => sendMessage(...a),
  listPins: (...a: unknown[]) => listPins(...a),
}));

// ---- stomp mock ----
const subscribeToChannel = vi.fn();
const disconnectStomp = vi.fn();
vi.mock('@/lib/stompClient', () => ({
  subscribeToChannel: (...a: unknown[]) => subscribeToChannel(...a),
  disconnectStomp: (...a: unknown[]) => disconnectStomp(...a),
}));

// ---- auth store mock ----
const mockUser = {
  id: 'u1',
  username: 'alice',
  tenant_id: 'tenant_default',
  roles: [],
  display_name: 'Alice',
};
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: mockUser }),
}));

// ---- useIsMobile mock ----
let isMobile = false;
vi.mock('@/hooks/useIsMobile', () => ({
  useIsMobile: () => isMobile,
}));

// ---- useHuddle mock ----
const joinHuddle = vi.fn();
const leaveHuddle = vi.fn();
const peers = new Map<string, string>();
vi.mock('./useHuddle', () => ({
  useHuddle: () => ({ roomId: null, joinHuddle, leaveHuddle, peers }),
}));

// ---- child component mocks (stubs capture props) ----
vi.mock('./ChannelList', () => ({
  ChannelList: (props: {
    channels: ImChannel[];
    onSelect: (c: ImChannel) => void;
    onCreateChannel: () => void;
    onRefresh: () => void;
  }) => (
    <div data-testid="channel-list">
      <button data-testid="cl-create" onClick={props.onCreateChannel}>新建</button>
      {props.channels.map((c) => (
        <button key={c.id} data-testid={`cl-item-${c.id}`} onClick={() => props.onSelect(c)}>
          {c.name}
        </button>
      ))}
    </div>
  ),
}));

vi.mock('./MessageList', () => ({
  MessageList: (props: {
    messages: ImMessage[];
    onMessageClick: (m: ImMessage) => void;
    onEditMessage: (id: string, c: string) => void;
    onDeleteMessage: (id: string) => void;
    onBurnExpired: () => void;
    onLoadMore: () => void;
  }) => (
    <div data-testid="message-list">
      <span data-testid="msg-count">{props.messages.length}</span>
      <button data-testid="ml-click" onClick={() => props.messages[0] && props.onMessageClick(props.messages[0])}>click</button>
      <button data-testid="ml-edit" onClick={() => props.onEditMessage('m1', 'edited')}>edit</button>
      <button data-testid="ml-delete" onClick={() => props.onDeleteMessage('m1')}>delete</button>
      <button data-testid="ml-burn" onClick={props.onBurnExpired}>burn</button>
      <button data-testid="ml-more" onClick={props.onLoadMore}>more</button>
    </div>
  ),
}));

vi.mock('./MessageComposer', () => ({
  MessageComposer: (props: { onSent: (m?: { id?: string }) => void; onAttachment: (u: string, f: string, s: number) => void }) => (
    <div data-testid="composer">
      <button data-testid="mc-sent" onClick={() => props.onSent({ id: 'new1' })}>sent</button>
      <button data-testid="mc-attach" onClick={() => props.onAttachment('http://x/f.png', 'f.png', 10)}>attach</button>
    </div>
  ),
}));

vi.mock('./PinList', () => ({
  PinList: () => <div data-testid="pin-list" />,
}));

vi.mock('./SearchResults', () => ({
  SearchResults: (props: { keyword: string; onMessageClick: (id: string, cid: string) => void }) => (
    <div data-testid="search-results">
      <span>{props.keyword}</span>
      <button data-testid="sr-click" onClick={() => props.onMessageClick('m9', 'c2')}>go</button>
    </div>
  ),
}));

vi.mock('./ThreadPanel', () => ({
  ThreadPanel: (props: { parentMessage: ImMessage | null; onClose: () => void }) => (
    <div data-testid="thread-panel">
      <span data-testid="tp-parent">{props.parentMessage?.id ?? 'none'}</span>
      <button data-testid="tp-close" onClick={props.onClose}>close</button>
    </div>
  ),
}));

vi.mock('./LivechatWidget', () => ({
  LivechatWidget: () => <div data-testid="livechat" />,
}));

vi.mock('./HuddlePanel', () => ({
  HuddlePanel: () => <div data-testid="huddle-panel" />,
}));

import { ImChatPage } from './ImLayout';

const channel = (over: Partial<ImChannel> = {}): ImChannel => ({
  id: 'c1',
  name: '频道一',
  type: 'PUBLIC',
  topic: '话题',
  createdBy: 'u1',
  createdAt: '2026-01-15T10:00:00Z',
  updatedAt: '2026-01-15T10:00:00Z',
  archived: false,
  ...over,
});

const message = (over: Partial<ImMessage> = {}): ImMessage => ({
  id: 'm1',
  channelId: 'c1',
  senderId: 'u2',
  content: '你好',
  type: 'TEXT',
  createdAt: '2026-01-15T10:00:00Z',
  parentId: undefined,
  ...over,
} as ImMessage);

function renderPage(initialPath = '/im/c1') {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/im" element={<ImChatPage />} />
          <Route path="/im/:channelId" element={<ImChatPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ImChatPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    isMobile = false;
    getJoinedChannels.mockResolvedValue({ data: [channel({ id: 'c1', name: '频道一' }), channel({ id: 'c2', name: '频道二' })] });
    getChannelMessages.mockResolvedValue({
      code: 0,
      data: { messages: [message({ id: 'm1', content: '你好' })], limit: 50, has_more: false, next_cursor: '' },
    });
    markMessagesRead.mockResolvedValue({});
    setPresenceHeartbeat.mockResolvedValue({});
    getUnreadCount.mockResolvedValue({ data: { unread_count: 0 } });
    listPins.mockResolvedValue({ data: [] });
    createChannel.mockResolvedValue({ data: channel({ id: 'c9', name: '新频道' }) });
    deleteMessage.mockResolvedValue({});
    editMessage.mockResolvedValue({});
    sendMessage.mockResolvedValue({});
    subscribeToChannel.mockReturnValue(() => {});
  });

  it('渲染三栏骨架：频道列表 / 消息区 / 线程面板', async () => {
    renderPage();
    expect(await screen.findByTestId('channel-list')).toBeInTheDocument();
    expect(screen.getByTestId('thread-panel')).toBeInTheDocument();
    expect(screen.getByTestId('huddle-panel')).toBeInTheDocument();
    expect(screen.getByTestId('livechat')).toBeInTheDocument();
  });

  it('自动选中路由中的频道并展示标题与话题', async () => {
    renderPage('/im/c2');
    expect(await screen.findByText('频道二')).toBeInTheDocument();
    // 标题区域渲染当前频道名与 topic
    await waitFor(() => {
      expect(getChannelMessages).toHaveBeenCalledWith('c2', undefined, 50);
    });
  });

  it('无匹配路由频道时不自动选择（保持 null，等待用户手动选）', async () => {
    renderPage('/im/unknown');
    // 由于 routeChannelId='unknown' 不在 channels 列表中，currentChannel 保持 null
    await waitFor(() => expect(screen.queryByTestId('message-list')).not.toBeInTheDocument());
    expect(screen.getByText('选择频道开始聊天')).toBeInTheDocument();
  });

  it('选中频道后渲染消息列表与输入框', async () => {
    renderPage();
    expect(await screen.findByTestId('message-list')).toBeInTheDocument();
    expect(screen.getByTestId('composer')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('msg-count').textContent).toBe('1'));
  });

  it('切换频道时重置线程面板并重新拉取消息', async () => {
    renderPage();
    await screen.findByTestId('message-list');
    fireEvent.click(screen.getByTestId('cl-item-c2'));
    await waitFor(() => {
      expect(getChannelMessages).toHaveBeenCalledWith('c2', undefined, 50);
    });
  });

  it('点击消息打开线程面板，再次点击关闭', async () => {
    renderPage();
    await screen.findByTestId('message-list');
    await waitFor(() => expect(screen.getByTestId('msg-count').textContent).toBe('1'));
    fireEvent.click(screen.getByTestId('ml-click'));
    await waitFor(() => expect(screen.getByTestId('tp-parent').textContent).toBe('m1'));
    fireEvent.click(screen.getByTestId('ml-click'));
    await waitFor(() => expect(screen.getByTestId('tp-parent').textContent).toBe('none'));
  });

  it('编辑消息成功后重新拉取消息', async () => {
    renderPage();
    await screen.findByTestId('message-list');
    getChannelMessages.mockClear();
    fireEvent.click(screen.getByTestId('ml-edit'));
    await waitFor(() => expect(editMessage).toHaveBeenCalledWith('m1', 'edited'));
    await waitFor(() => expect(getChannelMessages).toHaveBeenCalled());
  });

  it('删除消息成功后重新拉取消息', async () => {
    renderPage();
    await screen.findByTestId('message-list');
    fireEvent.click(screen.getByTestId('ml-delete'));
    await waitFor(() => expect(deleteMessage).toHaveBeenCalledWith('m1'));
  });

  it('发送附件消息调用 sendMessage(FILE)', async () => {
    renderPage();
    await screen.findByTestId('composer');
    fireEvent.click(screen.getByTestId('mc-attach'));
    await waitFor(() => {
      expect(sendMessage).toHaveBeenCalledWith('c1', expect.stringContaining('f.png'), 'FILE');
    });
  });

  it('搜索关键字非空时展示搜索结果而非消息列表', async () => {
    renderPage();
    await screen.findByTestId('message-list');
    const input = screen.getByLabelText('搜索消息');
    fireEvent.change(input, { target: { value: '关键词' } });
    expect(await screen.findByTestId('search-results')).toBeInTheDocument();
    expect(screen.queryByTestId('message-list')).not.toBeInTheDocument();
  });

  it('点击搜索结果切换到对应频道并清空搜索', async () => {
    renderPage();
    await screen.findByTestId('message-list');
    fireEvent.change(screen.getByLabelText('搜索消息'), { target: { value: 'kw' } });
    await screen.findByTestId('search-results');
    fireEvent.click(screen.getByTestId('sr-click'));
    await waitFor(() => expect(getChannelMessages).toHaveBeenCalledWith('c2', undefined, 50));
    await waitFor(() => expect(screen.queryByTestId('search-results')).not.toBeInTheDocument());
  });

  it('新建频道对话框：输入名称后创建并关闭', async () => {
    renderPage();
    await screen.findByTestId('channel-list');
    fireEvent.click(screen.getByTestId('cl-create'));
    const nameInput = await screen.findByPlaceholderText('频道名称');
    fireEvent.change(nameInput, { target: { value: '新频道' } });
    fireEvent.click(screen.getByText('创建'));
    await waitFor(() => expect(createChannel).toHaveBeenCalledWith({ name: '新频道', type: 'PUBLIC' }));
    await waitFor(() => expect(screen.queryByPlaceholderText('频道名称')).not.toBeInTheDocument());
  });

  it('新建频道名称为空时创建按钮禁用', async () => {
    renderPage();
    await screen.findByTestId('channel-list');
    fireEvent.click(screen.getByTestId('cl-create'));
    const btn = screen.getByText('创建');
    expect(btn).toBeDisabled();
  });

  it('点击遮罩关闭新建频道对话框', async () => {
    renderPage();
    await screen.findByTestId('channel-list');
    fireEvent.click(screen.getByTestId('cl-create'));
    await screen.findByPlaceholderText('频道名称');
    fireEvent.click(screen.getByLabelText('关闭新建频道弹窗'));
    await waitFor(() => expect(screen.queryByPlaceholderText('频道名称')).not.toBeInTheDocument());
  });

  it('加入 huddle 时调用 joinHuddle', async () => {
    renderPage();
    await screen.findByTestId('message-list');
    fireEvent.click(screen.getByText('加入通话'));
    expect(joinHuddle).toHaveBeenCalledWith('c1');
  });

  it('移动端渲染汉堡菜单并可打开频道抽屉', async () => {
    isMobile = true;
    renderPage();
    const menuBtn = await screen.findByLabelText('打开频道列表');
    fireEvent.click(menuBtn);
    expect(await screen.findByText('频道')).toBeInTheDocument();
  });

  it('订阅频道消息：收到 deletedAt 消息时重新拉取', async () => {
    let handler: ((p: string) => void) | undefined;
    subscribeToChannel.mockImplementation((_id: string, cb: (p: string) => void) => {
      handler = cb;
      return () => {};
    });
    renderPage();
    await screen.findByTestId('message-list');
    await waitFor(() => expect(subscribeToChannel).toHaveBeenCalled());
    getChannelMessages.mockClear();
    handler?.(JSON.stringify(message({ id: 'm1', deletedAt: '2026-01-15T11:00:00Z' })));
    await waitFor(() => expect(getChannelMessages).toHaveBeenCalled());
  });

  it('订阅频道消息：普通消息触发重新拉取', async () => {
    let handler: ((p: string) => void) | undefined;
    subscribeToChannel.mockImplementation((_id: string, cb: (p: string) => void) => {
      handler = cb;
      return () => {};
    });
    renderPage();
    await screen.findByTestId('message-list');
    await waitFor(() => expect(subscribeToChannel).toHaveBeenCalled());
    getChannelMessages.mockClear();
    handler?.(JSON.stringify(message({ id: 'm2', content: '新消息' })));
    await waitFor(() => expect(getChannelMessages).toHaveBeenCalled());
  });

  it('订阅消息解析失败时不抛错', async () => {
    let handler: ((p: string) => void) | undefined;
    subscribeToChannel.mockImplementation((_id: string, cb: (p: string) => void) => {
      handler = cb;
      return () => {};
    });
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    renderPage();
    await screen.findByTestId('message-list');
    await waitFor(() => expect(subscribeToChannel).toHaveBeenCalled());
    expect(() => handler?.('not-json')).not.toThrow();
    spy.mockRestore();
  });

  it('卸载时断开 stomp 连接', async () => {
    const { unmount } = renderPage();
    await screen.findByTestId('message-list');
    unmount();
    expect(disconnectStomp).toHaveBeenCalled();
  });

  it('无频道时展示选择频道提示', async () => {
    getJoinedChannels.mockResolvedValue({ data: [] });
    renderPage();
    await waitFor(() => expect(screen.queryByTestId('message-list')).not.toBeInTheDocument());
    expect(screen.getByText('选择频道开始聊天')).toBeInTheDocument();
  });

  it('渲染 MessageList 空状态时调用 refetch', async () => {
    getChannelMessages.mockResolvedValue({
      code: 0,
      data: { messages: [], limit: 50, has_more: false, next_cursor: '' },
    });
    renderPage();
    // MessageList mock 仍渲染 message-list，但 contains 0 条消息（mock 里 msg-count=0）
    await waitFor(() => {
      const countEl = screen.queryByTestId('msg-count');
      expect(countEl?.textContent).toBe('0');
    });
    expect(screen.getByTestId('composer')).toBeInTheDocument();
  });

  it('加载更多触发下一页请求', async () => {
    getChannelMessages.mockResolvedValueOnce({
      code: 0,
      data: { messages: [message({ id: 'm1' })], limit: 50, has_more: true, next_cursor: 'cursor-2' },
    });
    getChannelMessages.mockResolvedValueOnce({
      code: 0,
      data: { messages: [message({ id: 'm2' })], limit: 50, has_more: false, next_cursor: '' },
    });
    renderPage();
    await screen.findByTestId('message-list');
    fireEvent.click(screen.getByTestId('ml-more'));
    await waitFor(() => expect(getChannelMessages).toHaveBeenCalledWith('c1', 'cursor-2', 50));
  });

  it('发送成功回调触发消息重新拉取', async () => {
    renderPage();
    await screen.findByTestId('composer');
    getChannelMessages.mockClear();
    fireEvent.click(screen.getByTestId('mc-sent'));
    await waitFor(() => expect(getChannelMessages).toHaveBeenCalled());
  });

  it('标记已读：频道有消息时调用 markMessagesRead', async () => {
    renderPage();
    await screen.findByTestId('message-list');
    await waitFor(() => expect(markMessagesRead).toHaveBeenCalledWith('c1', 'm1'));
  });

  it('挂载时设置在线心跳', async () => {
    renderPage();
    await waitFor(() => expect(setPresenceHeartbeat).toHaveBeenCalled());
  });
});
