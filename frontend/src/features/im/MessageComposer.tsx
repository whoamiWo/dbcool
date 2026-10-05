import { useState, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useIsMobile } from '@/hooks/useIsMobile';
import { sendMessage, uploadAttachment, listSlashCommands, type SlashCommand } from './api';

interface MessageComposerProps {
  channelId: string;
  onSent: (message: any) => void;
  disabled?: boolean;
  onAttachment?: (url: string, filename: string, size: number) => void;
}

const SlashPanel = ({
  commands, onSelect, onClose, position,
}: {
  commands: SlashCommand[];
  onSelect: (cmd: SlashCommand) => void;
  onClose?: () => void;
  position: { top: number; left: number };
}) => {
  void onClose;
  // SlashPanel 是独立组件，需自行取 t（父组件的 t 不在此作用域）
  const { t } = useTranslation();
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
      className="glass-strong"
      style={{
        position: 'fixed',
        top: position.top,
        left: position.left,
        zIndex: 1000,
        minWidth: 240,
        maxWidth: 320,
        maxHeight: 240,
        overflowY: 'auto',
        padding: 8,
      }}
    >
      <input
        autoFocus
        value={filter}
        onChange={(e) => setFilter(e.currentTarget.value)}
        placeholder={t('im.searchCommand')}
        className="input-glass"
        style={{ width: '100%', padding: '6px 8px', marginBottom: 4 }}
      />
      {filtered.length === 0 ? (
        <div style={{ fontSize: 13, color: 'var(--color-text-muted)', padding: '8px 0' }}>无匹配命令</div>
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
              borderRadius: 'var(--radius-sm)',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              fontSize: 13,
              transition: 'all var(--transition-fast)',
            }}
            onMouseEnter={(e) => { (e.currentTarget as HTMLDivElement).style.background = 'var(--color-border-light)'; }}
            onMouseLeave={(e) => { (e.currentTarget as HTMLDivElement).style.background = 'transparent'; }}
          >
            <span style={{ fontWeight: 500, color: 'var(--color-primary-400)' }}>{cmd.name}</span>
            <span style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>{cmd.description}</span>
          </div>
        ))
      )}
      <div style={{ fontSize: 11, color: 'var(--color-text-muted)', padding: '4px 8px', borderTop: '1px solid var(--color-border-light)', marginTop: 4 }}>
        {t('im.commandHint')}
      </div>
    </div>
  );
};

export function MessageComposer({ channelId, onSent, disabled, onAttachment }: MessageComposerProps) {
  const { t } = useTranslation();
  const isMobile = useIsMobile();
  const [content, setContent] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [slashOpen, setSlashOpen] = useState(false);
  const [slashPos, setSlashPos] = useState({ top: 0, left: 0 });
  const [slashCommands, setSlashCommands] = useState<SlashCommand[]>([]);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

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
      alert(t('im.sendFailed'));
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

    if (e.target.value === '/') {
      const rect = target.getBoundingClientRect();
      setSlashPos({ top: rect.bottom + 4, left: rect.left });
      setSlashOpen(true);
    }
  };

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

  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    const files = Array.from(e.dataTransfer.files);
    for (const file of files) {
      if (file.size > 50 * 1024 * 1024) {
        alert(t('im.fileTooLarge'));
        continue;
      }
      try {
        const res = await uploadAttachment(file);
        if (res.code === 0 && res.data?.url) {
          onAttachment?.(res.data.url, file.name, file.size);
        }
      } catch (err) {
        console.error('上传失败', err);
        alert(t('im.uploadFailed'));
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
          padding: isMobile ? 8 : 12,
          borderTop: '1px solid var(--color-border-light)',
          background: 'rgba(15, 23, 42, 0.5)',
          backdropFilter: 'blur(10px)',
          minHeight: isMobile ? 56 : 80,
          // 移动端：输入区固定在底部，避开 Home Indicator
          position: isMobile ? 'sticky' : undefined,
          bottom: 0,
          paddingBottom: isMobile ? 'max(8px, env(safe-area-inset-bottom))' : 12,
        }}
      >
        <div
          style={{
            display: 'flex',
            gap: isMobile ? 6 : 8,
            alignItems: 'flex-end',
            padding: isMobile ? 6 : 12,
            background: 'rgba(30, 41, 59, 0.6)',
            backdropFilter: 'blur(10px)',
            borderRadius: 'var(--radius-lg)',
            border: '1px solid var(--color-border-light)',
          }}
        >
          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            title="拖拽或点击上传文件（≤50MB）"
            aria-label="上传附件"
            className="glass-button"
            // 触控区 ≥44px（移动端可点性下限）
            style={{
              padding: isMobile ? '10px 12px' : '8px 12px',
              minWidth: isMobile ? 44 : undefined,
              minHeight: isMobile ? 44 : undefined,
              fontSize: isMobile ? 16 : 13,
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
            placeholder={disabled ? t('im.selectChannelPlaceholder') : t('im.inputPlaceholder')}
            disabled={disabled}
            aria-label="输入消息"
            className="textarea-glass"
            style={{
              fontSize: isMobile ? 16 : 13, // iOS 16px 避免自动缩放
              minHeight: isMobile ? 36 : undefined,
              maxHeight: isMobile ? 120 : undefined,
              padding: isMobile ? '6px 8px' : undefined,
            }}
          />
          <button
            onClick={handleSend}
            disabled={!content.trim() || isSending || disabled}
            aria-label="发送消息"
            className="glass-button-primary"
            // 触控区 ≥44px
            style={{
              padding: isMobile ? '10px 16px' : '8px 16px',
              minWidth: isMobile ? 44 : undefined,
              minHeight: isMobile ? 44 : undefined,
              opacity: !content.trim() || isSending || disabled ? 0.5 : 1,
              cursor: !content.trim() || isSending || disabled ? 'not-allowed' : 'pointer',
              fontSize: isMobile ? 16 : 13,
              fontWeight: 500,
            }}
          >
            {isSending ? t('im.sending') : t('im.send')}
          </button>
        </div>
        <div style={{ fontSize: 11, color: 'var(--color-text-muted)', marginTop: 4, textAlign: 'right' }}>
          {t('im.inputHint')}
        </div>
      </div>
    </>
  );
}
