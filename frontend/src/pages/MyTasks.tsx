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
  PENDING: 'var(--color-warning)',
  APPROVED: 'var(--color-success)',
  REJECTED: 'var(--color-error)',
};

export function MyTasksPage() {
  const { user } = useAuthStore();
  const qc = useQueryClient();

  // 注意：apiClient **不解包**响应体，后端统一返回 { code, data } 信封。
  // 此前这里写成 get<Task[]>()，tasks 实际拿到的是整个信封对象，
  // 于是 (tasks ?? []).filter 抛 "…is not a function"，页面直接崩溃
  // （移动端 E2E 在 375px 视口下暴露出来；桌面端同样会崩）。
  const { data: tasks, isLoading } = useQuery({
    queryKey: ['my-tasks', user?.id],
    queryFn: async () => {
      const res = await apiClient.get<{ code: number; data: Task[] }>('/workflows/tasks/my');
      return res?.data ?? [];
    },
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
      <p style={{ color: 'var(--color-bg-elevated)' }}>
        待审批 {pending.length} 项 
      </p>

      {isLoading && <p>加载中...</p>}

      <TaskTable tasks={pending} showActions act={act} />

      {pending.length === 0 && (
        <div style={{ padding: 32, textAlign: 'center', color: 'var(--color-text-muted)', background: 'var(--color-text-primary)', borderRadius: 8 }}>
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
    // 表格包一层可横向滚动的容器：移动端下表格保持可读宽度并在容器内滚动，
    // 而不是把整个页面撑出横向滚动条（红线：页面级不得横向滚动）。
    <div className="table-scroll">
    <table style={{ width: '100%', borderCollapse: 'collapse', background: 'var(--color-text-primary)', borderRadius: 8 }}>
      <thead>
        <tr style={{ background: 'var(--color-bg-secondary)' }}>
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
            <tr key={t.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
              <td style={td}>
                <Link to={`/designer/instances/${t.instance_id}`}>{t.workflow_title || t.workflow_name}</Link>
              </td>
              <td style={td}>{t.node_id}</td>
              <td style={td}>
                <code style={{ fontSize: 11, background: 'var(--color-bg-secondary)', padding: 2 }}>
                  {JSON.stringify(trig).slice(0, 40)}
                </code>
              </td>
              <td style={td}>{t.record_id || '—'}</td>
              <td style={td}>{(t.created_at || '').replace('T', ' ').slice(0, 19)}</td>
              <td style={td}>
                <span style={{ padding: '2px 8px', background: statusColor[t.status] || 'var(--color-text-muted)',
                               color: 'var(--color-text-primary)', borderRadius: 4, fontSize: 12 }}>
                  {t.status}
                </span>
              </td>
              {showActions && (
                <td style={td}>
                  <button
                    onClick={() => act.mutate({ id: t.id, op: 'approve' })}
                    disabled={act.isPending}
                    style={{ marginRight: 4, padding: '4px 10px', background: 'var(--color-success)',
                             color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                  >✅ 通过</button>
                  <button
                    onClick={() => act.mutate({ id: t.id, op: 'reject' })}
                    disabled={act.isPending}
                    style={{ padding: '4px 10px', background: 'var(--color-error)',
                             color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                  >❌ 拒绝</button>
                </td>
              )}
            </tr>
          );
        })}
      </tbody>
    </table>
    </div>
  );
}

const th = { padding: 8, textAlign: 'left', fontSize: 13, color: 'var(--color-bg-elevated)' } as const;
const td = { padding: 8, fontSize: 13 } as const;
