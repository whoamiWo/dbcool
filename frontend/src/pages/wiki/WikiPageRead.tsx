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
      const listRes = await wikiApi.listPages({ kbId: undefined });
      const pages = Array.isArray(listRes) ? listRes : (listRes as any)?.data || [];
      const page = pages.find((p: WikiPage) => p.slug === slug);
      if (!page) throw new Error('Page not found');
      return page;
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

      <Paper sx={{ p: 4, background: 'rgba(30, 41, 59, 0.7)', border: '1px solid rgba(255, 255, 255, 0.1)', borderRadius: 12 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 3 }}>
          <Typography variant="h3" component="h1" sx={{ color: '#f8fafc' }}>{page.title}</Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Chip
              label={page.status}
              color={page.status === 'PUBLISHED' ? 'success' : page.status === 'ARCHIVED' ? 'default' : 'warning'}
              size="small"
            />
            <Typography variant="caption" sx={{ color: '#94a3b8' }}>
              v{page.version}
            </Typography>
          </Box>
        </Box>

        <Divider sx={{ mb: 3, borderColor: 'rgba(255, 255, 255, 0.1)' }} />

        <Box sx={{ mb: 4 }}>
          <Box
            sx={{
              '& h1': { fontSize: '2rem', fontWeight: 'bold', margin: '1rem 0 0.5rem', color: '#f8fafc' },
              '& h2': { fontSize: '1.5rem', fontWeight: 'bold', margin: '0.75rem 0 0.5rem', color: '#f8fafc' },
              '& h3': { fontSize: '1.25rem', fontWeight: 'bold', margin: '0.5rem 0', color: '#e2e8f0' },
              '& p': { margin: '0.5rem 0', lineHeight: 1.8, color: '#e2e8f0' },
              '& ul': { paddingLeft: 2, margin: '0.5rem 0' },
              '& ol': { paddingLeft: 2, margin: '0.5rem 0' },
              '& li': { margin: '0.25rem 0', color: '#e2e8f0' },
              '& code': {
                background: 'rgba(30, 41, 59, 0.8)', padding: '0.2em 0.4em', borderRadius: 3, fontFamily: 'monospace', color: '#c7d2fe',
              },
              '& pre': {
                background: '#1e1e1e', padding: 1.5, borderRadius: 1, overflowX: 'auto', margin: '0.5rem 0',
              },
              '& blockquote': {
                borderLeft: '4px solid #6366f1', paddingLeft: 1, margin: 0.5, color: '#94a3b8',
              },
              '& table': { borderCollapse: 'collapse', width: '100%', margin: '0.5rem 0' },
              '& th, &td': { border: '1px solid rgba(255, 255, 255, 0.1)', padding: 8, textAlign: 'left', color: '#e2e8f0' },
              '& a': { color: '#818cf8' },
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

        <Divider sx={{ mb: 3, borderColor: 'rgba(255, 255, 255, 0.1)' }} />

        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Typography variant="caption" sx={{ color: '#94a3b8' }}>
            创建于 {new Date(page.created_at).toLocaleString('zh-CN')}
            {page.created_by && ` · 作者: ${page.created_by}`}
          </Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button
              startIcon={<EditIcon />}
              component={Link}
              to={`/wiki/${page.slug}/edit`}
              variant="outlined"
              sx={{ borderColor: 'rgba(255, 255, 255, 0.2)', color: '#f8fafc', '&:hover': { background: 'rgba(255, 255, 255, 0.05)' } }}
            >
              编辑
            </Button>
            <Button
              startIcon={<HistoryIcon />}
              component={Link}
              to={`/wiki/${page.slug}/versions`}
              variant="outlined"
              sx={{ borderColor: 'rgba(255, 255, 255, 0.2)', color: '#f8fafc', '&:hover': { background: 'rgba(255, 255, 255, 0.05)' } }}
            >
              版本历史
            </Button>
          </Box>
        </Box>
      </Paper>
    </Box>
  );
}