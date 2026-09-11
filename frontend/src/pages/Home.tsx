import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import type { CollectionMeta } from '@/types/collection';

interface UserInfo { id: string; username: string; tenant_id: string; roles: string[]; }
interface MessagePreview { id: string; title: string; is_read: boolean; created_at: string; }
interface TaskPreview { id: string; workflow_title: string; node_id: string; status: string; instance_id: string; created_at: string; }
interface InstancePreview { id: string; workflow_title: string; status: string; started_at: string; }
interface AuditPreview { id: string; action: string; resource: string; resource_id: string | null; username: string | null; created_at: string; }

export function HomePage() {
  const { user } = useAuthStore();

  const meQuery = useQuery({ queryKey: ['me'], queryFn: () => apiClient.get<UserInfo>('/users/me') });
  const colsQuery = useQuery({ queryKey: ['collections'], queryFn: () => apiClient.get<CollectionMeta[]>('/collections') });
  const wfQuery = useQuery({ queryKey: ['workflows'], queryFn: () => apiClient.get<unknown[]>('/workflows') });
  const unreadQuery = useQuery({
    queryKey: ['messages', 'unread'],
    queryFn: () => apiClient.get<{ unread_count: number; messages: MessagePreview[] }>('/messages?limit=5&unreadOnly=true'),
  });
  const tasksQuery = useQuery({
    queryKey: ['my-tasks'],
    queryFn: () => apiClient.get<TaskPreview[]>('/workflows/tasks/my'),
  });
  const instancesQuery = useQuery({
    queryKey: ['wf-instances'],
    queryFn: () => apiClient.get<InstancePreview[]>('/workflows/instances'),
  });

  const pendingTasks = (tasksQuery.data ?? []).filter((t) => t.status === 'PENDING');
  const runningInstances = (instancesQuery.data ?? []).filter((i) => i.status === 'RUNNING' || i.status === 'PENDING');

  return (
    <div style={{ padding: 16 }}>
      <h1>👋 欢迎,{(user as { display_name?: string })?.display_name ?? user?.username ?? '游客'}</h1>

      {/* 摘要卡片 */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginTop: 16 }}>
        <SummaryCard color="#3b82f6" icon="📋" label="未读站内信" value={unreadQuery.data?.unread_count} link="/messages" />
        <SummaryCard color="#f59e0b" icon="📝" label="待我审批" value={pendingTasks.length} link="/tasks/my" />
        <SummaryCard color="#10b981" icon="📊" label="运行中工作流" value={runningInstances.length} link="/designer/instances" />
        <SummaryCard color="#8b5cf6" icon="📐" label="Collection 数" value={colsQuery.data?.length} link="/designer/schemas" />
        <SummaryCard color="#0ea5e9" icon="🔧" label="工作流数" value={wfQuery.data?.length} link="/designer/workflows" />
      </div>

      {/* 待办 + 运行中 */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginTop: 16 }}>
        <Panel title="⏳ 待审批任务" link="/tasks/my" linkText="查看全部">
          {pendingTasks.length === 0 && <Empty text="当前无待审批 🎉" />}
          {pendingTasks.slice(0, 5).map((t) => (
            <Link key={t.id} to={`/designer/instances/${t.instance_id}`} style={itemLink}>
              <span style={{ fontWeight: 500 }}>{t.workflow_title}</span>
              <span style={{ color: '#94a3b8', fontSize: 11 }}>{t.node_id}</span>
              <span style={{ color: '#64748b', fontSize: 11 }}>{(t.created_at || '').slice(0, 16)}</span>
            </Link>
          ))}
        </Panel>

        <Panel title="🔄 运行中实例" link="/designer/instances" linkText="查看全部">
          {runningInstances.length === 0 && <Empty text="无运行中实例" />}
          {runningInstances.slice(0, 5).map((i) => (
            <Link key={i.id} to={`/designer/instances/${i.id}`} style={itemLink}>
              <span style={{ fontWeight: 500 }}>{i.workflow_title}</span>
              <span style={{ color: i.status === 'PENDING' ? '#f59e0b' : '#3b82f6', fontSize: 11, fontWeight: 600 }}>
                {i.status}
              </span>
              <span style={{ color: '#64748b', fontSize: 11 }}>{(i.started_at || '').slice(0, 16)}</span>
            </Link>
          ))}
        </Panel>
      </div>

      {/* 未读站内信 + 最近审计 */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginTop: 16 }}>
        <Panel title="📨 未读站内信" link="/messages" linkText="查看全部">
          {(unreadQuery.data?.messages ?? []).length === 0 && <Empty text="无未读消息" />}
          {(unreadQuery.data?.messages ?? []).map((m) => (
            <div key={m.id} style={itemLink}>
              <span style={{ fontWeight: 500 }}>{m.title}</span>
              <span style={{ color: '#94a3b8', fontSize: 11 }}>{(m.created_at || '').slice(0, 16)}</span>
            </div>
          ))}
        </Panel>
        <AuditWidget />
      </div>

      {/* 系统状态 */}
      <div style={{ marginTop: 16, padding: 12, background: 'white', borderRadius: 8, color: '#64748b', fontSize: 12 }}>
        系统状态: {meQuery.isError ? '❌ 鉴权失败' : '✅ 已认证'} |{' '}
        tenant: <strong>{user?.tenant_id ?? '-'}</strong> |{' '}
        <a href="/api/health" target="_blank" rel="noreferrer">API 健康</a>
      </div>
    </div>
  );
}

