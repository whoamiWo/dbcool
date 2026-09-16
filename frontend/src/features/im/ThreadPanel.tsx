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
      <div
        style={{
          width: 280,
          borderLeft: '1px solid #e2e8f0',
          background: '#f8fafc',
          padding: 16,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          color: '#94a3b8',
          fontSize: 13,
          textAlign: 'center',
        }}
      >
        选择一条消息查看线程回复
      </div>
    );
  }

  return (
    <div
      style={{
        width: 280,
        borderLeft: '1px solid #e2e8f0',
        background: '#ffffff',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <div
        style={{
          padding: 12,
          borderBottom: '1px solid #e2e8f0',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <strong style={{ fontSize: 13 }}>线程回复</strong>
        <button
          onClick={onClose}
          style={{
            border: 'none',
            background: 'transparent',
            cursor: 'pointer',
            fontSize: 16,
            color: '#64748b',
          }}
        >
          ×
        </button>
      </div>

      <div style={{ padding: 12, borderBottom: '1px solid #e2e8f0', background: '#f1f5f9' }}>
        <div style={{ fontSize: 11, color: '#64748b', marginBottom: 4 }}>原消息</div>
        <div style={{ fontSize: 13, color: '#0f172a' }}>
          {parentMessage.deletedAt ? '该消息已删除' : parentMessage.content}
        </div>
        <div style={{ fontSize: 10, color: '#94a3b8', marginTop: 4 }}>
          {formatTime(parentMessage.createdAt)}
        </div>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: 12 }}>
        {isLoading && (
          <div style={{ textAlign: 'center', padding: 20, fontSize: 12, color: '#64748b' }}>
            加载中...
          </div>
        )}

        {!isLoading && threadMessages.length === 0 && (
          <div style={{ textAlign: 'center', padding: 20, fontSize: 12, color: '#94a3b8' }}>
            暂无回复
          </div>
        )}

        {threadMessages.map((msg) => (
          <div key={msg.id} style={{ marginBottom: 12 }}>
            <div
              style={{
                padding: 8,
                borderRadius: 6,
                background: '#f8fafc',
              }}
            >
              <div style={{ fontSize: 11, color: '#64748b', marginBottom: 2 }}>
                用户 {msg.senderId.substring(0, 8)}
              </div>
              <div style={{ fontSize: 13, color: '#0f172a' }}>
                {msg.deletedAt ? '该消息已删除' : msg.content}
              </div>
            </div>
            <div style={{ fontSize: 10, color: '#94a3b8', marginTop: 2 }}>
              {formatTime(msg.createdAt)}
            </div>
          </div>
        ))}
      </div>

      {/* 线程回复输入框 */}
      <div
        style={{
          padding: 12,
          borderTop: '1px solid #e2e8f0',
          background: '#f8fafc',
        }}
      >
        <div
          style={{
            display: 'flex',
            gap: 8,
            alignItems: 'flex-end',
            padding: 8,
            background: '#ffffff',
            borderRadius: 8,
            border: '1px solid #e2e8f0',
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
            style={{
              flex: 1,
              minHeight: 36,
              border: 'none',
              outline: 'none',
              fontSize: 13,
              fontFamily: 'inherit',
              background: 'transparent',
            }}
          />
          <button
            onClick={() => {
              handleSendReply(replyDraft);
              setReplyDraft('');
            }}
            disabled={!replyDraft.trim() || isSending}
            style={{
              padding: '6px 12px',
              background: !replyDraft.trim() || isSending ? '#cbd5e1' : '#3b82f6',
              color: 'white',
              border: 'none',
              borderRadius: 6,
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
