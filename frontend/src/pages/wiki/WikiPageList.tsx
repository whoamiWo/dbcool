import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Box,
  Button,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  Typography,
  IconButton,
  Chip,
  CircularProgress,
  Tooltip,
  Alert,
} from '@mui/material';
import {
  Add as AddIcon,
  Edit as EditIcon,
  Delete as DeleteIcon,
  History as HistoryIcon,
  Publish as PublishIcon,
  Archive as ArchiveIcon,
} from '@mui/icons-material';
import { wikiApi } from '@/api/wiki';
import type { WikiPage } from '@/types/wiki';

export function WikiPageListPage() {
  const { id: kbId } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newSlug, setNewSlug] = useState('');
  const [newContent, setNewContent] = useState('');
  const [error, setError] = useState<string | null>(null);

  const { data: kbData, isLoading: kbLoading } = useQuery({
    queryKey: ['wiki-kb', kbId],
    queryFn: () => wikiApi.getKb(kbId!),
    enabled: !!kbId,
  });

  const { data: pagesData, isLoading: pagesLoading } = useQuery({
    queryKey: ['wiki-pages', kbId],
    queryFn: () => wikiApi.listPages({ kbId }),
    enabled: !!kbId,
  });

  const createMutation = useMutation({
    mutationFn: (body: { knowledge_base_id: string; slug: string; title: string; content?: string }) =>
      wikiApi.createPage(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-pages', kbId] });
      setOpen(false);
      setNewTitle('');
      setNewSlug('');
      setNewContent('');
      setError(null);
    },
    onError: (e: any) => {
      setError(e?.response?.data?.message || '创建失败');
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => wikiApi.deletePage(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-pages', kbId] });
    },
  });

  const publishMutation = useMutation({
    mutationFn: (id: string) => wikiApi.publishPage(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-pages', kbId] });
    },
  });

  const archiveMutation = useMutation({
    mutationFn: (id: string) => wikiApi.archivePage(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-pages', kbId] });
    },
  });

  const handleCreate = () => {
    if (!newTitle.trim() || !newSlug.trim()) {
      setError('标题和 slug 必填');
      return;
    }
    createMutation.mutate({
      knowledge_base_id: kbId!,
      title: newTitle,
      slug: newSlug,
      content: newContent,
    });
  };

  const kb = (kbData as any)?.data || kbData;
  const pages = Array.isArray(pagesData) ? pagesData : (pagesData as any)?.data || [];

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Box>
          {kbLoading ? (
            <CircularProgress size={24} />
          ) : (
            <Typography variant="h4" component="h1">
              📚 {(kb as any)?.name || kb?.name}
            </Typography>
          )}
          <Button
            size="small"
            sx={{ mt: 1 }}
            onClick={() => (window.location.href = '/wiki/kb')}
          >
            ← 返回知识库列表
          </Button>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setOpen(true)}
        >
          新建文档
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Box sx={{ display: 'flex', gap: 2, mb: 2 }}>
        <Button
          size="small"
          variant="outlined"
          onClick={() => window.location.href = `/wiki/kb/${kbId}/categories`}
        >
          分类管理
        </Button>
        <Button
          size="small"
          variant="outlined"
          onClick={() => window.location.href = `/wiki/search`}
        >
          全局搜索
        </Button>
      </Box>

      {pagesLoading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      ) : pages.length === 0 ? (
        <Paper>
          <Box sx={{ p: 4, textAlign: 'center' }}>
            <Typography color="textSecondary">暂无文档，点击上方按钮创建</Typography>
          </Box>
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>标题</TableCell>
                <TableCell>状态</TableCell>
                <TableCell>版本</TableCell>
                <TableCell>更新时间</TableCell>
                <TableCell align="right">操作</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {pages.map((page: WikiPage) => (
                <TableRow key={page.id}>
                  <TableCell>
                    <Link to={`/wiki/${page.slug}`} style={{ textDecoration: 'none' }}>
                      {page.title}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Chip
                      label={page.status}
                      size="small"
                      color={page.status === 'PUBLISHED' ? 'success' : page.status === 'ARCHIVED' ? 'default' : 'warning'}
                    />
                  </TableCell>
                  <TableCell>v{page.version}</TableCell>
                  <TableCell>
                    {new Date(page.updated_at).toLocaleString('zh-CN')}
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip title="编辑">
                      <IconButton size="small" onClick={() => window.location.href = `/wiki/${page.slug}/edit`}>
                        <EditIcon />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title="版本历史">
                      <IconButton size="small" onClick={() => window.location.href = `/wiki/${page.slug}/versions`}>
                        <HistoryIcon />
                      </IconButton>
                    </Tooltip>
                    {page.status === 'DRAFT' ? (
                      <Tooltip title="发布">
                        <IconButton size="small" color="success" onClick={() => publishMutation.mutate(page.id)}>
                          <PublishIcon />
                        </IconButton>
                      </Tooltip>
                    ) : (
                      <Tooltip title="归档">
                        <IconButton size="small" color="default" onClick={() => archiveMutation.mutate(page.id)}>
                          <ArchiveIcon />
                        </IconButton>
                      </Tooltip>
                    )}
                    <Tooltip title="删除">
                      <IconButton size="small" color="error" onClick={() => deleteMutation.mutate(page.id)}>
                        <DeleteIcon />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>新建文档</DialogTitle>
        <DialogContent>
          <Box component="form" sx={{ mt: 2, display: 'flex', flexDirection: 'column', gap: 2 }}>
            <TextField
              label="标题"
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              fullWidth
              required
            />
            <TextField
              label="Slug"
              value={newSlug}
              onChange={(e) => setNewSlug(e.target.value)}
              fullWidth
              helperText="唯一标识，如：my-doc"
              required
            />
            <TextField
              label="内容"
              value={newContent}
              onChange={(e) => setNewContent(e.target.value)}
              multiline
              rows={6}
              fullWidth
              placeholder="支持 Markdown 格式"
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>取消</Button>
          <Button variant="contained" onClick={handleCreate} disabled={createMutation.isPending}>
            创建
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}