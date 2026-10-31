import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Box, Typography, Button, CircularProgress } from '@mui/material';
import { AddCircleOutlined, LabelOutlined } from '@mui/icons-material';
import {
  DndContext, closestCenter, KeyboardSensor, PointerSensor, useSensor, useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { BoardColumn } from './BoardColumn';
import { LabelManager } from './LabelManager';
import apiClient from '@/api/client';
import { ErrorState } from '@/components/ErrorState';

interface Column {
  id: string;
  title: string;
  type: string;
}

interface ChecklistItem {
  id: string;
  title: string;
  done: boolean;
}

interface Checklist {
  id: string;
  title: string;
  items: ChecklistItem[];
}

interface Card {
  id: string;
  title: string;
  status: string;
  priority: string;
  assignee?: string;
  progress: number;
  dueDate?: string;
  labels?: string[];
  checklists?: Checklist[];
  assigneeId?: string;
  endDate?: string;
}

/**
 * 项目管理看板 — 任务流转专用
 *
 * 适用场景：
 * - 管理项目任务，拖拽改变任务状态（DndKit，落库到 /api/project-boards/cards/move）
 * - 固定状态列（后端返回 lists）
 *
 * 不适用场景：
 * - 通用集合数据浏览 / 自定义字段分组 → 请使用 pages/KanbanView
 *
 * 入口：ProjectPage tab=0
 * 重复度实证（PHASE64 T2-R）：与 KanbanView 无成段重复代码，重复率 0%，不合并。
 */
export function BoardView({ projectId }: { projectId: string }) {
  // Wave4 待办池 #15：标签管理面板开关（后端标签 CRUD 已存在，此前前端零接线）
  const [labelsOpen, setLabelsOpen] = useState(false);
  const { t } = useTranslation();
  const [columns, setColumns] = useState<Column[]>([
    { id: 'col-todo', title: t('board.todo'), type: 'TODO' },
    { id: 'col-progress', title: t('board.inProgress'), type: 'IN_PROGRESS' },
    { id: 'col-done', title: t('board.done'), type: 'DONE' },
  ]);
  const [cards, setCards] = useState<Card[]>([]);
  const [loading, setLoading] = useState(true);
  // PHASE98 R3: 加载失败必须对用户可见。此前 catch 只 console.error，
  // 然后静默渲染一个空看板 —— 用户分不清"没有任务"和"加载失败"。
  const [loadError, setLoadError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const [colsRes, cardsRes] = await Promise.all([
        apiClient.get(`/project-boards/${projectId}/lists`) as Promise<{ data: Column[] }>,
        // Wave0.5 A-8 契约门禁发现：任务列表 GET 在 ProjectController（/api/projects/{id}/tasks），
        // ProjectBoardController 只有 POST/PUT/DELETE /tasks —— 原路径 /project-boards/{id}/tasks 恒 404
        apiClient.get(`/projects/${projectId}/tasks`) as Promise<{ data: Card[] }>,
      ]);
      setColumns(colsRes.data);

      const rawCards = cardsRes.data;
      // 批量拉取清单，避免逐卡 N+1 请求
      const taskIds = rawCards.map(c => c.id).filter(Boolean);
      let mergedCards = rawCards;
      if (taskIds.length > 0) {
        const clRes = (await apiClient.post('/project-boards/checklists/batch', { taskIds })) as {
          data: Record<string, Array<{ id: string; title: string; sortOrder: number }>>;
        };
        const grouped = clRes.data ?? {};
        // 汇总所有清单 id，一次性拉取清单项
        const allChecklistIds = Object.values(grouped).flat().map(c => c.id);
        let itemsByChecklist: Record<string, ChecklistItem[]> = {};
        if (allChecklistIds.length > 0) {
          const itemsRes = (await apiClient.post('/project-boards/checklist-items/batch', {
            checklistIds: allChecklistIds,
          })) as { data: Record<string, ChecklistItem[]> };
          itemsByChecklist = itemsRes.data ?? {};
        }
        mergedCards = rawCards.map(card => {
          const cls = grouped[card.id];
          if (!cls || cls.length === 0) return card;
          return {
            ...card,
            checklists: cls.map(cl => ({
              id: cl.id,
              title: cl.title,
              items: itemsByChecklist[cl.id] ?? [],
            })),
          };
        });
      }
      setCards(mergedCards);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : t('board.loadError'));
    } finally {
      setLoading(false);
    }
  }, [projectId, t]);

  // 挂载 / projectId 变化时加载数据。
  // 注意：此前 loadData 定义后从未被调用，组件会永远停留在 loading 态（坏死代码的典型症状）。
  useEffect(() => {
  // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：异步/条件/初始化模式，非连续渲染
    loadData();
  }, [loadData]);

  // Hooks 必须全部位于任何提前 return 之前，否则两次渲染的 hook 数量不一致，
  // React 会抛 "Rendered more hooks than during the previous render"。
  const sensors = useSensors(useSensor(PointerSensor), useSensor(KeyboardSensor));

  // PHASE98 R3: 错误态优先于 loading —— 失败时给出原因与重试入口
  if (loadError) {
    return <ErrorState message={`${t('board.loadError')}：${loadError}`} onRetry={loadData} />;
  }

  if (loading) return <CircularProgress sx={{ mt: 4 }} />;

  /**
   * 跨列/列内拖拽结束。
   *
   * <p>注意：DndContext 必须放在 BoardView（父级）。此前每列各自持有 DndContext，
   * 导致无法跨列拖拽；且 useSortable 未设置 containerId，
   * onDragEnd 里 fromCol/toCol 恒为 undefined —— 拖拽实际不触发任何移动。
   *
   * @param toListId 目标列的 UUID（后端 moveCard 需要列 ID，不是 type）
   */
  const handleMoveCard = (cardId: string, toListId: string, toIndex: number) => {
    const targetCol = columns.find(c => c.id === toListId);
    if (!targetCol) return;
    setCards(prev => prev.map(c => (c.id === cardId ? { ...c, status: targetCol.type } : c)));
    apiClient.post('/project-boards/cards/move', {
      taskId: cardId,
      toListId,
      toIndex,
    });
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over) return;
    const activeId = String(active.id);
    const overId = String(over.id);
    // over 可能是卡片（带 containerId），也可能是列容器本身（拖到空列）
    const toColId = (over.data.current?.sortable?.containerId as string) ?? overId;
    if (!toColId || toColId === activeId) return;
    const targetCol = columns.find(c => c.id === toColId);
    if (!targetCol) return;
    // 落在某张卡片上时，插入到该卡片位置；落在列容器上时追加到末尾
    const overCardIndex = cards.findIndex(c => c.id === overId);
    const toIndex = overCardIndex >= 0 ? overCardIndex : cards.filter(c => c.status === targetCol.type).length;
    handleMoveCard(activeId, toColId, toIndex);
  };

  const handleAddCard = (columnId: string) => {
    const title = prompt(t('board.inputCardTitle'));
    if (!title) return;
    apiClient.post('/project-boards/tasks', {
      projectId,
      title,
      status: columnId.startsWith('col-') ? columnId.slice(4) : columnId
    });
    setCards(prev => [...prev, { id: 'new', title, status: columnId, priority: 'MEDIUM', progress: 0 }]);
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h6">{t('board.title')}</Typography>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button
            variant="outlined"
            startIcon={<LabelOutlined />}
            aria-label={t('board.labelManager')}
            onClick={() => setLabelsOpen((v) => !v)}
          >
            {t('board.labels')}
          </Button>
          <Button variant="outlined" startIcon={<AddCircleOutlined />} onClick={loadData}>
            {t('board.refresh')}
          </Button>
        </Box>
      </Box>
      {labelsOpen && (
        <Box sx={{ mb: 2 }}>
          <LabelManager projectId={projectId} onClose={() => setLabelsOpen(false)} />
        </Box>
      )}
      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragEnd={handleDragEnd}
      >
        <Box sx={{ display: 'flex', gap: 2, overflowX: 'auto', pb: 2 }}>
          {columns.map(col => (
            <BoardColumn
              key={col.id}
              columnId={col.id}
              title={col.title}
              type={col.type}
              cards={cards.filter(c => c.status === col.type)}
              onAddCard={handleAddCard}
            />
          ))}
        </Box>
      </DndContext>
    </Box>
  );
}
