import { Box, Button, Typography } from '@mui/material';

/**
 * PHASE98 R3: 统一的空态组件（**带 CTA**）。
 *
 * 背景：此前全站空态都是纯文字，且引导语写着"点上方「新建」开始"——
 * 文字里的"上方"不可点击。新租户首次进入时 0 数据是最常见的场景，
 * 空态无 CTA 会直接断裂首登转化漏斗。
 *
 * 用法（action 必填其一，引导用户可真正执行下一步）：
 *   <EmptyState
 *     title="还没有 Collection"
 *     description="创建第一个 Collection 开始管理数据"
 *     actionLabel="新建 Collection"
 *     onAction={() => navigate('/collections/new')}
 *   />
 */
export interface EmptyStateProps {
  /** 主标题 */
  title: string;
  /** 补充说明 */
  description?: string;
  /** CTA 按钮文案 */
  actionLabel?: string;
  /** CTA 回调 */
  onAction?: () => void;
  /** CTA 禁用（如异步操作进行中） */
  actionDisabled?: boolean;
  /** 自定义高度占位 */
  minHeight?: number | string;
}

export function EmptyState({
  title,
  description,
  actionLabel,
  onAction,
  actionDisabled = false,
  minHeight = 200,
}: EmptyStateProps) {
  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 1,
        py: 6,
        textAlign: 'center',
        minHeight,
      }}
    >
      <Typography variant="body1" sx={{ fontWeight: 500 }}>
        {title}
      </Typography>
      {description && (
        <Typography variant="body2" sx={{ opacity: 0.7, maxWidth: 420 }}>
          {description}
        </Typography>
      )}
      {actionLabel && onAction && (
        <Button
          variant="contained"
          size="small"
          onClick={onAction}
          disabled={actionDisabled}
          sx={{ mt: 1 }}
        >
          {actionLabel}
        </Button>
      )}
    </Box>
  );
}

export default EmptyState;
