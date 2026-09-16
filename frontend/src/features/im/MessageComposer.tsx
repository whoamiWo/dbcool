import { useState, useRef } from 'react';
import { sendMessage } from './api';

interface MessageComposerProps {
  channelId: string;
  onSent: (message: any) => void;
  disabled?: boolean;
}

export function MessageComposer({ channelId, onSent, disabled }: MessageComposerProps) {
  const [content, setContent] = useState('');
  const [isSending, setIsSending] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleSend = async () => {
    if (!content.trim() || isSending || disabled) return;

    setIsSending(true);
    try {
      const res = await sendMessage(channelId, content.trim(), 'TEXT');
      onSent(res.data);
      setContent('');
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
    } catch (error) {
      console.error('发送消息失败', error);
      alert('发送消息失败，请重试');
    } finally {
      setIsSending(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setContent(e.target.value);
    const target = e.target;
    target.style.height = 'auto';
    target.style.height = target.scrollHeight + 'px';
  };

  return (
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
          padding: 12,
          background: '#ffffff',
          borderRadius: 8,
          border: '1px solid #e2e8f0',
        }}
      >
        <textarea
          ref={textareaRef}
          value={content}
          onChange={handleInput}
          onKeyDown={handleKeyDown}
          placeholder={disabled ? '请选择频道以发送消息' : '输入消息...'}
          disabled={disabled}
          style={{
            flex: 1,
            minHeight: 40,
            maxHeight: 120,
            resize: 'none',
            border: 'none',
            outline: 'none',
            fontSize: 14,
            fontFamily: 'inherit',
            background: 'transparent',
          }}
        />
        <button
          onClick={handleSend}
          disabled={!content.trim() || isSending || disabled}
          style={{
            padding: '8px 16px',
            background: !content.trim() || isSending || disabled ? '#cbd5e1' : '#3b82f6',
            color: 'white',
            border: 'none',
            borderRadius: 6,
            cursor: !content.trim() || isSending || disabled ? 'not-allowed' : 'pointer',
            fontSize: 13,
            fontWeight: 500,
          }}
        >
          {isSending ? '发送中...' : '发送'}
        </button>
      </div>
      <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 4, textAlign: 'right' }}>
        按 Enter 发送，Shift + Enter 换行
      </div>
    </div>
  );
}
