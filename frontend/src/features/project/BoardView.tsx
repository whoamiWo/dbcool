import { useState } from 'react';
import { Box, Typography, Button, CircularProgress } from '@mui/material';
import { AddCircleOutlined } from '@mui/icons-material';
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

export function BoardView({ projectId }: { projectId: string }) {
  const [columns, setColumns] = useState<Column[]>([
    { id: 'col-todo', title: '待办', type: 'TODO' },
    { id: 'col-progress', title: '进行中', type: 'IN_PROGRESS' },
    { id: 'col-done', title: '已完成', type: 'DONE' },
  ]);
  const [cards, setCards] = useState<Card[]>([]);
  const [loading, setLoading] = useState(true);

  const loadData = async () => {
    setLoading(true);
    try {
      const [colsRes, cardsRes] = await Promise.all([
        apiClient.get(`/projects/${projectId}/board-lists`) as Promise<{ data: Column[] }>,
        apiClient.get(`/projects/${projectId}/tasks`) as Promise<{ data: Card[] }>,
        apiClient.get(`/projects/${projectId}/board-lists`),
        apiClient.get(`/projects/${projectId}/tasks`),
      ]);
      setColumns(colsRes.data);
      setCards(cardsRes.data);
    } catch (e) {
      console.error('Failed to load board data', e);
    } finally {
      setLoading(false);
    }
  };

  if (loading) return <CircularProgress sx={{ mt: 4 }} />;

  const handleMoveCard = (cardId: string, _fromCol: string, toCol: string, toIndex: number) => {
    // Update local state optimistically
    setCards(prev => {
      const card = prev.find(c => c.id === cardId);
      if (!card) return prev;
      const newCards = prev.filter(c => c.id !== cardId);
      newCards.splice(toIndex, 0, { ...card, status: toCol });
      return newCards;
    });
    // Call API
    apiClient.post(`/projects/${projectId}/tasks/${cardId}/move`, { toListId: toCol, toIndex });
  };

  const handleAddCard = (columnId: string) => {
    const title = prompt('输入卡片标题:');
    if (!title) return;
    apiClient.post(`/projects/${projectId}/tasks`, { projectId, title, status: columnId });
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
      <Box sx={{ display: 'flex', gap: 2, overflowX: 'auto', pb: 2 }}>
        {columns.map(col => (
          <BoardColumn
            key={col.id}
            columnId={col.id}
            title={col.title}
            type={col.type}
            cards={cards.filter(c => c.status === col.type)}
            onMoveCard={handleMoveCard}
            onAddCard={handleAddCard}
          />
        ))}
      </Box>
    </Box>
  );
}
