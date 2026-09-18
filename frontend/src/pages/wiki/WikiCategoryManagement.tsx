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
import { wikiApi } from '@/api/wiki';
import type { WikiCategory } from '@/types/wiki';

export function WikiCategoryManagementPage() {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [editingCategory, setEditingCategory] = useState<WikiCategory | null>(null);
  const [name, setName] = useState('');
  const [parentId, setParentId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const { data: categoriesData, isLoading } = useQuery({
    queryKey: ['wiki-categories'],
    queryFn: () => wikiApi.listCategories(),
    select: (r: any) => {
      const data = Array.isArray(r) ? r : r?.data || [];
      return data as WikiCategory[];
    },
  });

  const createMutation = useMutation({
    mutationFn: (body: { name: string; slug: string; parent_id?: string }) =>
      wikiApi.createCategory(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-categories'] });
      setOpen(false);
      setName('');
      setParentId(null);
      setSuccess(true);
      setTimeout(() => setSuccess(false), 2000);
    },
    onError: (e: any) => {
      setError(e?.response?.data?.message || '创建失败');
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
      setError(e?.response?.data?.message || '更新失败');
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
                      <IconButton aria-label={`编辑${category.name}`} size="small" onClick={(e) => { e.stopPropagation(); handleEdit(category); }}>
                        <EditIcon fontSize="small" />
                      </IconButton>
                      <IconButton aria-label={`删除${category.name}`} size="small" color="error" onClick={(e) => { e.stopPropagation(); deleteMutation.mutate(category.id); }}>
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
      setError('名称必填');
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
            📂 分类管理
          </Typography>
          <Button
            size="small"
            sx={{ mt: 1 }}
            startIcon={<ArrowBackIcon />}
            onClick={() => (window.location.href = '/wiki/kb')}
          >
            返回知识库列表
          </Button>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={handleAdd}
        >
          新建分类
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {success && (
        <Alert severity="success" sx={{ mb: 2 }}>
          操作成功
        </Alert>
      )}

      {isLoading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      ) : categories.length === 0 ? (
        <Paper sx={{ p: 4, textAlign: 'center' }}>
          <Typography color="textSecondary">暂无分类，点击上方按钮创建</Typography>
        </Paper>
      ) : (
        <Paper sx={{ p: 3 }}>
          <CategoryTree items={treeData} />
        </Paper>
      )}

      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{editingCategory ? '编辑分类' : '新建分类'}</DialogTitle>
        <DialogContent>
          <Box component="form" sx={{ mt: 2, display: 'flex', flexDirection: 'column', gap: 2 }}>
            <TextField
              label="分类名称"
              value={name}
              onChange={(e) => setName(e.target.value)}
              fullWidth
              required
              autoFocus
            />
            <TextField
              select
              label="父分类"
              value={parentId || ''}
              onChange={(e) => setParentId(e.target.value || null)}
              fullWidth
              slotProps={{ select: { native: true } }}
            >
              <option value="">无（作为根分类）</option>
              {categories
                .filter(c => c.id !== editingCategory?.id)
                .map(c => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
            </TextField>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>取消</Button>
          <Button
            variant="contained"
            onClick={handleSubmit}
            disabled={createMutation.isPending || updateMutation.isPending}
          >
            {editingCategory ? '更新' : '创建'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}