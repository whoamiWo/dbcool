
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
  CheckCircleOutlined,
  CalendarToday,
} from '@mui/icons-material';
import { useDroppable } from '@dnd-kit/core';
import { SortableContext, verticalListSortingStrategy, useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

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

interface CardItem {
  id: string;
  title: string;
  status: string;
  priority: string;
  assignee?: string;
  progress: number;
  dueDate?: string;
  labels?: string[];
  checklists?: Checklist[];
}

interface BoardColumnProps {
  columnId: string;
  title: string;
  type: string;
  cards: CardItem[];
  onAddCard: (columnId: string) => void;
}

function formatDueDate(dateStr?: string): string {
  if (!dateStr) return '';
  const d = new Date(dateStr);
  if (isNaN(d.getTime())) return '';
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' });
}

function isOverdue(dateStr?: string): boolean {
  if (!dateStr) return false;
  const d = new Date(dateStr);
  if (isNaN(d.getTime())) return false;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return d < today;
}

function SortableCard({ card, columnId }: { card: CardItem; columnId: string }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: card.id,
    // 关键：带上所属列的 containerId，父级 onDragEnd 才能判断跨列拖拽
    data: { sortable: { containerId: columnId } },
  });
  const style = { transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.5 : 1 };

  const totalItems = card.checklists?.reduce((sum, c) => sum + c.items.length, 0) ?? 0;
  const doneItems = card.checklists?.reduce((sum, c) => sum + c.items.filter(i => i.done).length, 0) ?? 0;

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
          {card.checklists && card.checklists.length > 0 && (
            <Box sx={{ mb: 0.5, p: 0.5, bgcolor: 'rgba(0,0,0,0.04)', borderRadius: 0.5 }}>
              {card.checklists.map(cl => (
                <Box key={cl.id} sx={{ mb: 0.5 }}>
                  <Typography variant="caption" sx={{ fontWeight: 600 }}>
                    {cl.title}
                  </Typography>
                  <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
                    {cl.items.slice(0, 3).map(item => (
                      <Chip
                        key={item.id}
                        icon={item.done ? <CheckCircleOutlined /> : undefined}
                        label={item.title}
                        size="small"
                        variant={item.done ? 'filled' : 'outlined'}
                        sx={{ height: 18, fontSize: '0.65rem' }}
                      />
                    ))}
                    {cl.items.length > 3 && (
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        +{cl.items.length - 3}项
                      </Typography>
                    )}
                  </Box>
                </Box>
              ))}
              {totalItems > 0 && (
                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                  {doneItems}/{totalItems} 已完成
                </Typography>
              )}
            </Box>
          )}
          <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', fontSize: '0.75rem', color: 'text.secondary' }}>
            <Chip label={card.priority} size="small" color={card.priority === 'HIGH' ? 'error' : card.priority === 'MEDIUM' ? 'warning' : 'default'} />
            <Typography variant="caption">{card.assignee || '未分配'}</Typography>
            <Typography variant="caption">{card.progress}%</Typography>
            {card.dueDate && (
              <Chip
                icon={<CalendarToday fontSize="small" />}
                label={formatDueDate(card.dueDate)}
                size="small"
                color={isOverdue(card.dueDate) ? 'error' : 'default'}
                sx={{ height: 18, fontSize: '0.65rem' }}
              />
            )}
          </Box>
        </Box>
        <IconButton size="small" sx={{ p: 0.25 }}>
          <MoreHoriz fontSize="small" />
        </IconButton>
      </Box>
    </Paper>
  );
}

export function BoardColumn({ columnId, title, cards, onAddCard }: BoardColumnProps) {
  // 列本身作为放置目标（拖到空列也能接收卡片）。
  // 注意：DndContext 已上移到 BoardView（否则每列独立上下文，无法跨列拖拽）。
  const { setNodeRef: setDropRef } = useDroppable({ id: columnId });

  return (
    <Paper ref={setDropRef} sx={{ p: 1.5, minWidth: 280, maxWidth: 320, height: '100%', display: 'flex', flexDirection: 'column', bgcolor: 'var(--color-bg-tertiary)' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography variant="subtitle2" component="div">
          {title}
        </Typography>
        <Chip label={`${cards.length}`} size="small" color="default" />
      </Box>
      <Divider sx={{ mb: 1 }} />

      <Box sx={{ flex: 1, overflowY: 'auto', minHeight: 200 }}>
        {/* id=columnId —— 让本列成为可识别的排序容器，父级据此判断跨列拖拽 */}
        <SortableContext id={columnId} items={cards.map(c => c.id)} strategy={verticalListSortingStrategy}>
          {cards.map(card => (
            <SortableCard key={card.id} card={card} columnId={columnId} />
          ))}
        </SortableContext>
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
