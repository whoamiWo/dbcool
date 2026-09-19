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
//  斜杠命令菜单项
// ============================================================
const SLASH_COMMANDS: { label: string; type: BlockType; icon: React.ReactNode; description: string }[] = [
  { label: '段落', type: 'paragraph', icon: <Title fontSize="small" />, description: '普通文本段落' },
  { label: '标题 1', type: 'heading1', icon: <FormatSize fontSize="small" />, description: '大标题' },
  { label: '标题 2', type: 'heading2', icon: <FormatSize fontSize="small" />, description: '中标题' },
  { label: '标题 3', type: 'heading3', icon: <FormatSize fontSize="small" />, description: '小标题' },
  { label: '无序列表', type: 'bullet', icon: <FormatListBulleted fontSize="small" />, description: '项目符号列表' },
  { label: '有序列表', type: 'numbered', icon: <FormatListNumbered fontSize="small" />, description: '编号列表' },
  { label: '待办', type: 'todo', icon: <CheckBoxOutlineBlank fontSize="small" />, description: '可勾选的任务项' },
  { label: '引用', type: 'quote', icon: <FormatQuote fontSize="small" />, description: '引用块' },
  { label: '代码块', type: 'code', icon: <Code fontSize="small" />, description: '代码片段' },
  { label: '分割线', type: 'divider', icon: <HorizontalRule fontSize="small" />, description: '水平分割线' },
  { label: '提示框', type: 'callout', icon: <Lightbulb fontSize="small" />, description: '高亮提示框' },
];

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
}

export function NotionStyleEditor({
  page: _page,
  title,
  onTitleChange,
  content,
  onContentChange,
}: NotionStyleEditorProps) {
  const [blocks, setBlocks] = useState<Block[]>(() => markdownToBlocks(content));
  const [showPreview, setShowPreview] = useState(false);
  const [slashAnchor, setSlashAnchor] = useState<{ blockId: string; top: number; left: number } | null>(null);
  const [slashFilter, setSlashFilter] = useState('');
  const blockRefs = useRef<Map<string, HTMLDivElement>>(new Map());

  // 同步 blocks → content
  useEffect(() => {
    const md = blockToMarkdown(blocks);
    if (md !== content) {
      onContentChange(md);
    }
  }, [blocks, content, onContentChange]);

  // 外部 content 变化时同步
  useEffect(() => {
    const newBlocks = markdownToBlocks(content);
    if (JSON.stringify(newBlocks) !== JSON.stringify(blocks)) {
      setBlocks(newBlocks);
    }
  }, [content]);

  // ============================================================
  //  块操作
  // ============================================================
  const updateBlock = useCallback((id: string, patch: Partial<Block>) => {
    setBlocks(prev => prev.map(b => b.id === id ? { ...b, ...patch } : b));
  }, []);

  const deleteBlock = useCallback((id: string) => {
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

  const filteredCommands = SLASH_COMMANDS.filter(c =>
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
              bgcolor: '#1e1e1e', color: '#d4d4d4', p: 2, borderRadius: 1,
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
        {/* 拖拽手柄 */}
        <IconButton
          className="drag-handle"
          size="small"
          sx={{ opacity: 0, cursor: 'grab', mt: 0.25, transition: 'opacity 0.15s' }}
          onMouseDown={() => {/* 拖拽逻辑可后续接入 dnd-kit */}}
        >
          <DragIndicator fontSize="small" />
        </IconButton>

        {/* 待办勾选框 */}
        {isTodo && (
          <IconButton
            size="small"
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
          <Tooltip title="删除块">
            <IconButton size="small" onClick={() => deleteBlock(block.id)}>
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
      .replace(/`(.+?)`/g, '<code style="background:#f5f5f5;padding:0.2em 0.4em;border-radius:3px;font-family:monospace;">$1</code>')
      .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" style="color:#1976d2;">$1</a>')
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
            background: '#f5f5f5', padding: '0.2em 0.4em', borderRadius: 3, fontFamily: 'monospace',
          },
          '& pre': {
            background: '#1e1e1e', padding: 1, borderRadius: 1, overflowX: 'auto', margin: '0.5rem 0',
          },
          '& blockquote': {
            borderLeft: '4px solid #ccc', paddingLeft: 1, margin: 0.5, color: '#666',
          },
          '& table': { borderCollapse: 'collapse', width: '100%', margin: '0.5rem 0' },
          '& th, &td': { border: '1px solid #ddd', padding: 8, textAlign: 'left' },
          '& a': { color: '#1976d2' },
        }}
      >
        <ReactMarkdown
          components={{
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
      const placeholder = format === 'bold' ? '粗体文本' : format === 'italic' ? '斜体文本' : format === 'code' ? '代码' : '链接文本';
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
        label="标题"
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
          {showPreview ? '编辑模式' : '预览模式'}
        </Button>
        <Chip label={`${blocks.length} 个块`} size="small" variant="outlined" />
      </Box>

      {!showPreview && (
        <Paper sx={{ p: 2, mb: 2 }}>
          {/* 内联格式化工具栏 */}
          <Box
            sx={{
              display: 'flex', gap: 0.5, flexWrap: 'wrap', mb: 1, pb: 1,
              borderBottom: '1px solid #e0e0e0',
            }}
          >
            <Typography variant="subtitle2" sx={{ mr: 1, alignSelf: 'center' }}>
              格式:
            </Typography>
            <Tooltip title="粗体 (Ctrl+B)">
              <IconButton size="small" onClick={() => applyInlineFormat('bold')}>
                <FormatBold fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="斜体 (Ctrl+I)">
              <IconButton size="small" onClick={() => applyInlineFormat('italic')}>
                <FormatItalic fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="删除线">
              <IconButton size="small" onClick={() => applyInlineFormat('strikethrough')}>
                <StrikethroughS fontSize="small" />
              </IconButton>
            </Tooltip>
            <Divider orientation="vertical" flexItem />
            <Tooltip title="行内代码">
              <IconButton size="small" onClick={() => applyInlineFormat('code')}>
                <Code fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="链接">
              <IconButton size="small" onClick={() => applyInlineFormat('link')}>
                <Link fontSize="small" />
              </IconButton>
            </Tooltip>
            <Divider orientation="vertical" flexItem />
            <Tooltip title="输入 / 打开块菜单">
              <Chip label="/ 命令" size="small" onClick={() => {}} />
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
            autoFocus
            size="small"
            placeholder="搜索块类型..."
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
                无匹配结果
              </Typography>
            )}
          </List>
        </Box>
      </Popover>
    </Box>
  );
}