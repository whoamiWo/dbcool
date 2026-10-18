import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
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
import { IconButtonA11y } from '@/components/IconButtonA11y';
import EmptyState from '@/components/EmptyState';
import {
  Add as AddIcon,
  Edit as EditIcon,
  Delete as DeleteIcon,
  History as HistoryIcon,
  Publish as PublishIcon,
  Archive as ArchiveIcon,
} from '@mui/icons-material';
import { useIsMobile } from '@/hooks/useIsMobile';
import { wikiApi } from '@/api/wiki';
import type { WikiPage } from '@/types/wiki';

export function WikiPageListPage() {
  const { t } = useTranslation();
  const { id: kbId } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const isMobile = useIsMobile();
  const [open, setOpen] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newSlug, setNewSlug] = useState('');
  const [newContent, setNewContent] = useState('');
  const [error, setError] = useState<string | null>(null);

  const { data: kbData, isLoading: kbLoading, error: kbError } = useQuery({
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
      setError(e?.response?.data?.message || t('wikiPageList.createFailed'));
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
      setError(t('wikiPageList.titleAndSlugRequired'));
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
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3, flexDirection: isMobile ? 'column' : 'row', gap: 1 }}>
        <Box>
          {kbLoading ? (
            <CircularProgress size={24} />
          ) : kbError ? (
            // PHASE98 R3: 原实现把**任何**错误都硬编码成「无权限访问 / 拒绝访问（403）」。
            // 网络超时、500、404 都会显示 403，用户会去申请权限而不是重试，
            // 错误诊断成本被转嫁给客户支持。改为展示真实错误。
            <Alert severity="error" role="alert">
              {t('wikiPageList.kbLoadFailed', { error: (kbError as Error)?.message || t('common.loadFailed') })}
            </Alert>
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
            {t('wikiPageList.backToKbList')}
          </Button>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setOpen(true)}
          fullWidth={isMobile}
        >
          {t('wikiPageList.newDoc')}
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
          {t('wikiPageList.categoryManagement')}
        </Button>
        <Button
          size="small"
          variant="outlined"
          onClick={() => window.location.href = `/wiki/search`}
        >
          {t('wikiPageList.globalSearch')}
        </Button>
      </Box>

      {pagesLoading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      ) : pages.length === 0 ? (
        <EmptyState
          title={t('wikiPageList.emptyTitle')}
          description={t('wikiPageList.emptyDesc')}
          actionLabel={t('wikiPageList.newDoc')}
          onAction={handleCreate}
        />
      ) : isMobile ? (
        <Box className="wiki-page-list" sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          {pages.map((page: WikiPage) => (
            <Paper key={page.id} sx={{ p: 2 }}>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
                <Typography variant="subtitle1" component={Link} to={`/wiki/${page.slug}`} sx={{ textDecoration: 'none', color: 'var(--color-primary-400)' }}>
                  {page.title}
                </Typography>
                <Chip label={page.status} size="small" color={page.status === 'PUBLISHED' ? 'success' : page.status === 'ARCHIVED' ? 'default' : 'warning'} />
              </Box>
              <Box sx={{ display: 'flex', gap: 2, fontSize: 12, color: 'var(--color-text-muted)', mb: 1 }}>
                <span>v{page.version}</span>
                <span>{new Date(page.updated_at).toLocaleString('zh-CN')}</span>
              </Box>
              {/*
                PHASE98 R4: 移动端卡片视图此前既无 Tooltip 也无 aria-label
                —— 桌面表格视图（:239 起）至少有 Tooltip 兜底，卡片视图完全没有，
                屏幕阅读器读到的是一串"按钮"。这里补齐可访问名；
                删除是破坏性操作，另加二次确认。
              */}
              <Box sx={{ display: 'flex', gap: 1 }}>
                <IconButtonA11y
                  size="small"
                  aria-label={t('wikiPageList.editDoc', { title: page.title })}
                  onClick={() => (window.location.href = `/wiki/${page.slug}/edit`)}
                >
                  <EditIcon fontSize="small" />
                </IconButtonA11y>
                <IconButtonA11y
                  size="small"
                  aria-label={t('wikiPageList.viewHistory', { title: page.title })}
                  onClick={() => (window.location.href = `/wiki/${page.slug}/versions`)}
                >
                  <HistoryIcon fontSize="small" />
                </IconButtonA11y>
                {page.status === 'DRAFT' ? (
                  <IconButtonA11y
                    size="small"
                    color="success"
                    aria-label={t('wikiPageList.publishDoc', { title: page.title })}
                    onClick={() => publishMutation.mutate(page.id)}
                  >
                    <PublishIcon fontSize="small" />
                  </IconButtonA11y>
                ) : (
                  <IconButtonA11y
                    size="small"
                    color="default"
                    aria-label={t('wikiPageList.archiveDoc', { title: page.title })}
                    onClick={() => archiveMutation.mutate(page.id)}
                  >
                    <ArchiveIcon fontSize="small" />
                  </IconButtonA11y>
                )}
                <IconButtonA11y
                  size="small"
                  color="error"
                  aria-label={t('wikiPageList.deleteDoc', { title: page.title })}
                  onClick={() => {
                    if (window.confirm(t('wikiPageList.deleteConfirm', { title: page.title }))) {
                      deleteMutation.mutate(page.id);
                    }
                  }}
                >
                  <DeleteIcon fontSize="small" />
                </IconButtonA11y>
              </Box>
            </Paper>
          ))}
        </Box>
      ) : (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>{t('wikiPageList.colTitle')}</TableCell>
                <TableCell>{t('wikiPageList.colStatus')}</TableCell>
                <TableCell>{t('wikiPageList.colVersion')}</TableCell>
                <TableCell>{t('wikiPageList.colUpdatedAt')}</TableCell>
                <TableCell align="right">{t('wikiPageList.colAction')}</TableCell>
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
                    {/*
                      PHASE98 R4: 桌面视图此前只包了 Tooltip —— MUI Tooltip 默认
                      describeChild=false，运行时会把 title 作为 aria-label 注入子元素，
                      静态不可见且依赖 MUI 内部行为。这里补显式 aria-label。
                    */}
                    <Tooltip title={t('wikiPageList.tooltipEdit')}>
                      <IconButton size="small" aria-label={t('wikiPageList.editDoc', { title: page.title })} onClick={() => window.location.href = `/wiki/${page.slug}/edit`}>
                        <EditIcon />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title={t('wikiPageList.tooltipHistory')}>
                      <IconButton size="small" aria-label={t('wikiPageList.viewHistory', { title: page.title })} onClick={() => window.location.href = `/wiki/${page.slug}/versions`}>
                        <HistoryIcon />
                      </IconButton>
                    </Tooltip>
                    {page.status === 'DRAFT' ? (
                      <Tooltip title={t('wikiPageList.tooltipPublish')}>
                        <IconButton size="small" color="success" aria-label={t('wikiPageList.publishDoc', { title: page.title })} onClick={() => publishMutation.mutate(page.id)}>
                          <PublishIcon />
                        </IconButton>
                      </Tooltip>
                    ) : (
                      <Tooltip title={t('wikiPageList.tooltipArchive')}>
                        <IconButton size="small" color="default" aria-label={t('wikiPageList.archiveDoc', { title: page.title })} onClick={() => archiveMutation.mutate(page.id)}>
                          <ArchiveIcon />
                        </IconButton>
                      </Tooltip>
                    )}
                    <Tooltip title={t('wikiPageList.tooltipDelete')}>
                      <IconButton
                        size="small"
                        color="error"
                        aria-label={t('wikiPageList.deleteDoc', { title: page.title })}
                        onClick={() => {
                          if (window.confirm(t('wikiPageList.deleteConfirm', { title: page.title }))) {
                            deleteMutation.mutate(page.id);
                          }
                        }}
                      >
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
        <DialogTitle>{t('wikiPageList.dialogTitle')}</DialogTitle>
        <DialogContent>
          <Box component="form" sx={{ mt: 2, display: 'flex', flexDirection: 'column', gap: 2 }}>
            <TextField
              label={t('wikiPageList.fieldTitle')}
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              fullWidth
              required
            />
            <TextField
              label={t('wikiPageList.fieldSlug')}
              value={newSlug}
              onChange={(e) => setNewSlug(e.target.value)}
              fullWidth
              helperText={t('wikiPageList.slugHelper')}
              required
            />
            <TextField
              label={t('wikiPageList.fieldContent')}
              value={newContent}
              onChange={(e) => setNewContent(e.target.value)}
              multiline
              rows={6}
              fullWidth
              placeholder={t('wikiPageList.contentPlaceholder')}
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>{t('wikiPageList.btnCancel')}</Button>
          <Button variant="contained" onClick={handleCreate} disabled={createMutation.isPending}>
            {t('wikiPageList.btnCreate')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}