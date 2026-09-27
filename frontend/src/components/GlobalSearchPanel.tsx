import { useTranslation } from 'react-i18next';
import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogTitle,
  DialogContent,
  List,
  ListItem,
  ListItemText,
  TextField,
  Typography,
} from '@mui/material';
import {
  Search as SearchIcon,
  Close as CloseIcon,
  Description as WikiIcon,
  TableChart as RecordIcon,
  Forum as ImIcon,
  Assignment as ProjectIcon,
  Build as AutomationIcon,
} from '@mui/icons-material';
import { useQuery } from '@tanstack/react-query';
import { searchApi, SEARCH_FACET_COLORS, type SearchResult } from '@/api/search';

/** 搜索结果项组件 */
function SearchResultItem({ result, onClick, t }: { result: SearchResult; onClick: () => void; t: (k: string) => string }) {
  const getTypeIcon = () => {
    switch (result.type) {
      case 'wiki': return <WikiIcon />;
      case 'record': return <RecordIcon />;
      case 'im': return <ImIcon />;
      case 'project': return <ProjectIcon />;
      case 'automation': return <AutomationIcon />;
      default: return <SearchIcon />;
    }
  };

  const color = SEARCH_FACET_COLORS[result.type] || 'var(--color-text-secondary)';

  return (
    <ListItem
      onClick={onClick}
      sx={{
        cursor: 'pointer',
        '&:hover': { bgcolor: 'rgba(255,255,255,0.05)' },
        borderBottom: '1px solid rgba(255,255,255,0.08)',
      }}
    >
      <Box sx={{ mr: 1, color }}>{getTypeIcon()}</Box>
      <ListItemText
        primary={
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography variant="body2" sx={{ fontWeight: 500 }}>
              {result.title || t('common.untitled')}
            </Typography>
            <Chip
              size="small"
              label={t(`search.${result.type}`) || result.type}
              sx={{
                bgcolor: `${color}20`,
                color,
                fontSize: 10,
                height: 18,
              }}
            />
          </Box>
        }
        secondary={
          <Typography variant="caption" color="text.secondary">
            {result.snippet}
          </Typography>
        }
      />
    </ListItem>
  );
}

/** 全局搜索面板 — 从 AppLayout 触发 */
export function GlobalSearchPanel() {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const navigate = useNavigate();

  const { data, isFetching, refetch } = useQuery({
    queryKey: ['search', query],
    queryFn: () => searchApi.search(query, undefined, 10),
    enabled: false, // 手动触发
  });

  const handleOpen = () => setOpen(true);
  const handleClose = () => {
    setOpen(false);
    setQuery('');
  };

  const handleSearch = useCallback((q: string) => {
    if (q.trim()) {
      refetch();
    }
  }, [refetch]);

  const handleResultClick = (result: SearchResult) => {
    handleClose();
    switch (result.type) {
      case 'wiki':
        navigate(`/wiki/${result.slug || result.id}`);
        break;
      case 'record':
        navigate(`/views/${result.id}/run`);
        break;
      case 'im':
        navigate(`/im`);
        break;
      case 'project':
        navigate(`/projects`);
        break;
      case 'automation':
        navigate(`/designer/workflows`);
        break;
      default:
        navigate('/');
    }
  };

  useEffect(() => {
    if (query.trim()) {
      handleSearch(query);
    }
  }, [query, handleSearch]);

  return (
    <>
      <Button
        onClick={handleOpen}
        sx={{
          minWidth: 'auto',
          width: 40,
          height: 40,
          borderRadius: '50%',
          p: 0,
          bgcolor: 'var(--glass-bg-light)',
          border: 'var(--glass-border)',
          '&:hover': { bgcolor: 'rgba(255,255,255,0.1)' },
        }}
      >
        <SearchIcon />
      </Button>

      <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          {t('globalSearch.title')}
          <CloseIcon sx={{ cursor: 'pointer' }} onClick={handleClose} />
        </DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            fullWidth
            placeholder={t('globalSearch.placeholder')}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            variant="outlined"
            size="small"
            sx={{ mb: 2 }}
          />
          {isFetching && <CircularProgress size={24} />}
          {data && (
            <>
              <Box sx={{ mb: 2, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                {Object.keys(SEARCH_FACET_COLORS).map((type) => (
                  <Chip
                    key={type}
                    label={`${t(`search.${type}`)} (${data.data.facets[type as keyof typeof data.data.facets] || 0})`}
                    size="small"
                    sx={{
                      bgcolor: `${SEARCH_FACET_COLORS[type as keyof typeof SEARCH_FACET_COLORS]}20`,
                      color: SEARCH_FACET_COLORS[type as keyof typeof SEARCH_FACET_COLORS],
                    }}
                  />
                ))}
              </Box>
              <Typography variant="body2" color="text.secondary">
                {t('globalSearch.resultsCount', { count: data.data.total })}
              </Typography>
              <List sx={{ pt: 1 }}>
                {data.data.results.map((r) => (
                  <SearchResultItem
                    key={r.id}
                    result={r}
                    onClick={() => handleResultClick(r)}
                    t={t}
                  />
                ))}
              </List>
            </>
          )}
          {query.trim() && !isFetching && (!data || data.data.results.length === 0) && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
              {t('globalSearch.noResults')}
            </Typography>
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}