import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  DndContext,
  DragEndEvent,
  PointerSensor,
  TouchSensor,
  closestCorners,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import apiClient from '@/api/client';
import { useIsMobile } from '@/hooks/useIsMobile';
import { ErrorState } from '@/components/ErrorState';
import type { CollectionMeta, FieldDef } from '@/types/collection';
import type { KanbanConfig, ViewFull } from '@/types/view';

interface Record { id: string; [k: string]: unknown; }

/** 列容器的 droppable id 前缀（用于 onDragEnd 区分"拖到列"而非"拖到卡片"）。 */
const GROUP_PREFIX = 'group:';

/** 单张卡片：可拖拽。 */
function KanbanCard({
  record,
  groupKey,
  config,
  fields,
}: {
  record: Record;
  groupKey: string;
  config: KanbanConfig;
  fields: FieldDef[];
}) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: record.id,
    data: { groupKey },
  });

  const style: React.CSSProperties = {
    borderRadius: 4,
    padding: 8,
    marginBottom: 6,
    cursor: 'grab',
    opacity: isDragging ? 0.6 : 1,
    transform: transform
      ? `translate3d(${transform.x}px, ${transform.y}px, 0)`
      : undefined,
    touchAction: 'none',
  };

  const titleField = config.cardTitleField ?? Object.keys(record).find((k) => k !== 'id');
  const title = titleField ? String(record[titleField] ?? '') : record.id.slice(0, 8);

  return (
    <div
      ref={setNodeRef}
      className="glass-card"
      style={style}
      {...listeners}
      {...attributes}
    >
      <div style={{ fontWeight: 500, fontSize: 13, color: 'var(--color-text-primary)' }}>{title}</div>
      {(config.cardFields ?? []).slice(0, 3).map((f) => {
        const field = fields.find((x) => x.name === f);
        return (
          <div key={f} style={{ fontSize: 11, color: 'var(--color-text-muted)', marginTop: 2 }}>
            <strong>{field?.label ?? f}:</strong> {String(record[f] ?? '—')}
          </div>
        );
      })}
    </div>
  );
}

/** 单个分组列：作为放置目标（整列区域均可放入，含空列）。 */
function KanbanColumn({
  groupKey,
  items,
  config,
  fields,
  isMobile,
}: {
  groupKey: string;
  items: Record[];
  config: KanbanConfig;
  fields: FieldDef[];
  isMobile: boolean;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: GROUP_PREFIX + groupKey });

  return (
    <div
      ref={setNodeRef}
      className="glass-card"
      style={{
        borderRadius: 8,
        padding: 12,
        minHeight: 200,
        // 拖到列上方时高亮，给用户明确反馈
        outline: isOver ? '2px solid var(--color-info)' : 'none',
        background: isOver ? 'rgba(255,255,255,0.06)' : undefined,
      }}
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
        {groupKey} <span style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>({items.length})</span>
      </div>
      {items.map((r) => (
        <KanbanCard key={r.id} record={r} groupKey={groupKey} config={config} fields={fields} />
      ))}
      {items.length === 0 && (
        <div style={{ fontSize: 11, color: 'var(--color-text-muted)', padding: '8px 0' }}>
          {isMobile ? '拖到此处' : '拖拽卡片到此列'}
        </div>
      )}
    </div>
  );
}

/**
 * 看板视图 (US-204) — 通用视图引擎：按任意字段分组
 *
 * 适用场景：
 * - 浏览任意集合的记录，按自定义 groupBy 字段分组
 * - **PHASE131：支持跨列拖拽**改变分组字段值（落到目标列的 groupBy 值）
 *
 * 不适用场景：
 * - 项目任务流转（带列排序 / 负责人指派） → 请使用 features/project/BoardView
 *
 * 入口路由：/views/:id/kanban
 * 重复度实证（PHASE64 T2-R）：与 BoardView 无成段重复代码，重复率 0%，不合并。
 */
