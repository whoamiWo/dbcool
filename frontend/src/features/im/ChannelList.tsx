import { useEffect, useMemo, useState } from 'react';
import { getUnreadCount as getUnreadCountApi, type ImChannel } from './api';
import type { User } from '@/stores/auth';

interface ChannelListProps {
  channels: ImChannel[];
  selectedChannel: ImChannel | null;
  onSelect: (channel: ImChannel) => void;
  onRefresh: () => void;
  onCreateChannel: () => void;
  currentUser: User | null;
  getUnreadCount: typeof getUnreadCountApi;
}

/** R3：按频道属性分组（组标题与卡片内「群聊/私聊」文案刻意区分，避免文本歧义） */
const GROUP_DEFS = [
  { key: 'PUBLIC', label: '公开频道' },
  { key: 'PRIVATE', label: '私有频道' },
] as const;

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
  /** R3：分组折叠状态，默认全部展开 */
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const entries = await Promise.all(
        channels.map(async (c) => {
          try {
            const res = await getUnreadCount(c.id);
            return [c.id, res.data?.unread_count ?? 0] as const;
          } catch {
            return [c.id, 0] as const;
          }
        }),
      );
      if (!cancelled) setUnreadMap(Object.fromEntries(entries));
    })();
    return () => { cancelled = true; };
  }, [channels, getUnreadCount]);

  const groups = useMemo(
    () =>
      GROUP_DEFS.map((def) => ({
        ...def,
        items: channels.filter((c) =>
          def.key === 'PRIVATE' ? c.type === 'PRIVATE' : c.type !== 'PRIVATE',
        ),
      })).filter((g) => g.items.length > 0),
    [channels],
  );

  const toggleGroup = (key: string) => {
    setCollapsed((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  const renderChannel = (channel: ImChannel) => {
    const active = selectedChannel?.id === channel.id;
    const display = channel.name || channel.topic || '未命名频道';
    const unread = unreadMap[channel.id] ?? 0;
    return (
      <div
        key={channel.id}
        onClick={() => onSelect(channel)}
        className={`channel-item ${active ? 'channel-item-active' : ''}`}
        style={{
          transition: 'all var(--transition-fast)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 6 }}>
          <span
            style={{
              fontSize: 13,
              fontWeight: active ? 600 : 400,
              color: active ? 'var(--color-primary-400)' : 'var(--color-text-secondary)',
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
                background: 'var(--color-error)',
                color: 'var(--color-text-primary)',
                borderRadius: 'var(--radius-full)',
                minWidth: 18,
                height: 18,
                padding: '0 6px',
                fontSize: 11,
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0,
                boxShadow: 'var(--shadow-sm)',
              }}
            >
              {unread > 99 ? '99+' : unread}
            </span>
          )}
        </div>
        <div style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
          {channel.type === 'PRIVATE' ? '私聊' : '群聊'}
        </div>
      </div>
    );
  };

  return (
    <div className="im-sidebar">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <strong style={{ fontSize: 14, color: 'var(--color-text-primary)' }}>对话</strong>
        <div style={{ display: 'flex', gap: 4 }}>
          <button
            onClick={onRefresh}
            style={{
              border: 'none',
              background: 'transparent',
              cursor: 'pointer',
              fontSize: 12,
              color: 'var(--color-text-muted)',
              padding: '4px 8px',
              borderRadius: 'var(--radius-sm)',
              transition: 'all var(--transition-fast)',
            }}
            onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.color = 'var(--color-text-primary)'; }}
            onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.color = 'var(--color-text-muted)'; }}
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
              color: 'var(--color-primary-400)',
              padding: '4px 8px',
              borderRadius: 'var(--radius-sm)',
              transition: 'all var(--transition-fast)',
            }}
            onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(99,102,241,0.1)'; }}
            onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent'; }}
            title="新建频道"
          >
            新建
          </button>
        </div>
      </div>

      {currentUser && (
        <div style={{ fontSize: 11, color: 'var(--color-text-muted)', marginBottom: 8, padding: '4px 8px', background: 'var(--glass-bg-light)', borderRadius: 'var(--radius-sm)' }}>
          🧑 当前: {currentUser.username}
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {groups.map((group) => {
          const isCollapsed = collapsed[group.key] ?? false;
          return (
            <div key={group.key}>
              <button
                onClick={() => toggleGroup(group.key)}
                aria-expanded={!isCollapsed}
                title={isCollapsed ? '展开分组' : '折叠分组'}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  width: '100%',
                  padding: '6px 8px',
                  border: 'none',
                  background: 'transparent',
                  cursor: 'pointer',
                  fontSize: 11,
                  color: 'var(--color-text-muted)',
                  textAlign: 'left',
                  borderRadius: 'var(--radius-sm)',
                  transition: 'all var(--transition-fast)',
                }}
                onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.04)'; }}
                onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent'; }}
              >
                <span style={{ fontSize: 10 }}>{isCollapsed ? '▶' : '▼'}</span>
                <span>{group.label} ({group.items.length})</span>
              </button>
              {!isCollapsed && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4, paddingLeft: 4 }}>
                  {group.items.map(renderChannel)}
                </div>
              )}
            </div>
          );
        })}
        {channels.length === 0 && (
          <div style={{ padding: 12, fontSize: 12, color: 'var(--color-text-muted)', textAlign: 'center' }}>
            暂无频道
          </div>
        )}
      </div>
    </div>
  );
}
