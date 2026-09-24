import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Box,
  Button,
  Typography,
  CircularProgress,
  Alert,
} from '@mui/material';
import {
  Save as SaveIcon,
  ArrowBack as ArrowBackIcon,
} from '@mui/icons-material';
import { wikiApi } from '@/api/wiki';
import { EnhancedMarkdownEditor } from '@/components/wiki/EnhancedMarkdownEditor';

export function WikiPageEditPage() {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['wiki-page', slug],
    queryFn: async () => {
      const res = await wikiApi.getPageBySlug(slug!);
      // envelope 兼容
      return (res as any)?.data ?? res;
    },
    enabled: !!slug,
  });

  useEffect(() => {
    if (data) {
      setTitle(data.title || '');
      setContent(data.content || '');
    }
  }, [data]);

  const updateMutation = useMutation({
    mutationFn: (body: { title?: string; content?: string; slug?: string }) =>
      wikiApi.updatePage(data!.id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wiki-page', slug] });
      setSuccess(true);
      setTimeout(() => setSuccess(false), 2000);
    },
    onError: (e: any) => {
      setError(e?.response?.data?.message || '保存失败');
    },
  });

  const handleSave = () => {
    if (!title.trim() || !content.trim()) {
      setError('标题和内容必填');
      return;
    }
    updateMutation.mutate({ title, content });
  };

  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (!data) {
    return (
      <Box sx={{ p: 4, textAlign: 'center' }}>
        <Typography variant="h5" color="error">页面不存在</Typography>
        <Button sx={{ mt: 2 }} onClick={() => navigate(-1)}>
          返回
        </Button>
      </Box>
    );
  }

  return (
    <Box sx={{ maxWidth: 1200, mx: 'auto', px: 2, py: 4 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Button startIcon={<ArrowBackIcon />} onClick={() => navigate(`/wiki/${slug}`)}>
          返回
        </Button>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button
            variant="contained"
            startIcon={<SaveIcon />}
            onClick={handleSave}
            disabled={updateMutation.isPending}
          >
            保存
          </Button>
        </Box>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {success && (
        <Alert severity="success" sx={{ mb: 2 }}>
          保存成功
        </Alert>
      )}

      <EnhancedMarkdownEditor
        page={data}
        title={title}
        onTitleChange={setTitle}
        content={content}
        onContentChange={setContent}
      />
    </Box>
  );
}