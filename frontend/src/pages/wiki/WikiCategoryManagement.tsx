import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Box,
  Button,
  Typography,
  Paper,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  IconButton,
  CircularProgress,
  Alert,
  Chip,
  List,
  ListItemButton,
  ListItemText,
} from '@mui/material';
import {
  Add as AddIcon,
  ArrowBack as ArrowBackIcon,
  ExpandMore,
  ChevronRight,
  Edit as EditIcon,
  Delete as DeleteIcon,
} from '@mui/icons-material';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { wikiApi } from '@/api/wiki';
import EmptyState from '@/components/EmptyState';
import type { WikiCategory } from '@/types/wiki';

export function WikiCategoryManagementPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [editingCategory, setEditingCategory] = useState<WikiCategory | null>(null);
  const [name, setName] = useState('');
  const [parentId, setParentId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  // PHASE97: 分类端点必须带 kbId（后端只有 /api/wiki/kb/{kbId}/categories，无平铺版）。
  // 路由为 /wiki/kb/:kbId/categories，kbId 从 URL 取，而非无参调用不存在的平铺端点。
  const { kbId } = useParams<{ kbId: string }>();

  const { data: categoriesData, isLoading } = useQuery({
    queryKey: ['wiki-categories', kbId],
    queryFn: () => wikiApi.listCategories(kbId!),
    select: (r: any) => {
      const data = Array.isArray(r) ? r : r?.data || [];
      return data as WikiCategory[];
    },
  });

  const createMutation = useMutation({
    mutationFn: (body: { name: string; slug: string; parent_id?: string }) =>
      wikiApi.createCategory({ ...body, knowledge_base_id: kbId! }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-categories'] });
      setOpen(false);
      setName('');
      setParentId(null);
      setSuccess(true);
      setTimeout(() => setSuccess(false), 2000);
    },
    onError: (e: any) => {
      setError(e?.response?.data?.message || t('wiki.createFailed'));
    },
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, body }: { id: string; body: { name?: string; slug?: string; parent_id?: string } }) =>
      wikiApi.updateCategory(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-categories'] });
      setOpen(false);
      setEditingCategory(null);
      setSuccess(true);
      setTimeout(() => setSuccess(false), 2000);
    },
    onError: (e: any) => {
      setError(e?.response?.data?.message || t('wiki.createFailed'));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => wikiApi.deleteCategory(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-categories'] });
    },
  });

  const categories: WikiCategory[] = categoriesData || [];

  interface CategoryNode extends WikiCategory {
    children?: CategoryNode[];
  }

  const buildTree = (items: WikiCategory[], parentId: string | null = null): CategoryNode[] => {
    return items
      .filter(item => item.parent_id === parentId)
      .map(item => ({
        ...item,
        children: buildTree(items, item.id),
      }));
  };

  const treeData = buildTree(categories);

  const CategoryTree = ({ items, level = 0 }: { items: CategoryNode[]; level?: number }) => {
    const [expanded, setExpanded] = useState<string[]>([]);

    return (
      <List disablePadding>
        {items.map((category) => {
          const hasChildren = category.children && category.children.length > 0;
          const isExpanded = expanded.includes(category.id);
          return (
            <Box key={category.id}>
              <ListItemButton
                onClick={() => hasChildren && setExpanded(isExpanded ? expanded.filter(id => id !== category.id) : [...expanded, category.id])}
                sx={{ pl: level * 3 }}
              >
                {hasChildren ? (
                  isExpanded ? <ExpandMore /> : <ChevronRight />
                ) : (
                  <Box sx={{ width: 24 }} />
                )}
                <ListItemText
                  primary={
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      <Typography variant="body2">{category.name}</Typography>
                      <Chip label={category.slug} size="small" variant="outlined" />
                    </Box>
                  }
                  secondary={
                    <Box sx={{ display: 'flex', gap: 0.5 }}>
                      <IconButton aria-label={t('wiki.editHint', { name: category.name })} size="small" onClick={(e) => { e.stopPropagation(); handleEdit(category); }}>
                        <EditIcon fontSize="small" />
                      </IconButton>
                      <IconButton aria-label={t('wiki.deleteHint', { name: category.name })} size="small" color="error" onClick={(e) => { e.stopPropagation(); deleteMutation.mutate(category.id); }}>
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </Box>
                  }
                />
              </ListItemButton>
              {hasChildren && isExpanded && (
                <CategoryTree items={category.children!} level={level + 1} />
              )}
            </Box>
          );
        })}
      </List>
    );
  };

  const handleAdd = () => {
    setEditingCategory(null);
    setName('');
    setParentId(null);
    setOpen(true);
  };

  const handleEdit = (category: WikiCategory) => {
    setEditingCategory(category);
    setName(category.name);
    setParentId(category.parent_id);
    setOpen(true);
  };

  const handleSubmit = () => {
    if (!name.trim()) {
      setError(t('wiki.nameRequired'));
      return;
    }
    const slug = name.trim().toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9-]/g, '');

    if (editingCategory) {
      updateMutation.mutate({
        id: editingCategory.id,
        body: { name, slug, parent_id: parentId || undefined },
      });
    } else {
      createMutation.mutate({ name, slug, parent_id: parentId || undefined });
    }
  };

  return (
    <Box sx={{ maxWidth: 900, mx: 'auto', px: 2, py: 4 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Box>
          <Typography variant="h4" component="h1">
            {t('wiki.categoryTitle')}
          </Typography>
          <Button
            size="small"
            sx={{ mt: 1 }}
            startIcon={<ArrowBackIcon />}
            onClick={() => (window.location.href = '/wiki/kb')}
          >
            {t('wiki.returnKbList')}
          </Button>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={handleAdd}
        >
          {t('wiki.newCategory')}
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {success && (
        <Alert severity="success" sx={{ mb: 2 }}>
          {t('common.save')}
        </Alert>
      )}

      {isLoading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      ) : categories.length === 0 ? (
        <EmptyState
          title={t('wiki.noCategories')}
          description={t('wiki.catCreateHint')}
          actionLabel={t('wiki.newCategory')}
          onAction={handleAdd}
        />
      ) : (
        <Paper sx={{ p: 3 }}>
          <CategoryTree items={treeData} />
        </Paper>
      )}

      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editingCategory ? t('wiki.editCategory') : t('wiki.newCategory')}</DialogTitle>
        <DialogContent>
          <Box component="form" sx={{ mt: 2, display: 'flex', flexDirection: 'column', gap: 2 }}>
            <TextField
              label={t('wiki.categoryName')}
              value={name}
              onChange={(e) => setName(e.target.value)}
              fullWidth
              required
              // eslint-disable-next-line jsx-a11y/no-autofocus -- 弹窗/面板内首个输入框自动聚焦，符合交互预期
              autoFocus
            />
            <TextField
              select
              label={t('wiki.parentCategory')}
              value={parentId || ''}
              onChange={(e) => setParentId(e.target.value || null)}
              fullWidth
              slotProps={{ select: { native: true } }}
            >
              <option value="">{t('wiki.noParent')}</option>
              {categories
                .filter(c => c.id !== editingCategory?.id)
                .map(c => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
            </TextField>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>{t('wiki.cancel')}</Button>
          <Button
            variant="contained"
            onClick={handleSubmit}
            disabled={createMutation.isPending || updateMutation.isPending}
          >
            {editingCategory ? t('wiki.update') : t('wiki.create')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}