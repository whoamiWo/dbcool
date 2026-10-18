import { useState, useCallback, useRef, useEffect } from 'react';
import {
  Box,
  Button,
  IconButton,
  Typography,
  Paper,
  Divider,
  Tooltip,
  TextField,
  Popover,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Chip,
  Alert,
} from '@mui/material';
import {
  FormatBold,
  FormatItalic,
  StrikethroughS,
  Code,
  FormatListBulleted,
  FormatListNumbered,
  FormatQuote,
  FormatSize,
  Title,
  HorizontalRule,
  Link,
  Lightbulb,
  CheckBox,
  CheckBoxOutlineBlank,
  DeleteSweep,
  DragIndicator,
} from '@mui/icons-material';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { useTranslation } from 'react-i18next';
import type { WikiPage } from '@/types/wiki';

// ============================================================
//  块类型枚举
// ============================================================
type BlockType =
  | 'paragraph'
  | 'heading1'
  | 'heading2'
  | 'heading3'
  | 'bullet'
  | 'numbered'
  | 'todo'
  | 'quote'
  | 'code'
  | 'divider'
  | 'callout';

interface Block {
  id: string;
  type: BlockType;
  content: string;
  checked?: boolean; // for todo
  language?: string; // for code
}

// ============================================================
//  斜杠命令菜单项（t: 翻译函数，由组件内传入）
// ============================================================
function getSlashCommands(t: (key: string) => string): { label: string; type: BlockType; icon: React.ReactNode; description: string }[] {
  return [
    { label: t('editor.block.paragraph'), type: 'paragraph', icon: <Title fontSize="small" />, description: t('editor.block.desc.paragraph') },
    { label: t('editor.block.heading1'), type: 'heading1', icon: <FormatSize fontSize="small" />, description: t('editor.block.desc.heading1') },
    { label: t('editor.block.heading2'), type: 'heading2', icon: <FormatSize fontSize="small" />, description: t('editor.block.desc.heading2') },
    { label: t('editor.block.heading3'), type: 'heading3', icon: <FormatSize fontSize="small" />, description: t('editor.block.desc.heading3') },
    { label: t('editor.block.bullet'), type: 'bullet', icon: <FormatListBulleted fontSize="small" />, description: t('editor.block.desc.bullet') },
    { label: t('editor.block.numbered'), type: 'numbered', icon: <FormatListNumbered fontSize="small" />, description: t('editor.block.desc.numbered') },
    { label: t('editor.block.todo'), type: 'todo', icon: <CheckBoxOutlineBlank fontSize="small" />, description: t('editor.block.desc.todo') },
    { label: t('editor.block.quote'), type: 'quote', icon: <FormatQuote fontSize="small" />, description: t('editor.block.desc.quote') },
    { label: t('editor.block.code'), type: 'code', icon: <Code fontSize="small" />, description: t('editor.block.desc.code') },
    { label: t('editor.block.divider'), type: 'divider', icon: <HorizontalRule fontSize="small" />, description: t('editor.block.desc.divider') },
    { label: t('editor.block.callout'), type: 'callout', icon: <Lightbulb fontSize="small" />, description: t('editor.block.desc.callout') },
  ];
}

// ============================================================
//  工具函数
// ============================================================
function generateId(): string {
  return 'b_' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
}

function blockToMarkdown(blocks: Block[]): string {
  return blocks.map(b => {
    switch (b.type) {
      case 'heading1': return `# ${b.content}`;
      case 'heading2': return `## ${b.content}`;
      case 'heading3': return `### ${b.content}`;
      case 'bullet': return `- ${b.content}`;
      case 'numbered': return `1. ${b.content}`;
      case 'todo': return `- [${b.checked ? 'x' : ' '}] ${b.content}`;
      case 'quote': return `> ${b.content}`;
      case 'code': return `\`\`\`${b.language || ''}\n${b.content}\n\`\`\``;
      case 'divider': return `---`;
      case 'callout': return `> 💡 ${b.content}`;
      default: return b.content;
    }
  }).join('\n\n');
}

