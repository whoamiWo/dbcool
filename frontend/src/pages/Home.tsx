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
interface ApiEnvelope<T> { code: number; message: string; data: T; }

export function HomePage() {
  const { user } = useAuthStore();

  const meQuery = useQuery({ queryKey: ['me'], queryFn: () => apiClient.get<ApiEnvelope<UserInfo>>('/users/me') });
  const colsQuery = useQuery({ queryKey: ['collections'], queryFn: () => apiClient.get<ApiEnvelope<CollectionMeta[]>>('/collections') });
  const wfQuery = useQuery({ queryKey: ['workflows'], queryFn: () => apiClient.get<ApiEnvelope<unknown[]>>('/workflows') });
  const unreadQuery = useQuery({
    queryKey: ['messages', 'unread'],
    queryFn: () => apiClient.get<ApiEnvelope<{ unread_count: number; messages: MessagePreview[] }>>('/messages?limit=5&unreadOnly=true'),
  });
  const tasksQuery = useQuery({
    queryKey: ['my-tasks'],
    queryFn: () => apiClient.get<ApiEnvelope<TaskPreview[]>>('/workflows/tasks/my'),
  });
  const instancesQuery = useQuery({
    queryKey: ['wf-instances'],
    queryFn: () => apiClient.get<ApiEnvelope<InstancePreview[]>>('/workflows/instances'),
  });

  const pendingTasks = (tasksQuery.data?.data ?? []).filter((t) => t.status === 'PENDING');
  const runningInstances = (instancesQuery.data?.data ?? []).filter((i) => i.status === 'RUNNING' || i.status === 'PENDING');

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <h1 style={{ marginBottom: 24, fontSize: 24, fontWeight: 600, color: 'var(--color-text-primary)' }}>
        👋 欢迎,{(user as { display_name?: string })?.display_name ?? user?.username ?? '游客'}
      </h1>

      {/* 摘要卡片 */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginTop: 16 }}>
        <SummaryCard color="var(--color-info)" icon="📋" label="未读站内信" value={unreadQuery.data?.data?.unread_count} link="/messages" />
        <SummaryCard color="var(--color-warning)" icon="📝" label="待我审批" value={pendingTasks.length} link="/tasks/my" />
        <SummaryCard color="var(--color-success)" icon="📊" label="运行中工作流" value={runningInstances.length} link="/designer/instances" />
        <SummaryCard color="var(--color-secondary-500)" icon="📐" label="Collection 数" value={colsQuery.data?.data?.length} link="/designer/schemas" />
        <SummaryCard color="var(--color-primary-500)" icon="🔧" label="工作流数" value={wfQuery.data?.data?.length} link="/designer/workflows" />
      </div>

      {/* 待办 + 运行中 */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginTop: 16 }}>
        <Panel title="⏳ 待审批任务" link="/tasks/my" linkText="查看全部">
          {pendingTasks.length === 0 && <Empty text="当前无待审批 🎉" />}
          {pendingTasks.slice(0, 5).map((t) => (
            <Link key={t.id} to={`/designer/instances/${t.instance_id}`} style={itemLink}>
              <span style={{ fontWeight: 500 }}>{t.workflow_title}</span>
              <span style={{ color: 'var(--color-text-muted)', fontSize: 11 }}>{t.node_id}</span>
              <span style={{ color: 'var(--color-text-muted)', fontSize: 11 }}>{(t.created_at || '').slice(0, 16)}</span>
            </Link>
          ))}
        </Panel>

        <Panel title="🔄 运行中实例" link="/designer/instances" linkText="查看全部">
          {runningInstances.length === 0 && <Empty text="无运行中实例" />}
          {runningInstances.slice(0, 5).map((i) => (
            <Link key={i.id} to={`/designer/instances/${i.id}`} style={itemLink}>
              <span style={{ fontWeight: 500 }}>{i.workflow_title}</span>
              <span style={{ color: i.status === 'PENDING' ? 'var(--color-warning)' : 'var(--color-info)', fontSize: 11, fontWeight: 600 }}>
                {i.status}
              </span>
              <span style={{ color: 'var(--color-text-muted)', fontSize: 11 }}>{(i.started_at || '').slice(0, 16)}</span>
            </Link>
          ))}
        </Panel>
      </div>

      {/* 未读站内信 + 最近审计 */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginTop: 16 }}>
        <Panel title="📨 未读站内信" link="/messages" linkText="查看全部">
          {(unreadQuery.data?.data?.messages ?? []).length === 0 && <Empty text="无未读消息" />}
          {(unreadQuery.data?.data?.messages ?? []).map((m) => (
            <div key={m.id} style={itemLink}>
              <span style={{ fontWeight: 500 }}>{m.title}</span>
              <span style={{ color: 'var(--color-text-muted)', fontSize: 11 }}>{(m.created_at || '').slice(0, 16)}</span>
            </div>
          ))}
        </Panel>
        <AuditWidget />
      </div>

      {/* 系统状态 */}
      <div style={{ marginTop: 16, padding: 12, background: 'rgba(30, 41, 59, 0.5)', borderRadius: 'var(--radius-md)', color: 'var(--color-text-muted)', fontSize: 12, backdropFilter: 'blur(10px)' }}>
        系统状态: {meQuery.isError ? '❌ 鉴权失败' : '✅ 已认证'} |{' '}
        tenant: <strong style={{ color: 'var(--color-text-primary)' }}>{user?.tenant_id ?? '-'}</strong> |{' '}
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
      padding: 14,
      background: 'rgba(30, 41, 59, 0.7)',
      backdropFilter: 'blur(10px)',
      borderRadius: 'var(--radius-lg)',
      border: '1px solid rgba(255,255,255,0.1)',
      boxShadow: 'var(--shadow-md)',
      textDecoration: 'none',
      color: 'var(--color-text-primary)',
      transition: 'all var(--transition-normal)',
    }}
    onMouseEnter={(e) => {
      (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.08)';
      (e.currentTarget as HTMLElement).style.transform = 'translateY(-2px)';
    }}
    onMouseLeave={(e) => {
      (e.currentTarget as HTMLElement).style.background = 'rgba(30, 41, 59, 0.7)';
      (e.currentTarget as HTMLElement).style.transform = 'translateY(0)';
    }}
    >
      <div style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>{icon} {label}</div>
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
    <div className="glass-card" style={{ padding: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h3 style={{ margin: 0, fontSize: 14, color: 'var(--color-text-primary)' }}>{title}</h3>
        <Link to={link} style={{ fontSize: 11, color: 'var(--color-primary-400)' }}>{linkText} →</Link>
      </div>
      <div>{children}</div>
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return <div style={{ padding: 16, textAlign: 'center', color: 'var(--color-text-muted)' }}>{text}</div>;
}

const itemLink: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: '1fr auto auto', gap: 8, alignItems: 'center',
  padding: 6, borderBottom: '1px solid rgba(255,255,255,0.08)', color: 'var(--color-text-primary)', textDecoration: 'none', fontSize: 13,
  borderRadius: 'var(--radius-sm)',
  transition: 'all var(--transition-fast)',
};

