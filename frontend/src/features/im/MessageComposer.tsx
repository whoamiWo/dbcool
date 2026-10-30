import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useIsMobile } from '@/hooks/useIsMobile';
import { sendMessage, uploadAttachment, listSlashCommands, listChannelMembers, type SlashCommand } from './api';

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
      aria-label={t('im.slashCommandList')}
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
        // eslint-disable-next-line jsx-a11y/no-autofocus -- 弹窗/面板内首个输入框自动聚焦，符合交互预期
        autoFocus
        value={filter}
        onChange={(e) => setFilter(e.currentTarget.value)}
        placeholder={t('im.searchCommand')}
        className="input-glass"
        style={{ width: '100%', padding: '6px 8px', marginBottom: 4 }}
      />
      {filtered.length === 0 ? (
        <div style={{ fontSize: 13, color: 'var(--color-text-muted)', padding: '8px 0' }}>{t('im.noMatchCommand')}</div>
      ) : (
        filtered.map((cmd) => (
          <div
            key={cmd.name}
            role="option"
            // PHASE98 R4: role="option" 必须有 aria-selected；该列表无键盘选中态，故恒为 false
            aria-selected={false}
            aria-label={cmd.name}
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
  // Wave4 待办池 #7：@提及 —— 输入 @ 触发成员候选，选中插入后端可解析的结构化语法 @{displayName}:userId
  const [mentionOpen, setMentionOpen] = useState(false);
  const [mentionQuery, setMentionQuery] = useState('');
  const [mentionStart, setMentionStart] = useState(-1); // '@' 在 content 中的下标
  const [mentionIdx, setMentionIdx] = useState(0);      // 键盘选中索引
  const [members, setMembers] = useState<Array<{ userId: string; displayName: string }>>([]);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  // Wave4 #7：成员只按 channelId 拉一次（避免渲染期请求造成无限循环）
  const membersLoadedFor = useRef<string>('');
  useEffect(() => {
    if (!channelId || disabled) return;
    if (membersLoadedFor.current === channelId) return;
    membersLoadedFor.current = channelId;
    listChannelMembers(channelId)
      .then((res) => { if (res.code === 0) setMembers((res.data ?? []).filter((m) => !!m.displayName)); })
      .catch(console.error);
  }, [channelId, disabled]);
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

  const mentionMatches = members
    .filter((m) => m.displayName.toLowerCase().includes(mentionQuery.toLowerCase()))
    .slice(0, 8);

  const applyMention = (m: { userId: string; displayName: string }) => {
    const ta = textareaRef.current;
    if (!ta) return;
    const pos = content.length;
    const inserted = `@{${m.displayName}}:${m.userId} `;
    const next = content.slice(0, mentionStart) + inserted + content.slice(pos);
    setContent(next);
    setMentionOpen(false);
    // 光标落在插入文本之后
    requestAnimationFrame(() => {
      const np = mentionStart + inserted.length;
      ta.selectionStart = ta.selectionEnd = np;
      ta.focus();
    });
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    // @提及候选打开时接管按键
    if (mentionOpen && mentionMatches.length > 0) {
      if (e.key === 'ArrowDown') { e.preventDefault(); setMentionIdx((i) => (i + 1) % mentionMatches.length); return; }
      if (e.key === 'ArrowUp') { e.preventDefault(); setMentionIdx((i) => (i - 1 + mentionMatches.length) % mentionMatches.length); return; }
      if (e.key === 'Escape') { e.preventDefault(); setMentionOpen(false); return; }
      if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
        e.preventDefault();
        applyMention(mentionMatches[mentionIdx]);
        return;
      }
    }
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

    // Wave4 #7：@提及候选 —— '@' 须位于行首或空白后（邮箱等误触不算）
    // 以文本末尾为检测位置（用户边输入边选择的典型场景；不依赖 selectionStart，
    // 在部分测试环境/输入法组合下 selectionStart 不可靠）
    const pos = target.value.length;
    const before = target.value.slice(0, pos);
    const atIdx = before.lastIndexOf('@');
    if (atIdx >= 0 && (atIdx === 0 || /\s/.test(before[atIdx - 1]))) {
      const q = before.slice(atIdx + 1);
      if (!/\s$/.test(q)) {
        setMentionOpen(true);
        setMentionStart(atIdx);
        setMentionQuery(q);
        setMentionIdx(0);
        return;
      }
    }
    if (mentionOpen) setMentionOpen(false);
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
      {mentionOpen && (
        <div
          role="listbox"
          aria-label={t('im.mentionSuggestions')}
          className="glass-strong"
          style={{
            marginBottom: 4, borderRadius: 8, overflow: 'hidden',
            maxHeight: 200, overflowY: 'auto', boxShadow: '0 4px 16px rgba(0,0,0,0.12)',
          }}
        >
          {mentionMatches.length === 0 ? (
            <div style={{ padding: 8, fontSize: 12, color: 'var(--color-text-muted)' }}>
              {t('im.mentionNoMatch')}
            </div>
          ) : mentionMatches.map((m, i) => (
            <div
              key={m.userId}
              role="option"
              tabIndex={-1}
              aria-selected={i === mentionIdx}
              onMouseDown={(e) => { e.preventDefault(); applyMention(m); }}
              style={{
                padding: '6px 10px', cursor: 'pointer', fontSize: 13,
                background: i === mentionIdx ? 'var(--color-bg-hover)' : 'transparent',
              }}
            >
              @{m.displayName}
            </div>
          ))}
        </div>
      )}
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
        role="presentation"
        aria-label={t('im.inputAreaHint')}
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
            title={t('im.uploadAttachmentHint')}
            aria-label={t('im.uploadAttachment')}
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
            aria-label={t('im.inputMessage')}
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
            aria-label={t('im.sendMessage')}
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
