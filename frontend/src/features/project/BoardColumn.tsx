
import {
  Box,
  Paper,
  Typography,
  Chip,
  IconButton,
  Button,
  Divider,
} from '@mui/material';
import {
  Add,
  MoreHoriz,
  DragIndicator,
} from '@mui/icons-material';
import { DndContext, closestCenter, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core';
import { SortableContext, verticalListSortingStrategy, useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

interface CardItem {
  id: string;
  title: string;
  status: string;
  priority: string;
  assignee?: string;
  progress: number;
  dueDate?: string;
  labels?: string[];
}

interface BoardColumnProps {
  columnId: string;
  title: string;
  type: string;
  cards: CardItem[];
  onMoveCard: (cardId: string, fromCol: string, toCol: string, toIndex: number) => void;
  onAddCard: (columnId: string) => void;
}

function SortableCard({ card }: { card: CardItem; columnId?: string }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: card.id });
  const style = { transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.5 : 1 };

  return (
    <Paper
      ref={setNodeRef}
      style={style}
      {...attributes}
      {...listeners}
      sx={{
        p: 1.5, mb: 1, borderRadius: 1, bgcolor: 'background.paper',
        border: '1px solid var(--color-border-light)', cursor: 'grab',
        '&:hover': { boxShadow: 1 },
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 0.5 }}>
        <DragIndicator fontSize="small" sx={{ color: 'text.secondary', mt: 0.25 }} />
        <Box sx={{ flex: 1 }}>
          <Typography variant="body2" sx={{ mb: 0.5 }}>
            {card.title}
          </Typography>
          <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', mb: 0.5 }}>
            {card.labels?.map(label => (
              <Chip key={label} label={label} size="small" sx={{ height: 18, fontSize: '0.7rem' }} />
            ))}
          </Box>
          <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', fontSize: '0.75rem', color: 'text.secondary' }}>
            <Chip label={card.priority} size="small" color={card.priority === 'HIGH' ? 'error' : card.priority === 'MEDIUM' ? 'warning' : 'default'} />
            <Typography variant="caption">{card.assignee || '未分配'}</Typography>
            <Typography variant="caption">{card.progress}%</Typography>
          </Box>
        </Box>
        <IconButton size="small" sx={{ p: 0.25 }}>
          <MoreHoriz fontSize="small" />
        </IconButton>
      </Box>
    </Paper>
  );
}

export function BoardColumn({ columnId, title, cards, onMoveCard, onAddCard }: BoardColumnProps) {


  return (
    <Paper sx={{ p: 1.5, minWidth: 280, maxWidth: 320, height: '100%', display: 'flex', flexDirection: 'column', bgcolor: 'var(--color-bg-tertiary)' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography variant="subtitle2" component="div">
          {title}
        </Typography>
        <Chip label={`${cards.length}`} size="small" color="default" />
      </Box>
      <Divider sx={{ mb: 1 }} />

      <Box sx={{ flex: 1, overflowY: 'auto', minHeight: 200 }}>
        <DndContext
          sensors={useSensors(useSensor(PointerSensor), useSensor(KeyboardSensor))}
          collisionDetection={closestCenter}
          onDragEnd={({ active, over }) => {
            if (over && active.id !== over.id) {
              const fromCol = active.data.current?.sortable?.containerId as string;
              const toCol = over.data.current?.sortable?.containerId as string;
              const toIndex = cards.findIndex(c => c.id === over.id);
              if (fromCol && toCol) {
                onMoveCard(String(active.id), fromCol, toCol, toIndex);
              }
            }
          }}
        >
          <SortableContext items={cards.map(c => c.id)} strategy={verticalListSortingStrategy}>
            {cards.map(card => (
              <SortableCard key={card.id} card={card} columnId={columnId} />
            ))}
          </SortableContext>
        </DndContext>
      </Box>

      <Button
        size="small"
        startIcon={<Add fontSize="small" />}
        onClick={() => onAddCard(columnId)}
        sx={{ mt: 1, textTransform: 'none', justifyContent: 'flex-start' }}
      >
        添加卡片
      </Button>
    </Paper>
  );
}
