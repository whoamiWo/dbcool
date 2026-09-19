import { useState, useRef } from 'react';
import { sendMessage, uploadAttachment, listSlashCommands, type SlashCommand } from './api';

interface MessageComposerProps {
  channelId: string;
  onSent: (message: any) => void;
  disabled?: boolean;
  /** F4：拖拽上传回调（返回上传后的 attachment URL） */
  onAttachment?: (url: string, filename: string, size: number) => void;
}

/** F4：Slash 命令面板 */
const SlashPanel = ({
  commands, onSelect, onClose, position,
}: {
  commands: SlashCommand[];
  onSelect: (cmd: SlashCommand) => void;
  onClose?: () => void;
  position: { top: number; left: number };
}) => {
  void onClose;
  const [filter, setFilter] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  const filtered = commands.filter((c) =>
    c.name.toLowerCase().includes(filter.toLowerCase())
    || c.description.toLowerCase().includes(filter.toLowerCase()),
  );

  return (
    <div
      ref={listRef}
      role="listbox"
      aria-label="Slash 命令列表"
      style={{
        position: 'fixed',
        top: position.top,
        left: position.left,
        zIndex: 1000,
        minWidth: 240,
        maxWidth: 320,
        background: '#fff',
        border: '1px solid #e2e8f0',
        borderRadius: 8,
        boxShadow: '0 4px 12px rgba(0,0,0,0.12)',
        padding: 8,
        maxHeight: 240,
        overflowY: 'auto',
      }}
    >
      <input
        autoFocus
        value={filter}
        onChange={(e) => setFilter(e.currentTarget.value)}
        placeholder="搜索命令..."
        style={{
          width: '100%',
          padding: '6px 8px',
          border: '1px solid #e2e8f0',
          borderRadius: 4,
          fontSize: 13,
          marginBottom: 4,
          outline: 'none',
        }}
      />
      {filtered.length === 0 ? (
        <div style={{ fontSize: 13, color: '#94a3b8', padding: '8px 0' }}>无匹配命令</div>
      ) : (
        filtered.map((cmd) => (
          <div
            key={cmd.name}
            role="option"
            tabIndex={0}
            onClick={() => onSelect(cmd)}
            onKeyDown={(e) => { if (e.key === 'Enter') onSelect(cmd); }}
            style={{
              padding: '8px 10px',
              cursor: 'pointer',
              borderRadius: 4,
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              fontSize: 13,
            }}
            onMouseEnter={(e) => { (e.currentTarget as HTMLDivElement).style.background = '#f1f5f9'; }}
            onMouseLeave={(e) => { (e.currentTarget as HTMLDivElement).style.background = 'transparent'; }}
          >
            <span style={{ fontWeight: 500, color: '#1d4ed8' }}>{cmd.name}</span>
            <span style={{ color: '#64748b', fontSize: 12 }}>{cmd.description}</span>
          </div>
        ))
      )}
      <div
        style={{ fontSize: 11, color: '#94a3b8', padding: '4px 8px', borderTop: '1px solid #e2e8f0', marginTop: 4 }}
      >
        ↑↓ 选择 · Enter 确认 · Esc 关闭
      </div>
    </div>
  );
};

export function MessageComposer({ channelId, onSent, disabled, onAttachment }: MessageComposerProps) {
  const [content, setContent] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [slashOpen, setSlashOpen] = useState(false);
  const [slashPos, setSlashPos] = useState({ top: 0, left: 0 });
  const [slashCommands, setSlashCommands] = useState<SlashCommand[]>([]);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // F4：加载 Slash 命令列表
  if (!slashCommands.length) {
    listSlashCommands()
      .then((res) => { if (res.code === 0) setSlashCommands(res.data ?? []); })
      .catch(console.error);
  }

  const handleSend = async () => {
    if (!content.trim() || isSending || disabled) return;
    setIsSending(true);
    try {
      const res = await sendMessage(channelId, content.trim(), 'TEXT');
      onSent(res.data);
      setContent('');
      if (textareaRef.current) textareaRef.current.style.height = 'auto';
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

    // F4：检测到斜杠弹出命令面板
    if (e.target.value === '/') {
      const rect = target.getBoundingClientRect();
      setSlashPos({ top: rect.bottom + 4, left: rect.left });
      setSlashOpen(true);
    }
  };

  // F4：点击外部关闭 Slash 面板
  const panelRef = useRef<HTMLDivElement>(null);
  useRef(() => {
    const handler = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        setSlashOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  });

  const handleSlashSelect = (cmd: SlashCommand) => {
    setContent(`/${cmd.name} `);
    setSlashOpen(false);
    if (textareaRef.current) textareaRef.current.focus();
  };

  // F4：文件拖拽上传
  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    const files = Array.from(e.dataTransfer.files);
    for (const file of files) {
      if (file.size > 50 * 1024 * 1024) {
        alert('文件大小不能超过 50MB');
        continue;
      }
      try {
        const res = await uploadAttachment(file);
        if (res.code === 0 && res.data?.url) {
          onAttachment?.(res.data.url, file.name, file.size);
        }
      } catch (err) {
        console.error('上传失败', err);
        alert('上传失败，请重试');
      }
    }
  };

  return (
    <>
      {slashOpen && (
        <div ref={panelRef}>
          <SlashPanel
            commands={slashCommands}
            onSelect={handleSlashSelect}
            onClose={() => setSlashOpen(false)}
            position={slashPos}
          />
        </div>
      )}
      <div
        onDragOver={(e) => e.preventDefault()}
        onDrop={handleDrop}
        style={{
          padding: 12,
          borderTop: '1px solid #e2e8f0',
          background: '#f8fafc',
          minHeight: 80,
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
          {/* F4：拖拽上传按钮 */}
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            title="拖拽或点击上传文件（≤50MB）"
            style={{
              padding: '8px 12px',
              background: '#f1f5f9',
              border: '1px solid #e2e8f0',
              borderRadius: 6,
              cursor: disabled ? 'not-allowed' : 'pointer',
              fontSize: 13,
              color: '#475569',
            }}
            disabled={disabled}
          >
            📎
          </button>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept="image/*,.pdf,.doc,.docx,.xls,.xlsx"
            style={{ display: 'none' }}
            onChange={(e) => {
              const files = Array.from(e.target.files || []);
              for (const file of files) {
                uploadAttachment(file)
                  .then((res) => {
                    if (res.code === 0 && res.data?.url) onAttachment?.(res.data.url, file.name, file.size);
                  })
                  .catch(console.error);
              }
              e.target.value = '';
            }}
          />
          <textarea
            ref={textareaRef}
            value={content}
            onChange={handleInput}
            onKeyDown={handleKeyDown}
            placeholder={disabled ? '请选择频道以发送消息' : '输入消息...  (输入 / 打开命令面板)'}
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
          按 Enter 发送，Shift + Enter 换行 · 拖拽文件到此处上传
        </div>
      </div>
    </>
  );
}
