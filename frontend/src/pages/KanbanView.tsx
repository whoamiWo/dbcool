import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { useIsMobile } from '@/hooks/useIsMobile';
import { ErrorState } from '@/components/ErrorState';
import type { CollectionMeta, FieldDef } from '@/types/collection';
import type { KanbanConfig, ViewFull } from '@/types/view';

interface Record { id: string; [k: string]: unknown; }

/**
 * 看板视图 (US-204) — 通用视图引擎：按任意字段分组
 *
 * 适用场景：
 * - 浏览任意集合的记录，按自定义 groupBy 字段分组
 * - 只读展示，无拖拽交互
 *
 * 不适用场景：
 * - 项目任务流转 / 拖拽改变状态 → 请使用 features/project/BoardView
 *
 * 入口路由：/views/:id/kanban
 * 重复度实证（PHASE64 T2-R）：与 BoardView 无成段重复代码，重复率 0%，不合并。
 */
export function KanbanViewPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const isMobile = useIsMobile();
  // PHASE98 R3: 三个 query 全部取 isError/refetch。
  // 此前只解构 data —— 404/500 时 viewData 恒为 undefined，
  // 页面永远停在"加载中…"，用户看到的是"慢"而不是"坏"。
  const {
    data: viewData,
    isError: viewError,
    error: viewErrObj,
    refetch: refetchView,
  } = useQuery({
    queryKey: ['view', id],
    queryFn: () => apiClient.get<ViewFull>(`/views/${id}`),
    enabled: !!id,
  });
  const collectionName = viewData?.collection_name;
  const {
    data: collectionData,
    isError: collectionError,
    error: collectionErrObj,
    refetch: refetchCollection,
  } = useQuery({
    queryKey: ['collection', collectionName],
    queryFn: () => apiClient.get<CollectionMeta>(`/collections/${collectionName}`),
    enabled: !!collectionName,
  });
  const {
    data: recordsData,
    isError: recordsError,
    error: recordsErrObj,
    refetch: refetchRecords,
  } = useQuery({
    queryKey: ['records', collectionName],
    queryFn: () => apiClient.get<Record[]>(`/collections/${collectionName}/records?limit=500`),
    enabled: !!collectionName,
  });

  if (viewError) {
    return (
      <ErrorState
        message={`${t('kanbanView.viewLoadFailed')}: ${(viewErrObj as Error)?.message ?? ''}`}
        onRetry={() => void refetchView()}
      />
    );
  }
  if (collectionError) {
    return (
      <ErrorState
        message={`${t('kanbanView.collectionLoadFailed')}: ${(collectionErrObj as Error)?.message ?? ''}`}
        onRetry={() => void refetchCollection()}
      />
    );
  }
  if (recordsError) {
    return (
      <ErrorState
        message={`${t('kanbanView.recordsLoadFailed')}: ${(recordsErrObj as Error)?.message ?? ''}`}
        onRetry={() => void refetchRecords()}
      />
    );
  }
  if (!viewData) return <p>{t('common.loading')}</p>;
  const view = viewData;
  const config = view.config as unknown as KanbanConfig;
  const fields: FieldDef[] = collectionData?.fields ?? [];
  const records: Record[] = recordsData ?? [];

  if (!config.groupBy) return <p>{t('kanbanView.noGroupBy')}</p>;

  const groups = new Map<string, Record[]>();
  for (const r of records) {
    const key = String(r[config.groupBy] ?? t('kanbanView.emptyGroup'));
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(r);
  }

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', background: 'var(--color-bg-primary)', minHeight: '100vh', padding: '24px 0' }}>
      <Link to={`/designer/collections/${collectionName}`} style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>
        ← {t('common.back')} {collectionName}
      </Link>
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0' }}>{view.title}</h1>
      <p style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>
        {t('kanbanView.groupedBy')} <code>{config.groupBy}</code> ({t('kanbanView.groupCount', { count: groups.size })})
      </p>

      {/*
        PHASE98 R6: 移动端原为 `overflowX:'visible'` —— 主动关掉了横向滚动兜底，
        而 styles.css 的 `.kanban-view-container { grid-auto-columns: 260px }` 又把列宽钉死，
        一旦 CSS 优先级使 260px 生效，260px×N 列在 375px 视口下会直接撑破页面
        （页面级横滚在 iOS Safari 上表现为整页左右抖动）。
        统一改为 `overflowX:'auto'`，让溢出由容器承担，页面本身不横滚。
      */}
      <div
        style={{
          display: 'grid',
          gridAutoFlow: isMobile ? 'row' : 'column',
          gridAutoColumns: isMobile ? '1fr' : '280px',
          gap: 12,
          overflowX: 'auto',
          overflowY: isMobile ? 'auto' : 'visible',
          maxHeight: isMobile ? 'calc(100vh - 140px)' : 'none',
          paddingBottom: 12,
        }}
        className="kanban-view-container"
      >
        {Array.from(groups.entries()).map(([key, items]) => (
          <div
            key={key}
            className="glass-card"
            style={{ borderRadius: 8, padding: 12, minHeight: 200 }}
          >
            <div
              style={{
                fontWeight: 500,
                marginBottom: 8,
                paddingBottom: 8,
                borderBottom: '1px solid rgba(255, 255, 255, 0.1)',
                color: 'var(--color-text-primary)',
              }}
            >
              {key} <span style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>({items.length})</span>
            </div>
            {items.map((r) => {
              const titleField = config.cardTitleField ?? Object.keys(r).find((k) => k !== 'id');
              const title = titleField ? String(r[titleField] ?? '') : r.id.slice(0, 8);
              return (
                <div
                  key={r.id}
                  className="glass-card"
                  style={{ borderRadius: 4, padding: 8, marginBottom: 6 }}
                >
                  <div style={{ fontWeight: 500, fontSize: 13, color: 'var(--color-text-primary)' }}>{title}</div>
                  {(config.cardFields ?? []).slice(0, 3).map((f) => {
                    const field = fields.find((x) => x.name === f);
                    return (
                      <div key={f} style={{ fontSize: 11, color: 'var(--color-text-muted)', marginTop: 2 }}>
                        <strong>{field?.label ?? f}:</strong> {String(r[f] ?? '—')}
                      </div>
                    );
                  })}
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}
