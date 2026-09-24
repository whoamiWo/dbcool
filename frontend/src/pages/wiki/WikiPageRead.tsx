import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Box,
  Button,
  Typography,
  Paper,
  Divider,
  Chip,
  CircularProgress,
  Breadcrumbs,
  Link as MuiLink,
} from '@mui/material';
import {
  Edit as EditIcon,
  History as HistoryIcon,
  Home as HomeIcon,
} from '@mui/icons-material';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { wikiApi } from '@/api/wiki';
import type { WikiPage } from '@/types/wiki';
import '@/theme/glass.css';

export function WikiPageReadPage() {
  const { slug } = useParams<{ slug: string }>();

  const { data, isLoading, error } = useQuery({
    queryKey: ['wiki-page', slug],
    queryFn: async () => {
      const res = await wikiApi.getPageBySlug(slug!);
      return (res as any)?.data ?? res;
    },
    enabled: !!slug,
  });

  const page = data as WikiPage | undefined;

  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (error || !page) {
    return (
      <Box sx={{ p: 4, textAlign: 'center' }}>
        <Typography variant="h5" color="error">页面不存在</Typography>
        <Button sx={{ mt: 2 }} href="/wiki/kb">
          返回知识库
        </Button>
      </Box>
    );
  }

  return (
    <Box sx={{ maxWidth: 900, mx: 'auto', px: 2, py: 4 }} className="bg-primary">
      <Breadcrumbs separator="›" sx={{ mb: 2 }}>
        <MuiLink component={Link} to="/wiki/kb" color="inherit">
          <HomeIcon fontSize="small" />
        </MuiLink>
        <MuiLink component={Link} to={`/wiki/kb/${page.knowledge_base_id}`} color="inherit">
          文档列表
        </MuiLink>
        <Typography color="textPrimary">{page.title}</Typography>
      </Breadcrumbs>

      <Paper sx={{ p: 4, background: 'var(--glass-bg-medium)', border: '1px solid var(--color-border-light)', borderRadius: 12 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 3 }}>
          <Typography variant="h3" component="h1" sx={{ color: 'var(--color-text-primary)' }}>{page.title}</Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Chip
              label={page.status}
              color={page.status === 'PUBLISHED' ? 'success' : page.status === 'ARCHIVED' ? 'default' : 'warning'}
              size="small"
            />
            <Typography variant="caption" sx={{ color: 'var(--color-text-muted)' }}>
              v{page.version}
            </Typography>
          </Box>
        </Box>

        <Divider sx={{ mb: 3, borderColor: 'var(--color-border-light)' }} />

        <Box sx={{ mb: 4 }}>
          <Box
            sx={{
              '& h1': { fontSize: '2rem', fontWeight: 'bold', margin: '1rem 0 0.5rem', color: 'var(--color-text-primary)' },
              '& h2': { fontSize: '1.5rem', fontWeight: 'bold', margin: '0.75rem 0 0.5rem', color: 'var(--color-text-primary)' },
              '& h3': { fontSize: '1.25rem', fontWeight: 'bold', margin: '0.5rem 0', color: 'var(--color-text-secondary)' },
              '& p': { margin: '0.5rem 0', lineHeight: 1.8, color: 'var(--color-text-secondary)' },
              '& ul': { paddingLeft: 2, margin: '0.5rem 0' },
              '& ol': { paddingLeft: 2, margin: '0.5rem 0' },
              '& li': { margin: '0.25rem 0', color: 'var(--color-text-secondary)' },
              '& code': {
                background: 'var(--glass-bg-medium)', padding: '0.2em 0.4em', borderRadius: 3, fontFamily: 'monospace', color: 'var(--color-primary-200)',
              },
              '& pre': {
                background: 'var(--color-bg-primary)', padding: 1.5, borderRadius: 1, overflowX: 'auto', margin: '0.5rem 0',
              },
              '& blockquote': {
                borderLeft: '4px solid var(--color-primary-500)', paddingLeft: 1, margin: 0.5, color: 'var(--color-text-muted)',
              },
              '& table': { borderCollapse: 'collapse', width: '100%', margin: '0.5rem 0' },
              '& th, &td': { border: '1px solid var(--color-border-light)', padding: 8, textAlign: 'left', color: 'var(--color-text-secondary)' },
              '& a': { color: 'var(--color-primary-400)' },
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
              {page.content}
            </ReactMarkdown>
          </Box>
        </Box>

        <Divider sx={{ mb: 3, borderColor: 'var(--color-border-light)' }} />

        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Typography variant="caption" sx={{ color: 'var(--color-text-muted)' }}>
            创建于 {new Date(page.created_at).toLocaleString('zh-CN')}
            {page.created_by && ` · 作者: ${page.created_by}`}
          </Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button
              startIcon={<EditIcon />}
              component={Link}
              to={`/wiki/${page.slug}/edit`}
              variant="outlined"
              sx={{ borderColor: 'var(--color-border-medium)', color: 'var(--color-text-primary)', '&:hover': { background: 'var(--color-border-light)' } }}
            >
              编辑
            </Button>
            <Button
              startIcon={<HistoryIcon />}
              component={Link}
              to={`/wiki/${page.slug}/versions`}
              variant="outlined"
              sx={{ borderColor: 'var(--color-border-medium)', color: 'var(--color-text-primary)', '&:hover': { background: 'var(--color-border-light)' } }}
            >
              版本历史
            </Button>
          </Box>
        </Box>
      </Paper>
    </Box>
  );
}