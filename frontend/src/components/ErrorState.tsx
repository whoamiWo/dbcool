import { Alert, Button, Box } from '@mui/material';
import { useTranslation } from 'react-i18next';

/**
 * PHASE98 R3: 统一的错误态组件。
 *
 * 背景：此前每个页面各写一遍 `if (error) return <p>加载失败</p>`，
 * 无重试入口、无失败原因、无 traceId —— 用户只能看到一句"加载失败"，
 * 无法区分"后端故障"与"真的没数据"，也无法自助恢复。
 *
 * 用法：
 *   if (isError) return <ErrorState message={(error as Error)?.message} onRetry={refetch} />;
 */
export interface ErrorStateProps {
  /** 失败原因（优先显示，缺省时回落通用文案） */
  message?: string;
  /** 重试回调；提供时渲染"重试"按钮 */
  onRetry?: () => void;
  /** 链路追踪 ID，便于用户对客服报障 */
  traceId?: string;
  /** 自定义高度占位（用于表格/看板区域） */
  minHeight?: number | string;
}

export function ErrorState({ message, onRetry, traceId, minHeight }: ErrorStateProps) {
  const { t } = useTranslation();

  return (
    <Box sx={{ p: 3, minHeight }}>
      <Alert
        severity="error"
        role="alert"
        action={
          onRetry ? (
            <Button color="inherit" size="small" onClick={onRetry}>
              {t('common.retry')}
            </Button>
          ) : undefined
        }
      >
        <Box>{message || t('common.loadFailed')}</Box>
        {traceId && (
          <Box sx={{ fontSize: 12, mt: 0.5, opacity: 0.8 }}>traceId: {traceId}</Box>
        )}
      </Alert>
    </Box>
  );
}

export default ErrorState;
