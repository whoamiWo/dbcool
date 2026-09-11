import { Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { useAuthStore } from '@/stores/auth';

interface Task {
  id: string;
  instance_id: string;
  node_id: string;
  node_type: string;
  status: string;
  comment: string;
  created_at: string;
  finished_at: string;
  trigger_data_json: string;
  record_id: string;
  instance_status: string;
  workflow_id: string;
  workflow_name: string;
  workflow_title: string;
}

const statusColor: Record<string, string> = {
  PENDING: '#f59e0b',
  APPROVED: '#10b981',
  REJECTED: '#ef4444',
};

export function MyTasksPage() {
  const { user } = useAuthStore();
  const qc = useQueryClient();

  const { data: tasks, isLoading } = useQuery({
    queryKey: ['my-tasks', user?.id],
    queryFn: () => apiClient.get<Task[]>('/workflows/tasks/my'),
  });

  const act = useMutation({
    mutationFn: ({ id, op, comment }: { id: string; op: 'approve' | 'reject'; comment?: string }) =>
      apiClient.post(`/workflows/tasks/${id}/${op}`, { comment: comment || '' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['my-tasks'] });
      qc.invalidateQueries({ queryKey: ['wf-instances'] });
    },
  });

  const pending = (tasks ?? []).filter((t) => t.status === 'PENDING');
  return (
    <div style={{ padding: 16 }}>
      <h2>📋 我的待办任务</h2>
      <p style={{ color: '#475569' }}>
        待审批 {pending.length} 项 
      </p>

      {isLoading && <p>加载中...</p>}

      <TaskTable tasks={pending} showActions act={act} />

      {pending.length === 0 && (
        <div style={{ padding: 32, textAlign: 'center', color: '#94a3b8', background: 'white', borderRadius: 8 }}>
          🎉 当前没有待办任务
        </div>
      )}
    </div>
  );
}

function TaskTable({ tasks, showActions, act }: {
  tasks: Task[]; showActions?: boolean;
  act: ReturnType<typeof useMutation<unknown, unknown, { id: string; op: 'approve' | 'reject'; comment?: string }>>;
}) {
  return (
    <table style={{ width: '100%', borderCollapse: 'collapse', background: 'white', borderRadius: 8 }}>
      <thead>
        <tr style={{ background: '#f1f5f9' }}>
          <th style={th}>工作流</th>
          <th style={th}>节点</th>
          <th style={th}>触发数据</th>
          <th style={th}>关联记录</th>
          <th style={th}>创建时间</th>
          <th style={th}>状态</th>
          {showActions && <th style={th}>操作</th>}
        </tr>
      </thead>
      <tbody>
        {tasks.map((t) => {
          let trig: Record<string, unknown> = {};
          try { trig = JSON.parse(t.trigger_data_json || '{}'); } catch {}
          return (
            <tr key={t.id} style={{ borderTop: '1px solid #e2e8f0' }}>
              <td style={td}>
                <Link to={`/designer/instances/${t.instance_id}`}>{t.workflow_title || t.workflow_name}</Link>
              </td>
              <td style={td}>{t.node_id}</td>
              <td style={td}>
                <code style={{ fontSize: 11, background: '#f1f5f9', padding: 2 }}>
                  {JSON.stringify(trig).slice(0, 40)}
                </code>
              </td>
              <td style={td}>{t.record_id || '—'}</td>
              <td style={td}>{(t.created_at || '').replace('T', ' ').slice(0, 19)}</td>
              <td style={td}>
                <span style={{ padding: '2px 8px', background: statusColor[t.status] || '#94a3b8',
                               color: 'white', borderRadius: 4, fontSize: 12 }}>
                  {t.status}
                </span>
              </td>
              {showActions && (
                <td style={td}>
                  <button
                    onClick={() => act.mutate({ id: t.id, op: 'approve' })}
                    disabled={act.isPending}
                    style={{ marginRight: 4, padding: '4px 10px', background: '#10b981',
                             color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                  >✅ 通过</button>
                  <button
                    onClick={() => act.mutate({ id: t.id, op: 'reject' })}
                    disabled={act.isPending}
                    style={{ padding: '4px 10px', background: '#ef4444',
                             color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                  >❌ 拒绝</button>
                </td>
              )}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

const th = { padding: 8, textAlign: 'left', fontSize: 13, color: '#475569' } as const;
const td = { padding: 8, fontSize: 13 } as const;
