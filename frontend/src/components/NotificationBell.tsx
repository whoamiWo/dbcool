import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';

/**
 * 顶栏通知铃铛（E3）：未读数 30 秒轮询，点击进入通知中心。
 *
 * <p>使用 apiClient（带 JWT 拦截器），不绕过认证直接 fetch。
 */
export function NotificationBell() {
  const { data } = useQuery({
    queryKey: ['messages-unread'],
    queryFn: () => apiClient.get<{ unread: number }>('/messages/unread-count'),
    refetchInterval: 30_000,
    staleTime: 10_000,
    retry: false,
  });
  const unread = data?.unread ?? 0;

  return (
    <Link
      to="/messages"
      aria-label="通知中心"
      title={`通知中心${unread > 0 ? `（${unread} 条未读）` : ''}`}
      style={{
        position: 'relative',
        textDecoration: 'none',
        fontSize: 18,
        lineHeight: 1,
        padding: '4px 6px',
        color: 'var(--color-text-primary)',
      }}
    >
      🔔
      {unread > 0 && (
        <span
          style={{
            position: 'absolute',
            top: -2,
            right: -4,
            minWidth: 16,
            height: 16,
            padding: '0 4px',
            borderRadius: 8,
            background: 'var(--color-error)',
            color: '#fff',
            fontSize: 10,
            lineHeight: '16px',
            textAlign: 'center',
            fontWeight: 600,
          }}
        >
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </Link>
  );
}
