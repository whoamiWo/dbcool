import { useEffect, useRef, useState } from 'react';
import { listPins, pinMessage, unpinMessage, type ImPin } from './api';
import { ImChannel } from './api';

interface PinListProps {
  channelId: string;
  channel: ImChannel | null;
  onChanged?: () => void;
}

export function PinList({ channelId, channel, onChanged }: PinListProps) {
  const [pins, setPins] = useState<ImPin[]>([]);
  const [loading, setLoading] = useState(false);
  const mountedRef = useRef(false);

  const load = async () => {
    if (!channelId) return;
    setLoading(true);
    try {
      const res = await listPins(channelId);
      if (mountedRef.current) setPins(Array.isArray(res.data) ? res.data : []);
    } catch (err) {
      console.error('加载置顶消息失败', err);
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    load();
    return () => { mountedRef.current = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channelId]);

  const handlePin = async (messageId: string, alreadyPinned?: boolean) => {
    try {
      if (alreadyPinned) {
        await unpinMessage(messageId);
      } else {
        await pinMessage(messageId);
      }
      await load();
      onChanged?.();
    } catch (err) {
      console.error('置顶操作失败', err);
    }
  };

  if (!channel) {
    return (
      <div style={{ padding: 16, color: 'var(--color-text-muted)', fontSize: 13, textAlign: 'center' }}>
        请先选择频道
      </div>
    );
  }

  return (
    <div style={{ padding: 12 }}>
      <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8, color: 'var(--color-text-primary)' }}>
        📌 置顶消息 ({pins.length})
      </div>
      {loading ? (
        <div style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>加载中...</div>
      ) : pins.length === 0 ? (
        <div style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>暂无置顶消息</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {pins.map((pin) => (
            <div
              key={pin.id}
              className="glass-light"
              style={{ padding: '8px 10px', borderRadius: 'var(--radius-md)', fontSize: 12 }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ color: 'var(--color-warning)', fontWeight: 500 }}>消息 #{pin.messageId.slice(-6)}</span>
                <button
                  onClick={() => handlePin(pin.messageId, pin.pinned)}
                  style={{
                    background: 'none',
                    border: 'none',
                    cursor: 'pointer',
                    fontSize: 11,
                    color: 'var(--color-error)',
                    padding: '2px 6px',
                    borderRadius: 'var(--radius-sm)',
                    transition: 'all var(--transition-fast)',
                  }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(239,68,68,0.1)'; }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent'; }}
                  title="取消置顶"
                >
                  ✕
                </button>
              </div>
              <div style={{ color: 'var(--color-text-muted)', fontSize: 11, marginTop: 4 }}>
                {new Date(pin.pinnedAt).toLocaleString('zh-CN')}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
