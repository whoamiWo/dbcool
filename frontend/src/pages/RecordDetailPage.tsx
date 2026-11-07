import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Box, CircularProgress, Typography } from '@mui/material';
import apiClient from '@/api/client';

interface FieldMeta {
  name: string;
  label?: string;
  type?: string;
}

interface CollectionMeta {
  name: string;
  title?: string;
  fields?: FieldMeta[];
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; meta: CollectionMeta; record: Record<string, unknown> }
  | { status: 'notfound' }
  | { status: 'error'; message: string };

/**
 * R1a：通用记录详情页（搜索 deeplink `/records/:collection/:recordId`）。
 *
 * <p>刻意不复用 DetailView：后者依赖 viewId，且会拉 500 条记录在前端 find，
 * 作为搜索落地页既慢又要求视图上下文。这里只调用两个已存在的接口：
 * 集合元数据 + 单记录（后者已由后端做集合/行级/数据范围判权）。
 */
export function RecordDetailPage() {
  const { collection = '', recordId = '' } = useParams();
  const [state, setState] = useState<LoadState>({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;
    setState({ status: 'loading' });
    Promise.all([
      apiClient.get<{ data: CollectionMeta }>(`/collections/${collection}`),
      apiClient.get<{ data: Record<string, unknown> }>(
        `/collections/${collection}/records/${recordId}`,
      ),
    ])
      .then(([metaResp, recordResp]) => {
        if (cancelled) return;
        setState({
          status: 'ok',
          meta: metaResp?.data ?? ({ name: collection } as CollectionMeta),
          record: recordResp?.data ?? {},
        });
      })
      .catch((err: any) => {
        if (cancelled) return;
        const status = err?.response?.status;
        if (status === 404) {
          setState({ status: 'notfound' });
        } else {
          setState({
            status: 'error',
            message: err?.response?.data?.message ?? err?.message ?? '加载失败',
          });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [collection, recordId]);

  if (state.status === 'loading') {
    return (
      <Box sx={{ p: 3, display: 'flex', gap: 1, alignItems: 'center' }}>
        <CircularProgress size={20} />
        <Typography>加载中…</Typography>
      </Box>
    );
  }

  if (state.status === 'notfound') {
    return (
      <Box sx={{ p: 3 }}>
        <Typography variant="h6">记录不存在或无权访问</Typography>
        <Typography sx={{ mt: 1 }}>
          <Link to="/search">返回搜索</Link>
        </Typography>
      </Box>
    );
  }

  if (state.status === 'error') {
    return (
      <Box sx={{ p: 3 }}>
        <Typography variant="h6">加载失败</Typography>
        <Typography color="text.secondary">{state.message}</Typography>
      </Box>
    );
  }

  const { meta, record } = state;
  const fields: FieldMeta[] = meta.fields && meta.fields.length > 0
    ? meta.fields
    : Object.keys(record)
        .filter((k) => k !== 'id')
        .map((name): FieldMeta => ({ name }));

  return (
    <Box sx={{ p: 3, maxWidth: 900 }}>
      <Typography variant="overline" color="text.secondary">
        {meta.title || meta.name || collection}
      </Typography>
      <Typography variant="h5" sx={{ mb: 2 }}>
        {String(record.title ?? record.name ?? recordId)}
      </Typography>

      <Box component="dl" sx={{ display: 'grid', gridTemplateColumns: '180px 1fr', gap: 1 }}>
        {fields.map((f) => (
          <Box key={f.name} sx={{ display: 'contents' }}>
            <Typography component="dt" color="text.secondary" variant="body2">
              {f.label || f.name}
            </Typography>
            <Typography component="dd" variant="body2" sx={{ m: 0, wordBreak: 'break-all' }}>
              {formatValue(record[f.name])}
            </Typography>
          </Box>
        ))}
      </Box>

      <Typography sx={{ mt: 3 }}>
        <Link to="/search">返回搜索</Link>
      </Typography>
    </Box>
  );
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}

export default RecordDetailPage;
