import { useEffect, useState, useCallback } from 'react';
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
  type ImChannel,
  type ImMessage,
} from './api';
import { subscribeToChannel, disconnectStomp } from '@/lib/stompClient';
import { ChannelList } from './ChannelList';
import { MessageComposer } from './MessageComposer';
import { MessageList } from './MessageList';
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

  const { data: channelsData, refetch: refetchChannels } = useQuery({
    queryKey: ['im-channels'],
    queryFn: getJoinedChannels,
    refetchInterval: 60000,
  });

  const channels = channelsData?.data || [];

  // Infinite query for cursor pagination
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
    // react-query v5 起 initialPageParam 为必传;缺失会导致泛型无法推导,
    // 使 getNextPageParam 的 lastPage 与后续 pages 元素退化成 unknown(连锁报错)。
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => {
      if (!lastPage.data.has_more) return undefined;
      return lastPage.data.next_cursor;
    },
    enabled: !!currentChannel,
  });

  const messages = messagesData?.pages.flatMap((p) => p.data.messages) ?? [];

  useEffect(() => {
    if (channels.length > 0 && !currentChannel) {
      const selectedChannelId = routeChannelId || channels[0].id;
      const channel = channels.find((c) => c.id === selectedChannelId);
      if (channel) {
        setCurrentChannel(channel);
      }
    }
  }, [channels, routeChannelId, currentChannel]);

  // 当频道切换时重置消息并标记已读
  useEffect(() => {
    if (currentChannel && messages.length > 0) {
      const last = messages[messages.length - 1];
      if (last) {
        markMessagesRead(currentChannel.id, last.id).catch(() => undefined);
      }
    }
  }, [currentChannel?.id, messages.length]);

  // 在线心跳
  useEffect(() => {
    if (!user) return;
    setPresenceHeartbeat();
    const timer = setInterval(() => setPresenceHeartbeat(), 30000);
    return () => clearInterval(timer);
  }, [user?.id]);

  const handleChannelSelect = useCallback((channel: ImChannel) => {
    setCurrentChannel(channel);
    setThreadMessage(null);
    navigate(`/im/${channel.id}`); // 用 navigate 替代 pushState
  }, [navigate]);

  const handleMessageClick = useCallback((message: ImMessage) => {
    // 仅主消息可打开线程
    if (message.parentId) return;
    setThreadMessage(prev => (prev?.id === message.id ? null : message));
  }, []);

  // 编辑消息
  const handleEditMessage = useCallback(async (messageId: string, content: string) => {
    if (!currentChannel) return;
    try {
      await editMsgFn(messageId, content);
      refetchMessages();
    } catch (e) {
      console.error('编辑消息失败', e);
    }
  }, [currentChannel, refetchMessages]);

  // 删除消息
  const handleDeleteMessage = useCallback(async (messageId: string) => {
    if (!currentChannel) return;
    try {
      await deleteMsg(messageId);
      refetchMessages();
    } catch (e) {
      console.error('删除消息失败', e);
    }
  }, [currentChannel, refetchMessages]);

  // 新建频道
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

  // WebSocket 实时订阅
  useEffect(() => {
    if (!user || !currentChannel) return;

    const unsub = subscribeToChannel(currentChannel.id, (payload) => {
      try {
        const msg = JSON.parse(payload) as ImMessage;
        // 如果是软删除更新,直接 refetch
        if (msg.deletedAt) {
          refetchMessages();
          return;
        }
        // 避免重复
        setThreadMessage?.((prev) => prev?.id === msg.id ? null : prev);
        // 将实时消息合并到当前列表(避免闪烁,后续可优化为局部更新)
        refetchMessages();
      } catch (e) {
        console.error('解析消息失败', e);
      }
    });

    return () => {
      unsub();
    };
  }, [currentChannel?.id, user?.id, refetchMessages, setThreadMessage]);

  // 清理 WS 连接(页面卸载时)
  useEffect(() => {
    return () => {
      disconnectStomp();
    };
  }, []);

  return (
    <div
      style={{
        display: 'flex',
        height: 'calc(100vh - 80px)',
        background: '#ffffff',
        borderRadius: 8,
        overflow: 'hidden',
        boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
      }}
    >
      <ChannelList
        channels={channels}
        selectedChannel={currentChannel}
        onSelect={handleChannelSelect}
        onRefresh={refetchChannels}
        onCreateChannel={() => setShowCreateDialog(true)}
        currentUser={user}
        getUnreadCount={getUnreadCount}
      />

      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          flex: 1,
          minWidth: 0,
        }}
      >
        <div
          style={{
            padding: '12px 16px',
            borderBottom: '1px solid #e2e8f0',
            background: '#f8fafc',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <div>
            <h3 style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>
              {currentChannel?.name || '选择频道'}
            </h3>
            <div style={{ fontSize: 12, color: '#64748b' }}>
              {currentChannel?.topic || ''}
            </div>
          </div>
          <div style={{ fontSize: 12, color: '#64748b' }}>在线</div>
        </div>

        <div
          style={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            minHeight: 0,
          }}
        >
          {currentChannel && user?.id ? (
            <MessageList
              currentUserId={user.id}
              messages={messages}
              onMessageClick={handleMessageClick}
              canLoadMore={hasNextPage}
              isLoadingMore={isFetchingNextPage}
              onLoadMore={() => fetchNextPage()}
              onEditMessage={handleEditMessage}
              onDeleteMessage={handleDeleteMessage}
            />
          ) : (
            <div
              style={{
                flex: 1,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#94a3b8',
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
                // MessageComposer 已自己调用 sendMessage，这里只需追加到列表
                if (message?.id) {
                  refetchMessages();
                }
              }}
              disabled={!user}
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
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.4)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
          }}
          onClick={() => setShowCreateDialog(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              background: '#ffffff',
              borderRadius: 8,
              padding: 20,
              width: 320,
              boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
            }}
          >
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>新建频道</h3>
            <input
              value={newChannelName}
              onChange={(e) => setNewChannelName(e.target.value)}
              placeholder="频道名称"
              autoFocus
              style={{
                width: '100%',
                boxSizing: 'border-box',
                padding: '8px 12px',
                border: '1px solid #e2e8f0',
                borderRadius: 6,
                fontSize: 13,
                outline: 'none',
              }}
            />
            <div style={{ display: 'flex', gap: 8, marginTop: 12, justifyContent: 'flex-end' }}>
              <button
                onClick={() => setShowCreateDialog(false)}
                style={{
                  padding: '6px 12px',
                  border: '1px solid #e2e8f0',
                  background: '#fff',
                  borderRadius: 6,
                  cursor: 'pointer',
                  fontSize: 12,
                }}
              >
                取消
              </button>
              <button
                onClick={handleCreateChannel}
                disabled={!newChannelName.trim()}
                style={{
                  padding: '6px 12px',
                  background: newChannelName.trim() ? '#3b82f6' : '#cbd5e1',
                  color: '#fff',
                  border: 'none',
                  borderRadius: 6,
                  cursor: newChannelName.trim() ? 'pointer' : 'not-allowed',
                  fontSize: 12,
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
