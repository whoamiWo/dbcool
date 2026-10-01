import { useState, useEffect, useCallback } from 'react';
import { Box, Typography, Button, CircularProgress } from '@mui/material';
import { AddCircleOutlined } from '@mui/icons-material';
import {
  DndContext, closestCenter, KeyboardSensor, PointerSensor, useSensor, useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { BoardColumn } from './BoardColumn';
import apiClient from '@/api/client';

interface Column {
  id: string;
  title: string;
  type: string;
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
  const [columns, setColumns] = useState<Column[]>([
    { id: 'col-todo', title: '待办', type: 'TODO' },
    { id: 'col-progress', title: '进行中', type: 'IN_PROGRESS' },
    { id: 'col-done', title: '已完成', type: 'DONE' },
  ]);
  const [cards, setCards] = useState<Card[]>([]);
  const [loading, setLoading] = useState(true);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [colsRes, cardsRes] = await Promise.all([
        apiClient.get(`/api/project-boards/${projectId}/lists`) as Promise<{ data: Column[] }>,
        apiClient.get(`/api/project-boards/${projectId}/tasks`) as Promise<{ data: Card[] }>,
      ]);
      setColumns(colsRes.data);
      setCards(cardsRes.data);
    } catch (e) {
      console.error('Failed to load board data', e);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  // 挂载 / projectId 变化时加载数据。
  // 注意：此前 loadData 定义后从未被调用，组件会永远停留在 loading 态（坏死代码的典型症状）。
  useEffect(() => {
    loadData();
  }, [loadData]);

  // Hooks 必须全部位于任何提前 return 之前，否则两次渲染的 hook 数量不一致，
  // React 会抛 "Rendered more hooks than during the previous render"。
  const sensors = useSensors(useSensor(PointerSensor), useSensor(KeyboardSensor));

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
    apiClient.post('/api/project-boards/cards/move', {
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
    const title = prompt('输入卡片标题:');
    if (!title) return;
    apiClient.post('/api/project-boards/tasks', {
      projectId,
      title,
      status: columnId.startsWith('col-') ? columnId.slice(4) : columnId
    });
    setCards(prev => [...prev, { id: 'new', title, status: columnId, priority: 'MEDIUM', progress: 0 }]);
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h6">看板</Typography>
        <Button variant="outlined" startIcon={<AddCircleOutlined />} onClick={loadData}>
          刷新
        </Button>
      </Box>
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
