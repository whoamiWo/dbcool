import { useEffect, useState, useCallback, useMemo } from 'react';
import { useQuery, useInfiniteQuery } from '@tanstack/react-query';
import { useParams, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/auth';
import {
  getJoinedChannels,
  getChannelMessages,
  markMessagesRead,
  setPresenceHeartbeat,
  getUnreadCount,
  createChannel,
  deleteMessage as deleteMsg,
  editMessage as editMsgFn,
  sendMessage,
  listPins,
  type ImChannel,
  type ImMessage,
} from './api';
import { subscribeToChannel, disconnectStomp } from '@/lib/stompClient';
import { ChannelList } from './ChannelList';
import { MessageComposer } from './MessageComposer';
import { MessageList } from './MessageList';
import { PinList } from './PinList';
import { SearchResults } from './SearchResults';
import { ThreadPanel } from './ThreadPanel';

/** IM 聊天主布局，三栏：频道列表 | 消息流 | 线程面板 */
export function ImChatPage() {
  const { user } = useAuthStore();
  const { channelId: routeChannelId } = useParams();
  const navigate = useNavigate();
  const [currentChannel, setCurrentChannel] = useState<ImChannel | null>(null);
  const [threadMessage, setThreadMessage] = useState<ImMessage | null>(null);
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [newChannelName, setNewChannelName] = useState('');
  const [searchKeyword, setSearchKeyword] = useState('');

  const { data: channelsData, refetch: refetchChannels } = useQuery({
    queryKey: ['im-channels'],
    queryFn: getJoinedChannels,
    refetchInterval: 60000,
  });

  const channels = channelsData?.data || [];

  const {
    data: messagesData,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    refetch: refetchMessages,
  } = useInfiniteQuery({
    queryKey: ['im-messages', currentChannel?.id],
    queryFn: ({ pageParam }) =>
      getChannelMessages(currentChannel!.id, pageParam as string | undefined, 50),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => {
      if (!lastPage.data.has_more) return undefined;
      return lastPage.data.next_cursor;
    },
    enabled: !!currentChannel,
  });

  const messages = messagesData?.pages.flatMap((p) => p.data.messages) ?? [];

  const { data: pinsData, refetch: refetchPins } = useQuery({
    queryKey: ['im-pins', currentChannel?.id],
    queryFn: () => listPins(currentChannel!.id),
    enabled: !!currentChannel,
  });
  const pinnedMessageIds = useMemo(
    () => new Set((pinsData?.data ?? []).map((p) => p.messageId)),
    [pinsData],
  );

  useEffect(() => {
    if (channels.length > 0 && !currentChannel) {
      const selectedChannelId = routeChannelId || channels[0].id;
      const channel = channels.find((c) => c.id === selectedChannelId);
      if (channel) {
        setCurrentChannel(channel);
      }
    }
  }, [channels, routeChannelId, currentChannel]);

  useEffect(() => {
    if (currentChannel && messages.length > 0) {
      const last = messages[messages.length - 1];
      if (last) {
        markMessagesRead(currentChannel.id, last.id).catch(() => undefined);
      }
    }
  }, [currentChannel?.id, messages.length]);

  useEffect(() => {
    if (!user) return;
    setPresenceHeartbeat();
    const timer = setInterval(() => setPresenceHeartbeat(), 30000);
    return () => clearInterval(timer);
  }, [user?.id]);

  const handleChannelSelect = useCallback((channel: ImChannel) => {
    setCurrentChannel(channel);
    setThreadMessage(null);
    navigate(`/im/${channel.id}`);
  }, [navigate]);

  const handleMessageClick = useCallback((message: ImMessage) => {
    if (message.parentId) return;
    setThreadMessage(prev => (prev?.id === message.id ? null : message));
  }, []);

  const handleEditMessage = useCallback(async (messageId: string, content: string) => {
    if (!currentChannel) return;
    try {
      await editMsgFn(messageId, content);
      refetchMessages();
    } catch (e) {
      console.error('编辑消息失败', e);
    }
  }, [currentChannel, refetchMessages]);

  const handleDeleteMessage = useCallback(async (messageId: string) => {
    if (!currentChannel) return;
    try {
      await deleteMsg(messageId);
      refetchMessages();
    } catch (e) {
      console.error('删除消息失败', e);
    }
  }, [currentChannel, refetchMessages]);

  const handleBurnExpired = useCallback(() => {
    refetchMessages();
  }, [refetchMessages]);

  const handleAttachment = useCallback(
    async (url: string, filename: string, _size: number) => {
      if (!currentChannel) return;
      try {
        await sendMessage(currentChannel.id, `[附件] ${filename}\n${url}`, 'FILE');
        refetchMessages();
      } catch (e) {
        console.error('发送附件消息失败', e);
      }
    },
    [currentChannel, refetchMessages],
  );

  const handleSearchResultClick = useCallback(
    (messageId: string, channelId: string) => {
      const target = channels.find((c) => c.id === channelId);
      if (target) {
        handleChannelSelect(target);
        setSearchKeyword('');
      }
      void messageId;
    },
    [channels, handleChannelSelect],
  );

  const handleCreateChannel = useCallback(async () => {
    if (!newChannelName.trim()) return;
    try {
      await createChannel({ name: newChannelName.trim(), type: 'PUBLIC' });
      setNewChannelName('');
      setShowCreateDialog(false);
      refetchChannels();
    } catch (e) {
      console.error('创建频道失败', e);
    }
  }, [newChannelName, refetchChannels]);

  useEffect(() => {
    if (!user || !currentChannel) return;
    const unsub = subscribeToChannel(currentChannel.id, (payload) => {
      try {
        const msg = JSON.parse(payload) as ImMessage;
        if (msg.deletedAt) {
          refetchMessages();
          return;
        }
        setThreadMessage?.((prev) => prev?.id === msg.id ? null : prev);
        refetchMessages();
      } catch (e) {
        console.error('解析消息失败', e);
      }
    });
    return () => { unsub(); };
  }, [currentChannel?.id, user?.id, refetchMessages, setThreadMessage]);

  useEffect(() => {
    return () => { disconnectStomp(); };
  }, []);

  return (
    <div className="im-layout">
      <ChannelList
        channels={channels}
        selectedChannel={currentChannel}
        onSelect={handleChannelSelect}
        onRefresh={refetchChannels}
        onCreateChannel={() => setShowCreateDialog(true)}
        currentUser={user}
        getUnreadCount={getUnreadCount}
      />

      <div className="im-main">
        <div className="im-header">
          <div>
            <h3 style={{ margin: 0, fontSize: 16, fontWeight: 600, color: 'var(--color-text-primary)' }}>
              {currentChannel?.name || '选择频道'}
            </h3>
            <div style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>
              {currentChannel?.topic || ''}
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <input
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
              placeholder="搜索消息…"
              aria-label="搜索消息"
              className="input-glass"
              style={{ width: 160 }}
            />
            <div style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>
              <span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: '50%', background: 'var(--color-success)', marginRight: 4 }} />
              在线
            </div>
          </div>
        </div>

        {currentChannel && (
          <PinList
            channelId={currentChannel.id}
            channel={currentChannel}
            onChanged={refetchPins}
          />
        )}

        <div className="im-content">
          {searchKeyword.trim() ? (
            <div style={{ flex: 1, overflowY: 'auto', background: 'rgba(15, 23, 42, 0.5)' }}>
              <SearchResults
                keyword={searchKeyword}
                onMessageClick={handleSearchResultClick}
              />
            </div>
          ) : currentChannel && user?.id ? (
            <MessageList
              currentUserId={user.id}
              messages={messages}
              onMessageClick={handleMessageClick}
              canLoadMore={hasNextPage}
              isLoadingMore={isFetchingNextPage}
              onLoadMore={() => fetchNextPage()}
              onEditMessage={handleEditMessage}
              onDeleteMessage={handleDeleteMessage}
              pinnedMessageIds={pinnedMessageIds}
              onBurnExpired={handleBurnExpired}
            />
          ) : (
            <div
              style={{
                flex: 1,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: 'var(--color-text-muted)',
                fontSize: 14,
              }}
            >
              {currentChannel ? '暂无消息' : '请选择频道开始聊天'}
            </div>
          )}

          {currentChannel && (
            <MessageComposer
              channelId={currentChannel.id}
              onSent={(message) => {
                if (message?.id) {
                  refetchMessages();
                }
              }}
              disabled={!user}
              onAttachment={handleAttachment}
            />
          )}
        </div>
      </div>

      <ThreadPanel
        channelId={currentChannel?.id || null}
        parentMessage={threadMessage}
        onClose={() => setThreadMessage(null)}
      />

      {showCreateDialog && (
        <div
          role="dialog"
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(15,23,42,0.7)',
            backdropFilter: 'blur(8px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
          }}
          onClick={() => setShowCreateDialog(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="glass-strong"
            style={{ padding: 20, width: 320 }}
          >
            <h3 style={{ margin: '0 0 12px', fontSize: 15, color: 'var(--color-text-primary)' }}>新建频道</h3>
            <input
              value={newChannelName}
              onChange={(e) => setNewChannelName(e.target.value)}
              placeholder="频道名称"
              autoFocus
              className="input-glass"
              style={{ width: '100%', boxSizing: 'border-box', padding: '8px 12px', fontSize: 13 }}
            />
            <div style={{ display: 'flex', gap: 8, marginTop: 12, justifyContent: 'flex-end' }}>
              <button
                onClick={() => setShowCreateDialog(false)}
                className="glass-button"
                style={{ padding: '6px 12px', fontSize: 12 }}
              >
                取消
              </button>
              <button
                onClick={handleCreateChannel}
                disabled={!newChannelName.trim()}
                className="glass-button-primary"
                style={{
                  padding: '6px 12px',
                  fontSize: 12,
                  opacity: newChannelName.trim() ? 1 : 0.5,
                  cursor: newChannelName.trim() ? 'pointer' : 'not-allowed',
                }}
              >
                创建
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
