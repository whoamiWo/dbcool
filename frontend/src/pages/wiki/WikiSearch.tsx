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
import { useTranslation } from 'react-i18next';
import i18n from '@/i18n';
import { wikiApi } from '@/api/wiki';


export function WikiSearchPage() {
  const { t } = useTranslation();
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
        {t('wiki.searchTitle')}
      </Typography>

      {/* 搜索框 */}
      <Box sx={{ display: 'flex', gap: 1, mb: 2 }}>
        <TextField
          label={t('wiki.searchContent')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
          fullWidth
          placeholder={t('wiki.searchPlaceholder')}
        />
        <Button
          variant="contained"
          onClick={handleSearch}
          startIcon={<SearchIcon />}
        >
          {t('wiki.searchBtn')}
        </Button>
        <Button
          variant="outlined"
          onClick={() => setShowFilters(!showFilters)}
          startIcon={<FilterIcon />}
        >
          {t('wiki.filterBtn')}
        </Button>
      </Box>

      {/* 高级筛选 */}
      {showFilters && (
        <Paper sx={{ p: 2, mb: 2 }}>
          <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
            <FormControl size="small" sx={{ minWidth: 200 }}>
              <InputLabel>{t('wiki.knowledgeBase')}</InputLabel>
              <Select
                value={selectedKb}
                label={t('wiki.knowledgeBase')}
                onChange={(e) => setSelectedKb(e.target.value)}
              >
                <MenuItem value="">{t('wiki.allKb')}</MenuItem>
                {knowledgeBases.map((kb: any) => (
                  <MenuItem key={kb.id} value={kb.id}>{kb.name}</MenuItem>
                ))}
              </Select>
            </FormControl>

            <FormControl size="small" sx={{ minWidth: 150 }}>
              <InputLabel>{t('wiki.status')}</InputLabel>
              <Select
                value={selectedStatus}
                label={t('wiki.status')}
                onChange={(e) => setSelectedStatus(e.target.value)}
              >
                <MenuItem value="">{t('wiki.allStatus')}</MenuItem>
                <MenuItem value="DRAFT">{t('wiki.statusDraft')}</MenuItem>
                <MenuItem value="PUBLISHED">{t('wiki.statusPublished')}</MenuItem>
                <MenuItem value="ARCHIVED">{t('wiki.statusArchived')}</MenuItem>
              </Select>
            </FormControl>
          </Box>
          {(selectedKb || selectedStatus) && (
            <Box sx={{ mt: 1 }}>
              <Chip label={t('wiki.knowledgeBase')} size="small" onDelete={() => setSelectedKb('')} />
              <Chip label={t('wiki.status')} size="small" onDelete={() => setSelectedStatus('')} sx={{ ml: 1 }} />
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
            {t('wiki.noResults', { query })}
          </Typography>
        </Paper>
      ) : searched && results.length > 0 ? (
        <Paper>
          <Box sx={{ px: 2, py: 1, borderBottom: '1px solid var(--color-border-light)' }}>
            <Typography variant="body2" color="textSecondary">
              {t('wiki.resultCount', { count: results.length })}
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
                          {t('wiki.slugUpdated', { slug: page.slug, time: new Date(page.updated_at).toLocaleString(i18n.language) })}
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
            {t('wiki.startSearch')}
          </Typography>
        </Paper>
      )}
    </Box>
  );
}