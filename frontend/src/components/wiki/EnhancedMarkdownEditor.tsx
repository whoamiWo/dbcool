import { useState, useCallback, useRef } from 'react';
import {
  Box,
  Button,
  IconButton,
  Typography,
  Paper,
  Divider,
  Tooltip,
  TextField,
  Grid,
} from '@mui/material';
import {
  FormatBold,
  FormatItalic,
  StrikethroughS,
  Code,
  FormatListBulleted,
  FormatListNumbered,
  FormatQuote,
  Link,
  TableChart,
  FormatSize,
} from '@mui/icons-material';
import type { WikiPage } from '@/types/wiki';

interface EnhancedMarkdownEditorProps {
  page?: WikiPage;
  title: string;
  onTitleChange: (title: string) => void;
  content: string;
  onContentChange: (content: string) => void;
  _page?: WikiPage;
}

function insertAtCursor(
  textarea: HTMLTextAreaElement | null,
  before: string,
  after: string = '',
  placeholder: string = ''
): string {
  if (!textarea) return '';
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;
  const selected = textarea.value.substring(start, end);
  const newText =
    textarea.value.substring(0, start) +
    before +
    (selected || placeholder) +
    after +
    textarea.value.substring(end);
  return newText;
}

function renderMarkdownInline(md: string): string {
  return md
    .replace(/^### (.+)$/gm, '<h3>$1</h3>')
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/^# (.+)$/gm, '<h1>$1</h1>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/`(.+?)`/g, '<code>$1</code>')
    .replace(/^> (.+)$/gm, '<blockquote>$1</blockquote>')
    .replace(/^- (.+)$/gm, '<li>$1</li>')
    .replace(/^\d+\. (.+)$/gm, '<li>$1</li>')
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>')
    .replace(/\n/g, '<br />');
}

export function EnhancedMarkdownEditor({
  page: _page,
  title,
  onTitleChange,
  content,
  onContentChange,
}: EnhancedMarkdownEditorProps) {
  const [showPreview, setShowPreview] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const insertText = useCallback(
    (before: string, after?: string, placeholder?: string) => {
      onContentChange(insertAtCursor(textareaRef.current, before, after || '', placeholder));
      textareaRef.current?.focus();
    },
    [onContentChange]
  );

  return (
    <Box>
      <TextField
        label="标题"
        value={title}
        onChange={(e: any) => onTitleChange(e.target.value)}
        fullWidth
        required
        variant="standard"
        sx={{ mb: 2, fontFamily: 'inherit' }}
      />
      <Box sx={{ mb: 2 }}>
        <Button
          variant={showPreview ? 'contained' : 'outlined'}
          onClick={() => setShowPreview(!showPreview)}
        >
          {showPreview ? '编辑模式' : '预览模式'}
        </Button>
      </Box>

      <Grid container spacing={2}>
        {!showPreview && (
          <Grid size={{ xs: 12, md: 6 }}>
            <Paper sx={{ p: 2 }}>
              {/* Toolbar */}
              <Box
                sx={{
                  display: 'flex',
                  gap: 0.5,
                  flexWrap: 'wrap',
                  mb: 2,
                  pb: 1,
                  borderBottom: '1px solid #e0e0e0',
                }}
              >
                <Typography variant="subtitle2" sx={{ mr: 1, alignSelf: 'center' }}>
                  工具栏:
                </Typography>
                <Tooltip title="标题 1">
                  <IconButton size="small" onClick={() => insertText('# ')}>
                    <FormatSize />
                  </IconButton>
                </Tooltip>
                <Tooltip title="粗体 (Ctrl+B)">
                  <IconButton size="small" onClick={() => insertText('**', '**', '粗体文本')}>
                    <FormatBold />
                  </IconButton>
                </Tooltip>
                <Tooltip title="斜体 (Ctrl+I)">
                  <IconButton size="small" onClick={() => insertText('*', '*', '斜体文本')}>
                    <FormatItalic />
                  </IconButton>
                </Tooltip>
                <Tooltip title="删除线">
                  <IconButton size="small" onClick={() => insertText('~~', '~~', '删除文本')}>
                    <StrikethroughS />
                  </IconButton>
                </Tooltip>
                <Divider orientation="vertical" flexItem />
                <Tooltip title="代码块">
                  <IconButton size="small" onClick={() => insertText('```\n', '\n```')}>
                    <Code />
                  </IconButton>
                </Tooltip>
                <Tooltip title="行内代码">
                  <IconButton size="small" onClick={() => insertText('`', '`', '代码')}>
                    <Code />
                  </IconButton>
                </Tooltip>
                <Divider orientation="vertical" flexItem />
                <Tooltip title="无序列表">
                  <IconButton size="small" onClick={() => insertText('- ', '', '列表项')}>
                    <FormatListBulleted />
                  </IconButton>
                </Tooltip>
                <Tooltip title="有序列表">
                  <IconButton size="small" onClick={() => insertText('1. ', '', '列表项')}>
                    <FormatListNumbered />
                  </IconButton>
                </Tooltip>
                <Tooltip title="引用">
                  <IconButton size="small" onClick={() => insertText('> ', '', '引用文本')}>
                    <FormatQuote />
                  </IconButton>
                </Tooltip>
                <Divider orientation="vertical" flexItem />
                <Tooltip title="链接">
                  <IconButton size="small" onClick={() => insertText('[', '](url)', '链接文本')}>
                    <Link />
                  </IconButton>
                </Tooltip>
                <Tooltip title="表格">
                  <IconButton
                    size="small"
                    onClick={() =>
                      insertText('| 列1 | 列2 |\n|------|------|\n| 值1 | 值2 |')
                    }
                  >
                    <TableChart />
                  </IconButton>
                </Tooltip>
              </Box>

              <TextField
                inputRef={textareaRef}
                label="Markdown 内容"
                value={content}
                onChange={(e: any) => onContentChange(e.target.value)}
                multiline
                fullWidth
                rows={25}
                placeholder="支持 Markdown 格式..."
                variant="outlined"
                sx={{ fontFamily: 'monospace' }}
              />
            </Paper>
          </Grid>
        )}

        <Grid size={{ xs: 12, md: showPreview ? 12 : 6 }}>
          <Paper sx={{ p: 2, minHeight: 500 }}>
            <Typography variant="subtitle2" gutterBottom>
              {showPreview ? '预览' : '渲染预览'}
            </Typography>
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
                  background: '#f5f5f5',
                  padding: '0.2em 0.4em',
                  borderRadius: 3,
                  fontFamily: 'monospace',
                },
                '& pre': {
                  background: '#f5f5f5',
                  padding: 1,
                  borderRadius: 1,
                  overflowX: 'auto',
                  margin: '0.5rem 0',
                },
                '& blockquote': {
                  borderLeft: '4px solid #ccc',
                  paddingLeft: 1,
                  margin: 0.5,
                  color: '#666',
                },
                '& table': { borderCollapse: 'collapse', width: '100%', margin: '0.5rem 0' },
                '& th, & td': { border: '1px solid #ddd', padding: 8, textAlign: 'left' },
                '& a': { color: '#1976d2' },
              }}
              dangerouslySetInnerHTML={{
                __html: showPreview
                  ? renderMarkdownInline(content)
                  : content.replace(/\n/g, '<br />'),
              }}
            />
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
}
