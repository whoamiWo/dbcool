import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import type { ApiResponse, CollectionMeta } from '@/types/collection';

interface UserInfo {
  id: string;
  username: string;
  tenant_id: string;
  roles: string[];
}

export function HomePage() {
  const { user } = useAuthStore();

  const meQuery = useQuery({
    queryKey: ['users', 'me'],
    queryFn: () => apiClient.get<ApiResponse<UserInfo>>('/users/me'),
  });

  const collectionsQuery = useQuery({
    queryKey: ['collections', 'count'],
    queryFn: () =>
      apiClient.get<ApiResponse<CollectionMeta[]>>('/collections'),
    select: (res) => res.data.length,
  });

  return (
    <div>
      <h1>👋 欢迎,{user?.display_name ?? user?.username ?? '游客'}</h1>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
          gap: 16,
          marginTop: 24,
        }}
      >
        <div
          style={{
            padding: 20,
            background: 'white',
            borderRadius: 8,
            boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          }}
        >
          <div style={{ color: '#64748b', fontSize: 12, marginBottom: 4 }}>
            当前用户
          </div>
          <div style={{ fontSize: 18, fontWeight: 500 }}>
            {user?.username ?? '-'}
          </div>
          <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>
            tenant: {user?.tenant_id ?? '-'}
          </div>
        </div>

        <div
          style={{
            padding: 20,
            background: 'white',
            borderRadius: 8,
            boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          }}
        >
          <div style={{ color: '#64748b', fontSize: 12, marginBottom: 4 }}>
            Collection 数
          </div>
          <div style={{ fontSize: 32, fontWeight: 600, color: '#1e293b' }}>
            {collectionsQuery.data ?? '-'}
          </div>
        </div>

        <div
          style={{
            padding: 20,
            background: 'white',
            borderRadius: 8,
            boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          }}
        >
          <div style={{ color: '#64748b', fontSize: 12, marginBottom: 4 }}>
            JWT 状态
          </div>
          <div
            style={{
              fontSize: 16,
              fontWeight: 500,
              color: meQuery.isError ? '#dc2626' : '#16a34a',
            }}
          >
            {meQuery.isError ? '❌ 鉴权失败' : meQuery.isLoading ? '…' : '✅ 已认证'}
          </div>
        </div>
      </div>

      <div
        style={{
          marginTop: 24,
          padding: 20,
          background: 'white',
          borderRadius: 8,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
        }}
      >
        <h2 style={{ marginTop: 0 }}>快捷操作</h2>
        <ul style={{ lineHeight: 2 }}>
          <li>
            <Link to="/designer/schemas">📐 打开 Schema Designer(查看/创建 Collection)</Link>
          </li>
          <li>
            <a href="/api/health" target="_blank" rel="noreferrer">🩺 检查 Java 后端健康(/api/health)</a>
          </li>
          <li>
            <a href="/api/collections" target="_blank" rel="noreferrer">📋 原始 API: GET /api/collections</a>
          </li>
        </ul>
      </div>
    </div>
  );
}
