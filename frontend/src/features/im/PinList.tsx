import { useEffect, useRef, useState } from 'react';
import { listPins, pinMessage, unpinMessage, type ImPin } from './api';
import { ImChannel } from './api';

interface PinListProps {
  channelId: string;
  channel: ImChannel | null;
}

export function PinList({ channelId, channel }: PinListProps) {
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
    } catch (err) {
      console.error('置顶操作失败', err);
    }
  };

  if (!channel) {
    return (
      <div style={{ padding: 16, color: '#94a3b8', fontSize: 13 }}>
        请先选择频道
      </div>
    );
  }

  return (
    <div style={{ padding: 12 }}>
      <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8, color: '#374151' }}>
        📌 置顶消息 ({pins.length})
      </div>
      {loading ? (
        <div style={{ fontSize: 12, color: '#94a3b8' }}>加载中...</div>
      ) : pins.length === 0 ? (
        <div style={{ fontSize: 12, color: '#94a3b8' }}>暂无置顶消息</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {pins.map((pin) => (
            <div
              key={pin.id}
              style={{
                padding: '8px 10px',
                background: '#fffbeb',
                border: '1px solid #fcd34d',
                borderRadius: 6,
                fontSize: 12,
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ color: '#92400e', fontWeight: 500 }}>消息 #{pin.messageId.slice(-6)}</span>
                <button
                  onClick={() => handlePin(pin.messageId, pin.pinned)}
                  style={{
                    background: 'none',
                    border: 'none',
                    cursor: 'pointer',
                    fontSize: 11,
                    color: '#dc2626',
                  }}
                  title="取消置顶"
                >
                  ✕
                </button>
              </div>
              <div style={{ color: '#9ca3af', fontSize: 11, marginTop: 4 }}>
                {new Date(pin.pinnedAt).toLocaleString('zh-CN')}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