function SummaryCard({ color, icon, label, value, link }: {
  color: string; icon: string; label: string; value: number | undefined; link: string;
}) {
  return (
    <Link to={link} style={{
      padding: 14, background: 'white', borderRadius: 8, borderLeft: `4px solid ${color}`,
      boxShadow: '0 1px 3px rgba(0,0,0,0.1)', textDecoration: 'none', color: '#1e293b',
    }}>
      <div style={{ fontSize: 12, color: '#64748b' }}>{icon} {label}</div>
      <div style={{ fontSize: 28, fontWeight: 600, color, marginTop: 4 }}>
        {value === undefined ? '…' : value}
      </div>
    </Link>
  );
}

function Panel({ title, link, linkText, children }: {
  title: string; link: string; linkText: string; children: React.ReactNode;
}) {
  return (
    <div style={{ background: 'white', padding: 12, borderRadius: 8, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h3 style={{ margin: 0, fontSize: 14 }}>{title}</h3>
        <Link to={link} style={{ fontSize: 11, color: '#3b82f6' }}>{linkText} →</Link>
      </div>
      <div>{children}</div>
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return <div style={{ padding: 16, textAlign: 'center', color: '#94a3b8' }}>{text}</div>;
}

const itemLink: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: '1fr auto auto', gap: 8, alignItems: 'center',
  padding: 6, borderBottom: '1px solid #f1f5f9', color: '#1e293b', textDecoration: 'none', fontSize: 13,
};

function AuditWidget() {
  const { data, isLoading } = useQuery({
    queryKey: ['audit-recent'],
    queryFn: async () => {
      const r = await apiClient.get<{ code: number; data: { logs: AuditPreview[]; total: number } }>(
        '/audit/logs?limit=8');
      return r.data.data;
    },
    refetchInterval: 30000,  // 30s 自动刷新
  });
  const logs = data?.logs ?? [];
  return (
    <div style={{ background: 'white', padding: 12, borderRadius: 8, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h3 style={{ margin: 0, fontSize: 14 }}>🔍 最近审计 ({data?.total ?? '…'})</h3>
        <Link to="/admin/audit" style={{ fontSize: 11, color: '#3b82f6' }}>查看全部 →</Link>
      </div>
      {isLoading && <Empty text="加载中…" />}
      {!isLoading && logs.length === 0 && <Empty text="暂无审计记录" />}
      {logs.map((l) => {
        const color = l.action.startsWith('CREATE') || l.action === 'APPROVE' ? '#10b981'
          : l.action.startsWith('DELETE') || l.action === 'REJECT' ? '#ef4444'
          : l.action === 'TRIGGER' ? '#8b5cf6'
          : '#3b82f6';
        return (
          <div key={l.id} style={itemLink}>
            <span style={{ fontWeight: 500, fontSize: 12 }}>
              <span style={{ color, marginRight: 6 }}>●</span>
              {l.username || '?'} → <code style={{ fontSize: 11 }}>{l.resource}</code>
            </span>
            <span style={{
              fontSize: 10, padding: '1px 6px', borderRadius: 3,
              background: color + '20', color,
            }}>{l.action}</span>
            <span style={{ color: '#94a3b8', fontSize: 11 }}>{l.created_at.slice(11, 19)}</span>
          </div>
        );
      })}
    </div>
  );
}
