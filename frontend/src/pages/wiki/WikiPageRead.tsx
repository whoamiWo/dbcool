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
import { wikiApi } from '@/api/wiki';
import type { WikiPage } from '@/types/wiki';

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
    <Box sx={{ maxWidth: 900, mx: 'auto', px: 2, py: 4 }}>
      <Breadcrumbs separator="›" sx={{ mb: 2 }}>
        <MuiLink component={Link} to="/wiki/kb" color="inherit">
          <HomeIcon fontSize="small" />
        </MuiLink>
        <MuiLink component={Link} to={`/wiki/kb/${page.knowledge_base_id}`} color="inherit">
          文档列表
        </MuiLink>
        <Typography color="textPrimary">{page.title}</Typography>
      </Breadcrumbs>

      <Paper sx={{ p: 4 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 3 }}>
          <Typography variant="h3" component="h1">{page.title}</Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Chip
              label={page.status}
              color={page.status === 'PUBLISHED' ? 'success' : page.status === 'ARCHIVED' ? 'default' : 'warning'}
              size="small"
            />
            <Typography variant="caption" color="textSecondary">
              v{page.version}
            </Typography>
          </Box>
        </Box>

        <Divider sx={{ mb: 3 }} />

        <Box sx={{ mb: 4 }}>
          <Typography variant="body1" component="div" sx={{ whiteSpace: 'pre-wrap', lineHeight: 1.8 }}>
            {page.content}
          </Typography>
        </Box>

        <Divider sx={{ mb: 3 }} />

        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Typography variant="caption" color="textSecondary">
            创建于 {new Date(page.created_at).toLocaleString('zh-CN')}
            {page.created_by && ` · 作者: ${page.created_by}`}
          </Typography>
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button
              startIcon={<EditIcon />}
              component={Link}
              to={`/wiki/${page.slug}/edit`}
              variant="outlined"
            >
              编辑
            </Button>
            <Button
              startIcon={<HistoryIcon />}
              component={Link}
              to={`/wiki/${page.slug}/versions`}
              variant="outlined"
            >
              版本历史
            </Button>
          </Box>
        </Box>
      </Paper>
    </Box>
  );
}