function AuditWidget() {
  const { data, isLoading } = useQuery({
    queryKey: ['audit-recent'],
    queryFn: async () => {
      return apiClient.get<{ logs: AuditPreview[]; total: number }>(
        '/audit/logs?limit=8');
    },
    refetchInterval: 30000,
  });
  const logs = data?.logs ?? [];
  return (
    <div className="glass-card" style={{ padding: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h3 style={{ margin: 0, fontSize: 14, color: 'var(--color-text-primary)' }}>🔍 最近审计 ({data?.total ?? '…'})</h3>
        <Link to="/admin/audit" style={{ fontSize: 11, color: 'var(--color-primary-400)' }}>查看全部 →</Link>
      </div>
      {isLoading && <Empty text="加载中…" />}
      {!isLoading && logs.length === 0 && <Empty text="暂无审计记录" />}
      {logs.map((l: AuditPreview) => {
        const color = l.action.startsWith('CREATE') || l.action === 'APPROVE' ? 'var(--color-success)'
          : l.action.startsWith('DELETE') || l.action === 'REJECT' ? 'var(--color-error)'
          : l.action === 'TRIGGER' ? 'var(--color-secondary-500)'
          : 'var(--color-info)';
        return (
          <div key={l.id} style={itemLink}>
            <span style={{ fontWeight: 500, fontSize: 12 }}>
              <span style={{ color, marginRight: 6 }}>●</span>
              {l.username || '?'} → <code style={{ fontSize: 11 }}>{l.resource}</code>
            </span>
            <span style={{
              fontSize: 10, padding: '1px 6px', borderRadius: 3,
              background: `${color}20`, color,
            }}>{l.action}</span>
            <span style={{ color: 'var(--color-text-muted)', fontSize: 11 }}>{l.created_at.slice(11, 19)}</span>
          </div>
        );
      })}
    </div>
  );
}
