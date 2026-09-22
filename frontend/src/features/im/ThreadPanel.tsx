import { useState, useEffect, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getMessageThread, sendReply, type ImMessage } from './api';

interface ThreadPanelProps {
  channelId: string | null;
  parentMessage: ImMessage | null;
  onClose: () => void;
}

export function ThreadPanel({ channelId, parentMessage, onClose }: ThreadPanelProps) {
  const [threadMessages, setThreadMessages] = useState<ImMessage[]>([]);
  const [isSending, setIsSending] = useState(false);
  const [replyDraft, setReplyDraft] = useState('');

  const { data, isLoading } = useQuery({
    queryKey: ['thread', channelId, parentMessage?.id],
    queryFn: () => getMessageThread(channelId!, parentMessage!.id),
    enabled: !!channelId && !!parentMessage?.id,
  });

  useEffect(() => {
    if (data?.data?.replies) {
      setThreadMessages(data.data.replies);
    }
  }, [data?.data?.replies]);

  const formatTime = (time: string) => {
    const date = new Date(time);
    return date.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const handleSendReply = useCallback(async (content: string) => {
    if (!channelId || !parentMessage) return;
    setIsSending(true);
    try {
      const res = await sendReply(channelId, parentMessage.id, content);
      setThreadMessages(prev => [...prev, res.data]);
    } finally {
      setIsSending(false);
    }
  }, [channelId, parentMessage?.id]);

  if (!parentMessage) {
    return (
      <div className="im-thread-panel" style={{ justifyContent: 'center', alignItems: 'center' }}>
        <div style={{ textAlign: 'center', padding: 16 }}>
          <div style={{ fontSize: 32, marginBottom: 8 }}>💬</div>
          <div style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>
            选择一条消息查看线程回复
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="im-thread-panel">
      <div style={{
        padding: 12,
        borderBottom: '1px solid var(--color-border-light)',
        background: 'var(--color-bg-primary)',
        backdropFilter: 'blur(10px)',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
      }}>
        <strong style={{ fontSize: 13, color: 'var(--color-text-primary)' }}>线程回复</strong>
        <button
          onClick={onClose}
          style={{
            border: 'none',
            background: 'transparent',
            cursor: 'pointer',
            fontSize: 16,
            color: 'var(--color-text-muted)',
            padding: '4px 8px',
            borderRadius: 'var(--radius-sm)',
            transition: 'all var(--transition-fast)',
          }}
          onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.color = 'var(--color-text-primary)'; }}
          onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.color = 'var(--color-text-muted)'; }}
        >
          ×
        </button>
      </div>

      <div style={{
        padding: 12,
        borderBottom: '1px solid var(--color-border-light)',
        background: 'var(--color-bg-secondary)',
        backdropFilter: 'blur(10px)',
        borderRadius: 'var(--radius-md)',
        margin: '0 12px',
      }}>
        <div style={{ fontSize: 11, color: 'var(--color-text-muted)', marginBottom: 4 }}>原消息</div>
        <div style={{ fontSize: 13, color: 'var(--color-text-primary)' }}>
          {parentMessage.deletedAt ? '该消息已删除' : parentMessage.content}
        </div>
        <div style={{ fontSize: 10, color: 'var(--color-text-muted)', marginTop: 4 }}>
          {formatTime(parentMessage.createdAt)}
        </div>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: 12 }}>
        {isLoading && (
          <div style={{ textAlign: 'center', padding: 20, fontSize: 12, color: 'var(--color-text-muted)' }}>
            加载中...
          </div>
        )}

        {!isLoading && threadMessages.length === 0 && (
          <div style={{ textAlign: 'center', padding: 20, fontSize: 12, color: 'var(--color-text-muted)' }}>
            暂无回复
          </div>
        )}

        {threadMessages.map((msg) => (
          <div key={msg.id} style={{ marginBottom: 12 }}>
            <div
              className="message-bubble"
              style={{
                padding: 8,
                borderRadius: 'var(--radius-md)',
                background: 'var(--color-bg-secondary)',
                backdropFilter: 'blur(10px)',
              }}
            >
              <div style={{ fontSize: 11, color: 'var(--color-text-muted)', marginBottom: 2 }}>
                用户 {msg.senderId.substring(0, 8)}
              </div>
              <div style={{ fontSize: 13, color: 'var(--color-text-primary)' }}>
                {msg.deletedAt ? '该消息已删除' : msg.content}
              </div>
            </div>
            <div style={{ fontSize: 10, color: 'var(--color-text-muted)', marginTop: 2 }}>
              {formatTime(msg.createdAt)}
            </div>
          </div>
        ))}
      </div>

      {/* 线程回复输入框 */}
      <div style={{
        padding: 12,
        borderTop: '1px solid var(--color-border-light)',
        background: 'var(--color-bg-primary)',
        backdropFilter: 'blur(10px)',
      }}>
        <div
          style={{
            display: 'flex',
            gap: 8,
            alignItems: 'flex-end',
            padding: 8,
            background: 'var(--color-bg-secondary)',
            backdropFilter: 'blur(10px)',
            borderRadius: 'var(--radius-lg)',
            border: '1px solid var(--color-border-light)',
          }}
        >
          <input
            value={replyDraft}
            onChange={(e) => setReplyDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSendReply(replyDraft);
                setReplyDraft('');
              }
            }}
            placeholder="回复这条消息..."
            disabled={isSending}
            className="input-glass"
            style={{ flex: 1, minHeight: 36, border: 'none', outline: 'none', fontSize: 13, fontFamily: 'inherit', background: 'transparent' }}
          />
          <button
            onClick={() => {
              handleSendReply(replyDraft);
              setReplyDraft('');
            }}
            disabled={!replyDraft.trim() || isSending}
            className="glass-button-primary"
            style={{
              padding: '6px 12px',
              opacity: !replyDraft.trim() || isSending ? 0.5 : 1,
              cursor: !replyDraft.trim() || isSending ? 'not-allowed' : 'pointer',
              fontSize: 12,
              fontWeight: 500,
              flexShrink: 0,
            }}
          >
            {isSending ? '发送中...' : '回复'}
          </button>
        </div>
      </div>
    </div>
  );
}