function markdownToBlocks(md: string): Block[] {
  const lines = md.split('\n');
  const blocks: Block[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    // 代码块
    if (line.startsWith('```')) {
      const lang = line.slice(3).trim();
      const codeLines: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) {
        codeLines.push(lines[i]);
        i++;
      }
      blocks.push({ id: generateId(), type: 'code', content: codeLines.join('\n'), language: lang || 'text' });
      i++; // skip closing ```
      continue;
    }
    // 分割线
    if (/^---+$/.test(line.trim())) {
      blocks.push({ id: generateId(), type: 'divider', content: '' });
      i++;
      continue;
    }
    // 标题
    if (line.startsWith('# ')) {
      blocks.push({ id: generateId(), type: 'heading1', content: line.slice(2) });
      i++;
      continue;
    }
    if (line.startsWith('## ')) {
      blocks.push({ id: generateId(), type: 'heading2', content: line.slice(3) });
      i++;
      continue;
    }
    if (line.startsWith('### ')) {
      blocks.push({ id: generateId(), type: 'heading3', content: line.slice(4) });
      i++;
      continue;
    }
    // 引用
    if (line.startsWith('> ')) {
      const quoteText = line.slice(2);
      if (quoteText.startsWith('💡 ')) {
        blocks.push({ id: generateId(), type: 'callout', content: quoteText.slice(2) });
      } else {
        blocks.push({ id: generateId(), type: 'quote', content: quoteText });
      }
      i++;
      continue;
    }
    // 待办
    if (line.startsWith('- [ ] ') || line.startsWith('- [x] ')) {
      const checked = line.startsWith('- [x] ');
      blocks.push({ id: generateId(), type: 'todo', content: line.slice(6), checked });
      i++;
      continue;
    }
    // 无序列表
    if (line.startsWith('- ')) {
      blocks.push({ id: generateId(), type: 'bullet', content: line.slice(2) });
      i++;
      continue;
    }
    // 有序列表
    if (/^\d+\.\s/.test(line)) {
      blocks.push({ id: generateId(), type: 'numbered', content: line.replace(/^\d+\.\s/, '') });
      i++;
      continue;
    }
    // 空行 → 段落
    if (line.trim() === '') {
      i++;
      continue;
    }
    // 默认段落
    blocks.push({ id: generateId(), type: 'paragraph', content: line });
    i++;
  }
  if (blocks.length === 0) {
    blocks.push({ id: generateId(), type: 'paragraph', content: '' });
  }
  return blocks;
}

// ============================================================
//  NotionStyleEditor 组件
// ============================================================
interface NotionStyleEditorProps {
  page?: WikiPage;
  title: string;
  onTitleChange: (title: string) => void;
  content: string;
  onContentChange: (content: string) => void;
  kbId?: string; // 知识库 ID，用于调用 Block API
  onNavigateToPage?: (pageId: string) => void; // 页面跳转回调
}

