import { useCallback, useEffect, useRef, useState } from 'react';
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

  const refresh = useCallback(async () => {
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
  }, [channelId]);

  useEffect(() => {
    mountedRef.current = true;
  // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：异步/条件/初始化模式，非连续渲染
    refresh();
    return () => { mountedRef.current = false; };
  }, [refresh]);

  const handlePin = async (messageId: string, alreadyPinned?: boolean) => {
    try {
      if (alreadyPinned) {
        await unpinMessage(channelId, messageId);
      } else {
        await pinMessage(channelId, messageId);
      }
      await refresh();
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
    <div style={{ padding: 8 }}>
      {loading && <div style={{ fontSize: 13, color: 'var(--color-text-muted)' }}>加载中...</div>}
      {!loading && pins.length === 0 && (
        <div style={{ fontSize: 13, color: 'var(--color-text-muted)' }}>暂无置顶消息</div>
      )}
      <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {pins.map((pin) => (
          <li
            key={pin.id}
            style={{
              padding: '6px 8px',
              borderRadius: 6,
              marginBottom: 6,
              backgroundColor: 'rgba(255, 255, 255, 0.04)',
              border: '1px solid rgba(255, 255, 255, 0.06)',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <span style={{ fontSize: 13 }}>消息 ID: {pin.messageId}</span>
              <button
                onClick={() => handlePin(pin.messageId, true)}
                style={{
                  marginLeft: 8,
                  padding: '2px 8px',
                  fontSize: 12,
                  borderRadius: 4,
                  border: 'none',
                  cursor: 'pointer',
                  backgroundColor: 'rgba(255, 255, 255, 0.08)',
                  color: 'inherit',
                }}
              >
                取消置顶
              </button>
            </div>
            <div style={{ fontSize: 11, color: 'var(--color-text-muted)', marginTop: 2 }}>
              by {pin.pinnedBy} · {new Date(pin.pinnedAt).toLocaleString()}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}