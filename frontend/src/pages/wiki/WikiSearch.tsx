import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Box,
  Button,
  TextField,
  Typography,
  Paper,
  List,
  ListItem,
  ListItemText,
  CircularProgress,
  Divider,
  FormControl,
  InputLabel,
  Select,
  MenuItem,
  Chip,
} from '@mui/material';
import { Search as SearchIcon, FilterList as FilterIcon } from '@mui/icons-material';
import { wikiApi } from '@/api/wiki';


export function WikiSearchPage() {
  const [query, setQuery] = useState('');
  const [searched, setSearched] = useState(false);
  const [showFilters, setShowFilters] = useState(false);
  const [selectedKb, setSelectedKb] = useState<string>('');
  const [selectedStatus, setSelectedStatus] = useState<string>('');

  // 获取知识库列表用于筛选
  const { data: kbData } = useQuery({
    queryKey: ['wiki-kbs'],
    queryFn: async () => {
      const res = await wikiApi.listKb();
      return Array.isArray(res) ? res : (res as any)?.data || [];
    },
  });

  const { data, isLoading } = useQuery({
    queryKey: ['wiki-search', query, searched, selectedKb, selectedStatus],
    queryFn: async () => {
      if (!query.trim()) return { data: [], total: 0 };
      const params: any = { q: query };
      if (selectedKb) params.kbId = selectedKb;
      if (selectedStatus) params.status = selectedStatus;
      const res = await wikiApi.search(query, params);
      return {
        data: Array.isArray(res) ? res : (res as any)?.data || [],
        total: Array.isArray(res) ? res.length : (res as any)?.total || 0,
      };
    },
    enabled: searched && query.trim().length > 0,
  });

  const handleSearch = () => {
    setSearched(true);
  };

  const results = data?.data || [];
  const knowledgeBases = Array.isArray(kbData) ? kbData : (kbData as any)?.data || [];

  return (
    <Box sx={{ maxWidth: 900, mx: 'auto', px: 2, py: 4 }}>
      <Typography variant="h4" component="h1" sx={{ mb: 3 }}>
        🔍 知识库搜索
      </Typography>

      {/* 搜索框 */}
      <Box sx={{ display: 'flex', gap: 1, mb: 2 }}>
        <TextField
          label="搜索内容"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
          fullWidth
          placeholder="输入关键词搜索文档..."
        />
        <Button
          variant="contained"
          onClick={handleSearch}
          startIcon={<SearchIcon />}
        >
          搜索
        </Button>
        <Button
          variant="outlined"
          onClick={() => setShowFilters(!showFilters)}
          startIcon={<FilterIcon />}
        >
          筛选
        </Button>
      </Box>

      {/* 高级筛选 */}
      {showFilters && (
        <Paper sx={{ p: 2, mb: 2 }}>
          <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
            <FormControl size="small" sx={{ minWidth: 200 }}>
              <InputLabel>知识库</InputLabel>
              <Select
                value={selectedKb}
                label="知识库"
                onChange={(e) => setSelectedKb(e.target.value)}
              >
                <MenuItem value="">全部知识库</MenuItem>
                {knowledgeBases.map((kb: any) => (
                  <MenuItem key={kb.id} value={kb.id}>{kb.name}</MenuItem>
                ))}
              </Select>
            </FormControl>

            <FormControl size="small" sx={{ minWidth: 150 }}>
              <InputLabel>状态</InputLabel>
              <Select
                value={selectedStatus}
                label="状态"
                onChange={(e) => setSelectedStatus(e.target.value)}
              >
                <MenuItem value="">全部状态</MenuItem>
                <MenuItem value="DRAFT">草稿</MenuItem>
                <MenuItem value="PUBLISHED">已发布</MenuItem>
                <MenuItem value="ARCHIVED">已归档</MenuItem>
              </Select>
            </FormControl>
          </Box>
          {(selectedKb || selectedStatus) && (
            <Box sx={{ mt: 1 }}>
              <Chip label="知识库" size="small" onDelete={() => setSelectedKb('')} />
              <Chip label="状态" size="small" onDelete={() => setSelectedStatus('')} sx={{ ml: 1 }} />
            </Box>
          )}
        </Paper>
      )}

      {isLoading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      ) : searched && results.length === 0 ? (
        <Paper sx={{ p: 4, textAlign: 'center' }}>
          <Typography color="textSecondary">
            未找到与 "{query}" 相关的文档
          </Typography>
        </Paper>
      ) : searched && results.length > 0 ? (
        <Paper>
          <Box sx={{ px: 2, py: 1, borderBottom: '1px solid #e0e0e0' }}>
            <Typography variant="body2" color="textSecondary">
              找到 {results.length} 条结果
            </Typography>
          </Box>
          <List>
            {results.map((page: any, idx: number) => (
              <Box key={page.id || idx}>
                <ListItem
                  component="a"
                  href={`/wiki/${page.slug}`}
                  sx={{ textDecoration: 'none', color: 'inherit' }}
                >
                  <ListItemText
                    primary={
                      <Typography variant="subtitle1" sx={{ fontWeight: 500 }}>
                        {page.title}
                      </Typography>
                    }
                    secondary={
                      <>
                        <Typography variant="caption" color="textSecondary">
                          Slug: {page.slug} · 更新时间: {new Date(page.updated_at).toLocaleString('zh-CN')}
                        </Typography>
                        {page.content && (
                          <Typography
                            variant="body2"
                            color="textSecondary"
                            sx={{ mt: 0.5, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}
                          >
                            {page.content.slice(0, 200)}...
                          </Typography>
                        )}
                      </>
                    }
                  />
                </ListItem>
                {idx < results.length - 1 && <Divider />}
              </Box>
            ))}
          </List>
        </Paper>
      ) : (
        <Paper sx={{ p: 4, textAlign: 'center' }}>
          <Typography color="textSecondary">
            输入关键词开始搜索
          </Typography>
        </Paper>
      )}
    </Box>
  );
}