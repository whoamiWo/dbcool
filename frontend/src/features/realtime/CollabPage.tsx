import { useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, CircularProgress, Typography } from '@mui/material';
import apiClient from '@/api/client';
import CollabEditor from './CollabEditor';

interface WikiPageDto {
  id: string;
  title: string;
  content?: string;
}

/**
 * 协同编辑页 — 以 Wiki 文档为协作载体(docId 即 wiki page id)。
 */
export default function CollabPage() {
  const { docId } = useParams<{ docId: string }>();
  const queryClient = useQueryClient();

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['wiki-page', docId],
    queryFn: async () => {
      const r = await apiClient.get<{ code: number; data: WikiPageDto }>(
        `/wiki/pages/${docId}`,
      );
      return ((r as { data?: WikiPageDto })?.data ?? r) as WikiPageDto;
    },
    enabled: !!docId,
  });

  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (isError || !data) {
    return (
      <Box sx={{ p: 3 }}>
        <Alert severity="error">
          加载文档失败:{(error as Error)?.message ?? '文档不存在'}
        </Alert>
      </Box>
    );
  }

  const handleSave = async (content: string) => {
    await apiClient.put(`/wiki/pages/${docId}`, { content });
    queryClient.invalidateQueries({ queryKey: ['wiki-page', docId] });
  };

  return (
    <Box sx={{ p: 3, maxWidth: 1100, mx: 'auto' }}>
      <Typography variant="caption" color="text.secondary">
        协同编辑 · 文档 ID {docId}
      </Typography>
      <CollabEditor
        docId={docId as string}
        title={data.title}
        initialContent={data.content ?? ''}
        onSave={handleSave}
      />
    </Box>
  );
}