export function NotionStyleEditor({
  page: _page,
  title,
  onTitleChange,
  content,
  onContentChange,
  kbId,
  onNavigateToPage,
}: NotionStyleEditorProps) {
  const { t } = useTranslation();
  const [blocks, setBlocks] = useState<Block[]>(() => markdownToBlocks(content));
  const [showPreview, setShowPreview] = useState(false);
  const [slashAnchor, setSlashAnchor] = useState<{ blockId: string; top: number; left: number } | null>(null);
  const [slashFilter, setSlashFilter] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [backlinks, setBacklinks] = useState<Array<{ id: string; sourcePageId: string; sourceTitle?: string; sourceSlug?: string; targetPageId?: string; targetSlug?: string; createdAt: string }>>([]);
  const [backlinksLoading, setBacklinksLoading] = useState(false);
  const [templates, setTemplates] = useState<Array<{ id: string; title: string; kbId?: string }>>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);
  const blockRefs = useRef<Map<string, HTMLDivElement>>(new Map());
  const saveTimeoutRef = useRef<NodeJS.Timeout>();
  // G2-R：标记 blocks 变更来源。仅当用户在编辑器内操作（updateBlock/addBlock/deleteBlock/输入）
  // 置 true 时，effect① 才允许把 blocks 反向回写 content；
  // 由 effect②（外部 content 载入）触发的 setBlocks 不得反向回写，避免"外部载入被清空"竞态。
  const userEditedRef = useRef(false);
  // 记录最后一次外部载入的 content，用于跳过紧随其后的反向回写
  const lastExternalContentRef = useRef(content);

  // 归一化比较：统一首尾空白/换行，避免 '' 与 '\n' 之类误判
  const normalizeMd = (s: string) => (s ?? '').replace(/\s+$/g, '').trimEnd();

  // 同步 blocks → content（仅在用户编辑后回写）
  useEffect(() => {
    if (!userEditedRef.current) return;
    userEditedRef.current = false;
    const md = blockToMarkdown(blocks);
    if (normalizeMd(md) !== normalizeMd(content)) {
      onContentChange(md);
    }
  }, [blocks, content, onContentChange]);

  // 外部 content 变化时同步（数据载入：把 markdown 解析为 blocks 并渲染）
   
  useEffect(() => {
    lastExternalContentRef.current = content;
    const newBlocks = markdownToBlocks(content);
    // 归一化比较（忽略每次解析生成的随机 block id），避免无谓重渲染
    const stripIds = (bs: Block[]) => JSON.stringify(bs.map(({ id: _id, ...rest }) => rest));
    if (stripIds(newBlocks) !== stripIds(blocks)) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：异步/条件/初始化模式，非连续渲染
      setBlocks(newBlocks);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps -- 刻意窄依赖/空依赖，避免对象引用变化引发循环
  }, [content]);

  // ============================================================
  //  Block API 自动保存（防抖）
  // ============================================================
  // eslint-disable-next-line react-hooks/preserve-manual-memoization -- 刻意依赖 _page?.id 而非 _page 对象本身，避免不必要的重渲染
  const saveBlocksToBackend = useCallback(async () => {
    if (!blocks.length || !_page?.id) return;
    setSaving(true);
    setSaveError(null);
    try {
      // 调用后端 POST /api/wiki/blocks/batch-upsert
      const res = await fetch(`/api/wiki/blocks/batch-upsert`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          pageId: _page?.id,
          blocks: blocks.map(b => ({
            type: b.type,
            content: b.content,
            language: b.language,
            checked: b.checked,
          })),
        }),
      });
      if (!res.ok) throw new Error(t('editor.error.saveFailedWithStatus', { status: res.status }));
      setSaveError(null);
    } catch (err) {
      const msg = err instanceof Error ? err.message : t('editor.error.saveFailed');
      setSaveError(msg);
    } finally {
      setSaving(false);
    }
  }, [blocks, _page?.id, t]);

  // 防抖保存
  useEffect(() => {
    if (saveTimeoutRef.current) clearTimeout(saveTimeoutRef.current);
    saveTimeoutRef.current = setTimeout(() => {
      saveBlocksToBackend();
    }, 2000); // 2 秒防抖
    return () => {
      if (saveTimeoutRef.current) clearTimeout(saveTimeoutRef.current);
    };
  }, [saveBlocksToBackend]);

  // 加载反向链接
  // eslint-disable-next-line react-hooks/preserve-manual-memoization -- 别名同上，仅依赖 _page?.id
  const loadBacklinks = useCallback(async () => {
    if (!_page?.id) return;
    setBacklinksLoading(true);
    try {
      const res = await fetch(`/api/wiki/pages/${_page.id}/backlinks`);
      if (!res.ok) throw new Error(t('editor.error.loadBacklinksFailed'));
      const data = await res.json();
      // 信封格式 {code:0, data:[...]}，需 unwrap
      const backlinks = data?.data || [];
      setBacklinks(backlinks);
    } catch (err) {
      console.error('加载反向链接失败:', err);
    } finally {
      setBacklinksLoading(false);
    }
  }, [_page?.id, t]);

  // 加载模板列表
  const loadTemplates = useCallback(async () => {
    if (!kbId) return;
    try {
      setTemplatesLoading(true);
      const res = await fetch(`/api/wiki/kb/${kbId}/templates`);
      if (!res.ok) throw new Error(t('editor.error.loadTemplatesFailedWithStatus', { status: res.status }));
      const data = await res.json();
      // 信封格式 {code:0, data:[...]}，需 unwrap
      setTemplates(data?.data || []);
    } catch (err) {
      console.error('加载模板失败:', err);
    } finally {
      setTemplatesLoading(false);
    }
  }, [kbId, t]);

  // 应用模板（从模板创建新页面）
  const applyTemplate = useCallback(async (templateId: string) => {
    if (!kbId) {
      alert(t('editor.error.selectKbFirst'));
      return;
    }
    const title = prompt(t('editor.error.promptNewPageTitle'));
    if (!title) return;
    
    try {
      const res = await fetch(`/api/wiki/pages/${templateId}/from-template`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          kbId, 
          title,
          slug: title.toLowerCase().replace(/\s+/g, '-'),
        }),
      });
      if (!res.ok) throw new Error(t('editor.error.createPageFailed'));
      const data = await res.json();
      // 跳转到新创建的页面
      window.location.href = `/wiki/${data.data.slug}`;
    } catch (err) {
      console.error('创建页面失败:', err);
    }
  }, [kbId, t]);

  // 初始加载反向链接和模板
   
  useEffect(() => {
    if (_page?.id) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：异步/条件/初始化模式，非连续渲染
      loadBacklinks();
    }
    loadTemplates();
  }, [_page?.id, loadBacklinks, loadTemplates]);

  // ============================================================
  //  块操作
  // ============================================================
  const updateBlock = useCallback((id: string, patch: Partial<Block>) => {
    userEditedRef.current = true;
    setBlocks(prev => prev.map(b => b.id === id ? { ...b, ...patch } : b));
  }, []);

  const deleteBlock = useCallback((id: string) => {
    userEditedRef.current = true;
    setBlocks(prev => {
      const idx = prev.findIndex(b => b.id === id);
      if (prev.length <= 1) return prev; // 保留最后一个块
      const next = prev.filter(b => b.id !== id);
      // 聚焦前一个块
      if (idx > 0) {
        const prevBlock = next[idx - 1];
        setTimeout(() => {
          const el = blockRefs.current.get(prevBlock.id)?.querySelector('[data-editable]') as HTMLElement | null;
          el?.focus();
        }, 0);
      }
      return next;
    });
  }, []);

  const addBlock = useCallback((afterId: string, type: BlockType) => {
    userEditedRef.current = true;
    setBlocks(prev => {
      const idx = prev.findIndex(b => b.id === afterId);
      const newBlock: Block = { id: generateId(), type, content: '' };
      const next = [...prev];
      next.splice(idx + 1, 0, newBlock);
      setTimeout(() => {
        const el = blockRefs.current.get(newBlock.id)?.querySelector('[data-editable]') as HTMLElement | null;
        el?.focus();
      }, 0);
      return next;
    });
    setSlashAnchor(null);
    setSlashFilter('');
  }, []);

  const changeBlockType = useCallback((id: string, newType: BlockType) => {
    updateBlock(id, { type: newType });
    setSlashAnchor(null);
    setSlashFilter('');
  }, [updateBlock]);

  // ============================================================
  //  斜杠命令
  // ============================================================
  const handleSlashKey = useCallback((blockId: string, text: string, rect: DOMRect) => {
    if (text === '/') {
      setSlashAnchor({ blockId, top: rect.bottom + 4, left: rect.left });
      setSlashFilter('');
    }
  }, []);

  const handleSlashFilterChange = useCallback((text: string) => {
    setSlashFilter(text.replace(/^\/\s*/, ''));
  }, []);

  const filteredCommands = getSlashCommands(t).filter(c =>
    c.label.toLowerCase().includes(slashFilter.toLowerCase()) ||
    c.description.toLowerCase().includes(slashFilter.toLowerCase())
  );

  // ============================================================
  //  渲染单个块
  // ============================================================
  const renderBlock = (block: Block) => {
    const isHeading = block.type.startsWith('heading');
    const isTodo = block.type === 'todo';
    const isCode = block.type === 'code';
    const isDivider = block.type === 'divider';
    const isCallout = block.type === 'callout';

    if (isDivider) {
      return (
        <Divider sx={{ my: 2 }} />
      );
    }

    if (isCallout) {
      return (
        <Paper
          sx={{
            px: 2, py: 1.5, bgcolor: 'info.lighter', borderLeft: '4px solid', borderColor: 'info.main',
            borderRadius: 1, my: 1,
          }}
        >
          <Box data-editable
            contentEditable
            suppressContentEditableWarning
            onBlur={(e) => updateBlock(block.id, { content: e.currentTarget.textContent || '' })}
            sx={{ outline: 'none', fontSize: '0.95rem', lineHeight: 1.6 }}
            dangerouslySetInnerHTML={{ __html: renderMarkdownInline(block.content) }}
          />
        </Paper>
      );
    }

    if (isCode) {
      return (
        <Box sx={{ my: 1 }}>
          <TextField
            select
            size="small"
            value={block.language || 'text'}
            onChange={(e) => updateBlock(block.id, { language: e.target.value })}
            sx={{ mb: 0.5, minWidth: 120 }}
            slotProps={{ select: { native: true } }}
          >
            {['text', 'javascript', 'typescript', 'python', 'java', 'sql', 'yaml', 'json', 'bash', 'markdown'].map(lang => (
              <option key={lang} value={lang}>{lang}</option>
            ))}
          </TextField>
          <Box data-editable
            contentEditable
            suppressContentEditableWarning
            onBlur={(e) => updateBlock(block.id, { content: e.currentTarget.textContent || '' })}
            sx={{
              bgcolor: 'var(--color-bg-primary)', color: 'var(--color-text-muted)', p: 2, borderRadius: 1,
              fontFamily: 'monospace', fontSize: '0.85rem', minHeight: 80,
              outline: 'none', whiteSpace: 'pre-wrap',
            }}
          >
            {block.content}
          </Box>
        </Box>
      );
    }

    const fontSize = isHeading
      ? (block.type === 'heading1' ? '1.75rem' : block.type === 'heading2' ? '1.4rem' : '1.15rem')
      : '0.95rem';

    return (
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 0.5,
          py: 0.25,
          '&:hover .drag-handle': { opacity: 1 },
        }}
      >
        {/*
          拖拽手柄 —— PHASE98 R4：onMouseDown 是空实现（注释写着"可后续接入 dnd-kit"），
          即"看起来能拖、实际拖不动"的假交互。在真正接入 dnd-kit 之前，
          必须对它隐藏无障碍语义（aria-hidden + 不可聚焦），
          否则屏幕阅读器和键盘用户会遇到一个点了没反应的控件。
        */}
        <IconButton
          className="drag-handle"
          size="small"
          aria-hidden="true"
          tabIndex={-1}
          sx={{ opacity: 0, cursor: 'grab', mt: 0.25, transition: 'opacity 0.15s' }}
          onMouseDown={() => {/* 拖拽逻辑待接入 dnd-kit；在此之前对无障碍隐藏 */}}
        >
          <DragIndicator fontSize="small" />
        </IconButton>

        {/* 待办勾选框 */}
        {isTodo && (
          <IconButton
            size="small"
            aria-label={block.checked ? t('editor.aria.markIncomplete') : t('editor.aria.markComplete')}
            onClick={() => updateBlock(block.id, { checked: !block.checked })}
            sx={{ mt: 0.25, p: 0.5 }}
          >
            {block.checked
              ? <CheckBox fontSize="small" color="primary" />
              : <CheckBoxOutlineBlank fontSize="small" />
            }
          </IconButton>
        )}

        {/* 可编辑内容 */}
        <Box
          data-editable
          contentEditable
          suppressContentEditableWarning
          onBlur={(e) => {
            const text = e.currentTarget.textContent || '';
            updateBlock(block.id, { content: text });
            // 检测斜杠命令
            if (text === '/') {
              const rect = e.currentTarget.getBoundingClientRect();
              handleSlashKey(block.id, text, rect);
            } else if (text.startsWith('/')) {
              handleSlashFilterChange(text);
            }
          }}
          onKeyDown={(e) => {
            const target = e.target as HTMLElement;
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              // Enter → 创建新段落块
              const text = target.textContent || '';
              updateBlock(block.id, { content: text });
              addBlock(block.id, 'paragraph');
            }
            if (e.key === 'Backspace' && target.textContent === '') {
              e.preventDefault();
              deleteBlock(block.id);
            }
          }}
          sx={{
            flex: 1,
            outline: 'none',
            fontSize,
            fontWeight: isHeading ? 600 : 400,
            lineHeight: 1.6,
            minHeight: fontSize === '1.75rem' ? 40 : 24,
            textDecoration: isTodo && block.checked ? 'line-through' : 'none',
            color: isTodo && block.checked ? 'text.disabled' : 'text.primary',
            '&:focus': { bgcolor: 'action.hover', borderRadius: 0.5 },
          }}
          dangerouslySetInnerHTML={{ __html: renderMarkdownInline(block.content) }}
        />

        {/* 块操作按钮 */}
        <Box sx={{ display: 'flex', gap: 0.25, opacity: 0, transition: 'opacity 0.15s', '&:hover': { opacity: 1 } }}>
          <Tooltip title={t('editor.tooltip.deleteBlock')}>
            <IconButton
              size="small"
              onClick={() => deleteBlock(block.id)}
              aria-label={t('editor.tooltip.deleteBlock')}
            >
              <DeleteSweep fontSize="small" />
            </IconButton>
          </Tooltip>
        </Box>
      </Box>
    );
  };

  // ============================================================
  //  内联 Markdown 渲染（简化版）
  // ============================================================
  function renderMarkdownInline(md: string): string {
    if (!md) return '';
    let html = md
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      .replace(/`(.+?)`/g, '<code style="background:var(--color-bg-tertiary);padding:0.2em 0.4em;border-radius:3px;font-family:monospace;">$1</code>')
      .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" style="color:var(--color-info);">$1</a>')
      .replace(/\n/g, '<br />');
    return html;
  }

  // ============================================================
  //  预览模式渲染
  // ============================================================
  const renderPreview = () => {
    const md = blockToMarkdown(blocks);
    return (
      <Box
        sx={{
          '& h1': { fontSize: '2rem', fontWeight: 'bold', margin: '1rem 0 0.5rem' },
          '& h2': { fontSize: '1.5rem', fontWeight: 'bold', margin: '0.75rem 0 0.5rem' },
          '& h3': { fontSize: '1.25rem', fontWeight: 'bold', margin: '0.5rem 0' },
          '& p': { margin: '0.5rem 0', lineHeight: 1.6 },
          '& ul': { paddingLeft: 2, margin: '0.5rem 0' },
          '& ol': { paddingLeft: 2, margin: '0.5rem 0' },
          '& li': { margin: '0.25rem 0' },
          '& code': {
            background: 'var(--color-bg-tertiary)', padding: '0.2em 0.4em', borderRadius: 3, fontFamily: 'monospace',
          },
          '& pre': {
            background: 'var(--color-bg-primary)', padding: 1, borderRadius: 1, overflowX: 'auto', margin: '0.5rem 0',
          },
          '& blockquote': {
            borderLeft: '4px solid var(--color-border-light)', paddingLeft: 1, margin: 0.5, color: 'var(--color-text-muted)',
          },
          '& table': { borderCollapse: 'collapse', width: '100%', margin: '0.5rem 0' },
          '& th, &td': { border: '1px solid var(--color-border-medium)', padding: 8, textAlign: 'left' },
          '& a': { color: 'var(--color-info)' },
        }}
      >
        <ReactMarkdown
          components={{
            // eslint-disable-next-line @typescript-eslint/no-unused-vars -- node 是 ReactMarkdown code 组件 API，此处仅用 className 区分内联/块级
            code({ node, className, children, ...props }) {
              const match = /language-(\w+)/.exec(className || '');
              const isInline = !match && !className;
              if (isInline) {
                return <code {...props}>{children}</code>;
              }
              return (
                <SyntaxHighlighter
                  style={oneDark}
                  language={match ? match[1] : 'text'}
                  PreTag="div"
                >
                  {String(children).replace(/\n$/, '')}
                </SyntaxHighlighter>
              );
            },
          }}
        >
          {md}
        </ReactMarkdown>
      </Box>
    );
  };

  // ============================================================
  //  内联格式化操作
  // ============================================================
  const applyInlineFormat = (format: 'bold' | 'italic' | 'strikethrough' | 'code' | 'link') => {
    // 获取当前聚焦的块
    const activeEl = document.querySelector('[data-editable]:focus');
    if (!activeEl) return;
    const blockId = activeEl.closest('[data-block-id]')?.getAttribute('data-block-id');
    if (!blockId) return;
    const block = blocks.find(b => b.id === blockId);
    if (!block) return;

    let newContent = block.content;
    const sel = window.getSelection();
    if (sel && sel.rangeCount > 0 && !sel.isCollapsed) {
      const selected = sel.toString();
      switch (format) {
        case 'bold': newContent = block.content.replace(selected, `**${selected}**`); break;
        case 'italic': newContent = block.content.replace(selected, `*${selected}*`); break;
        case 'strikethrough': newContent = block.content.replace(selected, `~~${selected}~~`); break;
        case 'code': newContent = block.content.replace(selected, `\`${selected}\``); break;
        case 'link': newContent = block.content.replace(selected, `[${selected}](url)`); break;
      }
    } else {
      // 无选中文本 → 在光标位置插入格式标记
      const placeholder = format === 'bold' ? t('editor.placeholder.bold') : format === 'italic' ? t('editor.placeholder.italic') : format === 'code' ? t('editor.placeholder.code') : t('editor.placeholder.link');
      const markers: Record<string, [string, string]> = {
        bold: ['**', '**'],
        italic: ['*', '*'],
        strikethrough: ['~~', '~~'],
        code: ['`', '`'],
        link: ['[', '](url)'],
      };
      const [before, after] = markers[format];
      newContent = block.content + `${before}${placeholder}${after}`;
    }
    updateBlock(blockId, { content: newContent });
  };

  // ============================================================
  //  渲染
  // ============================================================
  return (
    <Box>
      <TextField
        label={t('editor.ui.title')}
        value={title}
        onChange={(e: any) => onTitleChange(e.target.value)}
        fullWidth
        required
        variant="standard"
        sx={{ mb: 3, fontFamily: 'inherit', fontSize: '1.5rem', fontWeight: 600 }}
      />

      <Box sx={{ mb: 2, display: 'flex', gap: 1, alignItems: 'center' }}>
        <Button
          variant={showPreview ? 'contained' : 'outlined'}
          size="small"
          onClick={() => setShowPreview(!showPreview)}
        >
          {showPreview ? t('editor.ui.editMode') : t('editor.ui.previewMode')}
        </Button>
        <Chip label={`${blocks.length} ${t('editor.ui.blocks')}`} size="small" variant="outlined" />
        {saving && (
          <Chip label={t('editor.ui.saving')} size="small" color="info" variant="filled" />
        )}
        {saveError && (
          <Alert severity="error" sx={{ ml: 1 }} onClose={() => setSaveError(null)}>
            {saveError}
          </Alert>
        )}
      </Box>

      {!showPreview && (
        <Paper sx={{ p: 2, mb: 2 }}>
          {/* 内联格式化工具栏 */}
          <Box
            sx={{
              display: 'flex', gap: 0.5, flexWrap: 'wrap', mb: 1, pb: 1,
              borderBottom: '1px solid var(--color-border-light)',
            }}
          >
            <Typography variant="subtitle2" sx={{ mr: 1, alignSelf: 'center' }}>
              {t('editor.ui.format')}
            </Typography>
            <Tooltip title={t('editor.ui.bold')}>
              <IconButton size="small" onClick={() => applyInlineFormat('bold')}>
                <FormatBold fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title={t('editor.ui.italic')}>
              <IconButton size="small" onClick={() => applyInlineFormat('italic')}>
                <FormatItalic fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title={t('editor.ui.strikethrough')}>
              <IconButton size="small" onClick={() => applyInlineFormat('strikethrough')}>
                <StrikethroughS fontSize="small" />
              </IconButton>
            </Tooltip>
            <Divider orientation="vertical" flexItem />
            <Tooltip title={t('editor.ui.code')}>
              <IconButton size="small" onClick={() => applyInlineFormat('code')}>
                <Code fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title={t('editor.ui.link')}>
              <IconButton size="small" onClick={() => applyInlineFormat('link')}>
                <Link fontSize="small" />
              </IconButton>
            </Tooltip>
            <Divider orientation="vertical" flexItem />
            <Tooltip title={t('editor.ui.openSlashMenu')}>
              <Chip label={t('editor.ui.slashCommand')} size="small" onClick={() => {}} />
            </Tooltip>
          </Box>
        </Paper>
      )}

      {/* 编辑/预览区域 */}
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {!showPreview ? (
          <Paper sx={{ p: 3, minHeight: 400 }}>
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
              {blocks.map(block => (
                <Box key={block.id} data-block-id={block.id}>
                  {renderBlock(block)}
                </Box>
              ))}
            </Box>
          </Paper>
        ) : (
          <Paper sx={{ p: 3, minHeight: 400 }}>
            {renderPreview()}
          </Paper>
        )}
      </Box>

      {/* 斜杠命令弹出菜单 */}
      <Popover
        open={slashAnchor !== null}
        anchorReference="anchorPosition"
        anchorPosition={slashAnchor ? { top: slashAnchor.top, left: slashAnchor.left } : undefined}
        onClose={() => { setSlashAnchor(null); setSlashFilter(''); }}
        slotProps={{ paper: { sx: { maxHeight: 320, width: 280 } } }}
      >
        <Box sx={{ p: 1 }}>
          <TextField
            // eslint-disable-next-line jsx-a11y/no-autofocus -- 弹窗/面板内首个输入框自动聚焦，符合交互预期
            autoFocus
            size="small"
            placeholder={t('editor.ui.searchBlocks')}
            value={slashFilter}
            onChange={(e) => handleSlashFilterChange(e.target.value)}
            fullWidth
            sx={{ mb: 1 }}
          />
          <List dense sx={{ maxHeight: 240, overflow: 'auto' }}>
            {filteredCommands.map(cmd => (
              <ListItemButton
                key={cmd.type}
                onClick={() => {
                  if (slashAnchor) {
                    changeBlockType(slashAnchor.blockId, cmd.type);
                  }
                }}
              >
                <ListItemIcon sx={{ minWidth: 36 }}>{cmd.icon}</ListItemIcon>
                <ListItemText primary={cmd.label} secondary={cmd.description} />
              </ListItemButton>
            ))}
            {filteredCommands.length === 0 && (
              <Typography variant="body2" sx={{ p: 2, color: 'text.secondary' }}>
                {t('editor.ui.noMatches')}
              </Typography>
            )}
          </List>
        </Box>
      </Popover>

      {/* 反向链接面板（毛玻璃侧栏） */}
      <Paper
        sx={{
          mt: 2, p: 2,
          background: 'var(--color-border-light)',
          backdropFilter: 'blur(12px) saturate(180%)',
          border: '1px solid var(--color-border-light)',
          borderRadius: 2,
        }}
      >
        <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1, color: 'text.secondary' }}>
          🔗 {t('editor.ui.backlinks')}
        </Typography>
        {backlinksLoading ? (
          <Typography variant="body2" sx={{ color: 'text.disabled', fontSize: 12 }}>
            {t('editor.ui.backlinksLoading')}
          </Typography>
        ) : backlinks.length === 0 ? (
          <Typography variant="body2" sx={{ color: 'text.disabled', fontSize: 12 }}>
            {t('editor.ui.noBacklinks')}
          </Typography>
        ) : (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
            {backlinks.map(bl => (
              <Box
                key={bl.id}
                sx={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  p: 1, borderRadius: 1, cursor: 'pointer',
                  transition: 'background 200ms ease',
                  '&:hover': { background: 'var(--color-border-light)' },
                }}
                onClick={() => onNavigateToPage?.(bl.sourcePageId)}
              >
                <Box>
                  <Typography variant="body2">{bl.sourceTitle || t('editor.ui.unknownPage')}</Typography>
                  <Typography variant="caption" sx={{ color: 'text.disabled' }}>
                    {bl.sourceSlug ? `/${bl.sourceSlug}` : ''}
                  </Typography>
                </Box>
                <Typography variant="caption" sx={{ color: 'text.disabled' }}>
                  {new Date(bl.createdAt).toLocaleDateString()}
                </Typography>
              </Box>
            ))}
          </Box>
        )}
      </Paper>

      {/* 模板选择（毛玻璃按钮组） */}
      <Paper
        sx={{
          mt: 2, p: 2,
          background: 'var(--color-border-light)',
          backdropFilter: 'blur(12px) saturate(180%)',
          border: '1px solid var(--color-border-light)',
          borderRadius: 2,
        }}
      >
        <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1, color: 'text.secondary' }}>
          📄 {t('editor.ui.templates')}
        </Typography>
        {templatesLoading ? (
          <Typography variant="body2" sx={{ color: 'text.disabled', fontSize: 12 }}>
            {t('editor.ui.templatesLoading')}
          </Typography>
        ) : templates.length === 0 ? (
          <Typography variant="body2" sx={{ color: 'text.disabled', fontSize: 12 }}>
            {t('editor.ui.noTemplates')}
          </Typography>
        ) : (
          <>
            <Typography variant="caption" sx={{ display: 'block', mb: 1, color: 'text.secondary' }}>
              {t('editor.ui.templatesHint')}
            </Typography>
            <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
              {templates.map(tpl => (
                <Chip
                  key={tpl.id}
                  label={tpl.title}
                  size="small"
                  variant="outlined"
                  sx={{
                    cursor: 'pointer',
                    '&:hover': { bgcolor: 'var(--color-border-light)' },
                    borderColor: 'var(--color-border-medium)',
                  }}
                  onClick={() => applyTemplate(tpl.id)}
                />
              ))}
            </Box>
          </>
        )}
      </Paper>
    </Box>
  );
}