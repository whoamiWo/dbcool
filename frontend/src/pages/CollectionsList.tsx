import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { ErrorState } from '@/components/ErrorState';
import { EmptyState } from '@/components/EmptyState';
import type { CollectionMeta } from '@/types/collection';

export function CollectionsListPage() {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['collections'],
    queryFn: async () => {
      // 后端 envelope: {code, message, data: CollectionMeta[]}
      const r = await apiClient.get<{ code: number; data: CollectionMeta[] }>('/collections');
      // 兼容 vitest mock 直接返数组 + 后端 envelope: r 是数组 OR {code, data: [...]} 
      if (Array.isArray(r)) return r;  // vitest 模式
      return r.data ?? [];  // 真后端 envelope
    },
  });

  // PHASE98 R3: 错误态改为可重试 + 展示真实原因（原先只有一句"加载失败"）
  if (isLoading) return <p>加载中…</p>;
  if (error) {
    return (
      <div>
        <h1>📚 Collections</h1>
        <ErrorState
          message={`Collection 列表加载失败：${(error as Error)?.message ?? ''}`}
          onRetry={() => void refetch()}
        />
      </div>
    );
  }

  const collections = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>📚 Collections({collections.length})</h1>
        <Link
          to="/designer/schemas"
          style={{
            padding: '8px 16px',
            background: 'var(--color-bg-secondary)',
            color: 'var(--color-text-primary)',
            textDecoration: 'none',
            borderRadius: 4,
          }}
        >
          + 新建 Collection
        </Link>
      </div>

      {/* PHASE98 R3: 空态改为带 CTA —— 原引导语"点上方"里的"上方"不可点击 */}
      {collections.length === 0 ? (
        <EmptyState
          title="还没有 Collection"
          description="Collection 是数据的结构定义，创建后才能录入记录。"
          actionLabel="+ 新建 Collection"
          onAction={() => (window.location.href = '/designer/schemas')}
        />
      ) : (
        <div
          style={{
            marginTop: 16,
            background: 'var(--color-text-primary)',
            borderRadius: 8,
            boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
            overflow: 'hidden',
          }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ background: 'var(--color-bg-secondary)' }}>
                <th style={{ padding: 12, textAlign: 'left' }}>名称</th>
                <th style={{ padding: 12, textAlign: 'left' }}>标题</th>
                <th style={{ padding: 12, textAlign: 'left' }}>字段数</th>
                <th style={{ padding: 12, textAlign: 'left' }}>创建时间</th>
                <th style={{ padding: 12 }} aria-label="操作"></th>
              </tr>
            </thead>
            <tbody>
              {collections.map((c) => {
                const fieldCount = c.fields_json
                  ? (JSON.parse(c.fields_json) as unknown[]).length
                  : 0;
                return (
                  <tr key={c.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                    <td style={{ padding: 12, fontFamily: 'monospace' }}>{c.name}</td>
                    <td style={{ padding: 12 }}>{c.title}</td>
                    <td style={{ padding: 12 }}>{fieldCount}</td>
                    <td style={{ padding: 12, color: 'var(--color-text-disabled)' }}>
                      {new Date(c.created_at).toLocaleString('zh-CN')}
                    </td>
                    <td style={{ padding: 12, textAlign: 'right' }}>
                      <Link
                        to={`/designer/collections/${c.name}`}
                        style={{ color: 'var(--color-info)', textDecoration: 'none' }}
                      >
                        打开 →
                      </Link>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
