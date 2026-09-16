import { useEffect, useState } from 'react';
import { getUnreadCount as getUnreadCountApi, type ImChannel } from './api';
import type { User } from '@/stores/auth';

interface ChannelListProps {
  channels: ImChannel[];
  selectedChannel: ImChannel | null;
  onSelect: (channel: ImChannel) => void;
  onRefresh: () => void;
  onCreateChannel: () => void;
  currentUser: User | null;
  // 直接复用 api 函数签名,避免手写复杂返回值类型产生偏差
  getUnreadCount: typeof getUnreadCountApi;
}

export function ChannelList({
  channels,
  selectedChannel,
  onSelect,
  onRefresh,
  onCreateChannel,
  currentUser,
  getUnreadCount,
}: ChannelListProps) {
  const [unreadMap, setUnreadMap] = useState<Record<string, number>>({});

  // 拉取各频道未读数。getUnreadCount 是 api 顶层函数、引用稳定,不会造成循环请求。
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const entries = await Promise.all(
        channels.map(async (c) => {
          try {
            const res = await getUnreadCount(c.id);
            // 注意:client.ts 响应拦截器返回 response.data,故此处 res 已是 {code,data},
            // 只需一层 .data(写成 res.data.data 会多一层)
            return [c.id, res.data?.unread_count ?? 0] as const;
          } catch {
            // 单个频道未读拉取失败不应拖垮整个列表
            return [c.id, 0] as const;
          }
        }),
      );
      if (!cancelled) setUnreadMap(Object.fromEntries(entries));
    })();
    return () => {
      cancelled = true;
    };
  }, [channels, getUnreadCount]);

  return (
    <div
      style={{
        width: 240,
        borderRight: '1px solid #e2e8f0',
        overflowY: 'auto',
        background: '#ffffff',
        padding: 12,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <strong style={{ fontSize: 14 }}>对话</strong>
        <div style={{ display: 'flex', gap: 4 }}>
          <button
            onClick={onRefresh}
            style={{
              border: 'none',
              background: 'transparent',
              cursor: 'pointer',
              fontSize: 12,
              color: '#64748b',
            }}
            title="刷新"
          >
            刷新
          </button>
          <button
            onClick={onCreateChannel}
            style={{
              border: 'none',
              background: 'transparent',
              cursor: 'pointer',
              fontSize: 12,
              color: '#2563eb',
            }}
            title="新建频道"
          >
            新建
          </button>
        </div>
      </div>

      {currentUser && (
        <div style={{ fontSize: 11, color: '#94a3b8', marginBottom: 8 }}>
          当前:{currentUser.username}
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {channels.map((channel) => {
          const active = selectedChannel?.id === channel.id;
          const display = channel.name || channel.topic || '未命名频道';
          const unread = unreadMap[channel.id] ?? 0;
          return (
            <div
              key={channel.id}
              onClick={() => onSelect(channel)}
              style={{
                padding: '8px 12px',
                borderRadius: 4,
                cursor: 'pointer',
                background: active ? '#e0f2fe' : 'transparent',
                borderLeft: active ? '3px solid #3b82f6' : '3px solid transparent',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 6 }}>
                <span
                  style={{
                    fontSize: 13,
                    fontWeight: active ? 600 : 400,
                    color: active ? '#0f172a' : '#475569',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {display}
                </span>
                {unread > 0 && (
                  <span
                    style={{
                      background: '#ef4444',
                      color: '#ffffff',
                      borderRadius: 9,
                      minWidth: 18,
                      height: 18,
                      padding: '0 6px',
                      fontSize: 11,
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0,
                    }}
                  >
                    {unread > 99 ? '99+' : unread}
                  </span>
                )}
              </div>
              <div style={{ fontSize: 11, color: '#94a3b8' }}>
                {channel.type === 'PRIVATE' ? '私聊' : '群聊'}
              </div>
            </div>
          );
        })}
        {channels.length === 0 && (
          <div style={{ padding: 12, fontSize: 12, color: '#94a3b8' }}>
            暂无频道
          </div>
        )}
      </div>
    </div>
  );
}