export function KanbanViewPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const isMobile = useIsMobile();
  const queryClient = useQueryClient();
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

  // 提前解析分组字段：moveMutation 需要它拼请求体，而 hooks 必须无条件声明在
  // 所有 early return 之前，因此这里用可选链安全取值（viewData 未加载时为 undefined，
  // 此时不会渲染拖拽 UI，mutation 也不会被触发）。
  const config = (viewData?.config ?? {}) as unknown as KanbanConfig;
  const groupByField = config.groupBy;

  // 跨列拖拽落库：只更新分组字段，其余字段不动
  const moveMutation = useMutation({
    mutationFn: ({ recordId, groupValue }: { recordId: string; groupValue: string }) =>
      apiClient.put(`/collections/${collectionName}/records/${recordId}`, {
        [groupByField ?? '']: groupValue,
      }),
    // 乐观更新：拖拽是高频交互，先改本地再等服务端，避免卡顿
    onMutate: async ({ recordId, groupValue }) => {
      await queryClient.cancelQueries({ queryKey: ['records', collectionName] });
      const previous = queryClient.getQueryData<Record[]>(['records', collectionName]);
      queryClient.setQueryData<Record[]>(['records', collectionName], (old) =>
        old?.map((r) =>
          r.id === recordId ? { ...r, [groupByField ?? '']: groupValue } : r
        )
      );
      return { previous };
    },
    // 失败回滚（含无写权限 403 的情况）
    onError: (_err, _vars, ctx) => {
      if (ctx?.previous) {
        queryClient.setQueryData(['records', collectionName], ctx.previous);
      }
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['records', collectionName] });
    },
  });

  // 拖拽传感器（Hook，必须在所有 early return 之前无条件调用）：
  // 触摸设备用 TouchSensor（长按 200ms 激活）避免与页面滚动冲突；
  // 指针设备用 PointerSensor（移动 8px 激活）避免误触。
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 200, tolerance: 8 } })
  );

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
  const fields: FieldDef[] = collectionData?.fields ?? [];
  const records: Record[] = recordsData ?? [];

  if (!config.groupBy) return <p>{t('kanbanView.noGroupBy')}</p>;

  const groups = new Map<string, Record[]>();
  for (const r of records) {
    const key = String(r[config.groupBy] ?? t('kanbanView.emptyGroup'));
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(r);
  }

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over) return;
    const overId = String(over.id);
    // 只有列容器是 droppable，因此 over 必然是 group:<key>
    if (!overId.startsWith(GROUP_PREFIX)) return;
    const targetGroup = overId.slice(GROUP_PREFIX.length);
    const recordId = String(active.id);
    const current = records.find((r) => r.id === recordId);
    const currentKey = current ? String(current[groupByField] ?? t('kanbanView.emptyGroup')) : null;
    // 同列不作处理
    if (currentKey === targetGroup) return;
    // 空分组的占位 key 不应写库（它不是真实字段值）
    if (targetGroup === t('kanbanView.emptyGroup')) return;
    moveMutation.mutate({ recordId, groupValue: targetGroup });
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', background: 'var(--color-bg-primary)', minHeight: '100vh', padding: '24px 0' }}>
      <Link to={`/designer/collections/${collectionName}`} style={{ color: 'var(--color-text-muted)', fontSize: 12 }}>
        ← {t('common.back')} {collectionName}
      </Link>
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0' }}>{view.title}</h1>
      <p style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>
        {t('kanbanView.groupedBy')} <code>{config.groupBy}</code> ({t('kanbanView.groupCount', { count: groups.size })})
        {moveMutation.isPending && <span style={{ marginLeft: 8 }}>{t('common.saving') ?? '保存中…'}</span>}
        {moveMutation.isError && (
          <span style={{ marginLeft: 8, color: 'var(--color-danger, #f87171)' }}>
            {(moveMutation.error as Error)?.message ?? t('common.error')}
          </span>
        )}
      </p>

      {/*
        PHASE98 R6: 移动端原为 `overflowX:'visible'` —— 主动关掉了横向滚动兜底，
        而 styles.css 的 `.kanban-view-container { grid-auto-columns: 260px }` 又把列宽钉死，
        一旦 CSS 优先级使 260px 生效，260px×N 列在 375px 视口下会直接撑破页面
        （页面级横滚在 iOS Safari 上表现为整页左右抖动）。
        统一改为 `overflowX:'auto'`，让溢出由容器承担，页面本身不横滚。
      */}
      <DndContext
        sensors={sensors}
        collisionDetection={closestCorners}
        onDragEnd={handleDragEnd}
      >
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
            <KanbanColumn
              key={key}
              groupKey={key}
              items={items}
              config={config}
              fields={fields}
              isMobile={isMobile}
            />
          ))}
        </div>
      </DndContext>
    </div>
  );
}
