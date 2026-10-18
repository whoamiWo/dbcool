import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import ReactFlow, { Background, Controls, ReactFlowProvider, Handle, Position, type Node, type Edge } from 'reactflow';
import apiClient from '@/api/client';

interface Instance {
  id: string;
  workflow_id: string;
  workflow_name: string;
  workflow_title: string;
  status: string;
  record_id: string;
  current_node_index: number;
  started_at: string;
  finished_at: string;
  trigger_data_json: string;
}
interface Task {
  id: string;
  node_id: string;
  node_type: string;
  assignee?: string;
  status: string;
  comment?: string;
  created_at: string;
  finished_at?: string;
}
interface NodeDef { id: string; type: string; config?: Record<string, string>; position?: { x: number; y: number }; }
interface EdgeDef { id?: string; source: string; target: string; sourceHandle?: string | null; }

const statusColor: Record<string, string> = {
  RUNNING:   'var(--color-info)',
  PENDING:   'var(--color-warning)',
  COMPLETED: 'var(--color-success)',
  FAILED:    'var(--color-error)',
  REJECTED:  'var(--color-secondary-500)',
};

/* US-407: 把毫秒时长渲染成人性化字符串 */
function humanDuration(ms: number, t: (key: string) => string): string {
  if (ms < 1000) return `${ms} ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s} ${t('workflowInstances.durationSeconds')}`;
  const m = Math.floor(s / 60);
  const remS = s % 60;
  if (m < 60) return `${m} ${t('workflowInstances.durationMinutes')} ${remS} ${t('workflowInstances.durationSeconds')}`;
  const h = Math.floor(m / 60);
  const remM = m % 60;
  return `${h} ${t('workflowInstances.durationHours')} ${remM} ${t('workflowInstances.durationMinutes')}`;
}

/* US-407: 把后端 JSON 字符串美化展示(失败则原文) */
function formatJson(s: string | null | undefined, t: (key: string) => string): string {
  if (!s) return t('workflowInstances.emptyValue');
  try { return JSON.stringify(JSON.parse(s), null, 2); }
  catch { return s; }
}

export function WorkflowInstancesPage() {
  return (
    <ReactFlowProvider>
      <InstancesInner />
    </ReactFlowProvider>
  );
}

function InstancesInner() {
  const { t } = useTranslation();
  const { id } = useParams<{ id?: string }>();
  const [wfFilter, setWfFilter] = useState('');

  const { data: list } = useQuery({
    queryKey: ['wf-instances', wfFilter],
    queryFn: async () => {
      const url = wfFilter ? `/workflows/instances?workflowId=${wfFilter}` : '/workflows/instances';
      return apiClient.get<Instance[]>(url);
    },
  });

  const { data: workflows } = useQuery({
    queryKey: ['workflows'],
    queryFn: () => apiClient.get<{ id: string; name: string; title: string }[]>('/workflows'),
  });

  return (
    <div style={{ padding: 16 }}>
      <h2>{t('workflowInstances.pageTitle')}</h2>
      {!id && (
        <>
          <div style={{ marginBottom: 12, display: 'flex', gap: 8, alignItems: 'center' }}>
            <label htmlFor="w-lbl-1">{t('workflowInstances.filterWorkflow')}:</label>
            <select id="w-lbl-1" value={wfFilter} onChange={(e) => setWfFilter(e.target.value)} style={{ padding: 4 }}>
              <option value="">{t('workflowInstances.all')}</option>
              {workflows?.map((w) => <option key={w.id} value={w.id}>{w.title || w.name}</option>)}
            </select>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse', background: 'var(--color-text-primary)' }}>
            <thead>
              <tr style={{ background: 'var(--color-bg-secondary)' }}>
                <th style={th}>{t('workflowInstances.colWorkflow')}</th><th style={th}>{t('workflowInstances.colStatus')}</th>
                <th style={th}>{t('workflowInstances.colRelatedRecord')}</th><th style={th}>{t('workflowInstances.colTriggerTime')}</th>
                <th style={th}>{t('workflowInstances.colEndTime')}</th><th style={th} aria-label={t('workflowInstances.colAction')}></th>
              </tr>
            </thead>
            <tbody>
              {(list ?? []).map((i) => (
                <tr key={i.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                  <td style={td}>{i.workflow_title || i.workflow_name}</td>
                  <td style={td}>
                    <span style={{ padding: '2px 8px', background: statusColor[i.status] || 'var(--color-text-muted)',
                                   color: 'var(--color-text-primary)', borderRadius: 4, fontSize: 12 }}>
                      {i.status}
                    </span>
                  </td>
                  <td style={td}>{i.record_id || '—'}</td>
                  <td style={td}>{(i.started_at || '').replace('T', ' ').slice(0, 19)}</td>
                  <td style={td}>{(i.finished_at || '').replace('T', ' ').slice(0, 19)}</td>
                  <td style={td}>
                    <Link to={`/designer/instances/${i.id}`}>{t('workflowInstances.viewDetails')}</Link>
                  </td>
                </tr>
              ))}
              {(!list || list.length === 0) && (
                <tr><td colSpan={6} style={{ ...td, textAlign: 'center', color: 'var(--color-text-muted)' }}>{t('workflowInstances.noInstances')}</td></tr>
              )}
            </tbody>
          </table>
        </>
      )}
      {id && <InstanceDetail id={id} />}
    </div>
  );
}

function InstanceDetail({ id }: { id: string }) {
  const { t } = useTranslation();
  const { data } = useQuery({
    queryKey: ['wf-instance', id],
    queryFn: () => apiClient.get<Instance & { tasks: Task[] }>(`/workflows/instances/${id}`),
  });
  const { data: wf } = useQuery({
    queryKey: ['workflow', data?.workflow_id],
    queryFn: () => apiClient.get<{ nodes_json: string; edges_json: string }>(`/workflows/${data!.workflow_id}`),
    enabled: !!data?.workflow_id,
  });

  if (!data) return <p>{t('common.loading')}</p>;

  let nodes: NodeDef[] = [];
  let edges: EdgeDef[] = [];
  try { nodes = JSON.parse(wf?.nodes_json || '[]'); } catch {}
  try { edges = JSON.parse(wf?.edges_json || '[]'); } catch {}

  // 状态决定哪些节点完成/当前/未到
  const isFinished = data.status === 'COMPLETED' || data.status === 'FAILED' || data.status === 'REJECTED';
  const currentIdx = data.current_node_index ?? -1;

  const rfNodes: Node[] = nodes.map((n, i) => {
    let nodeColor = 'var(--color-border-medium)'; // 灰 — 未到
    let borderColor = 'var(--color-border-medium)';
    if (i < currentIdx || (isFinished && i <= currentIdx)) {
      nodeColor = 'var(--color-success)'; borderColor = 'var(--color-success)'; // 绿 — 已完成
    } else if (i === currentIdx && !isFinished) {
      nodeColor = 'var(--color-warning)'; borderColor = 'var(--color-warning)'; // 黄 — 当前
    }
    return {
      id: n.id,
      type: 'flowNode',
      position: n.position || { x: 100 + i * 220, y: 100 },
      data: { kind: n.type, config: n.config || {}, nodeColor, borderColor, isCurrent: i === currentIdx && !isFinished, onChange: () => {}, onDelete: () => {} },
    };
  });
  const rfEdges: Edge[] = edges.map((e, i) => ({
    id: e.id || `e${i}`, source: e.source, target: e.target, sourceHandle: e.sourceHandle ?? undefined,
    style: { stroke: currentIdx >= 0 ? 'var(--color-success)' : 'var(--color-border-medium)' },
  }));

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Link to="/designer/instances">{t('workflowInstances.backToList')}</Link>
      </div>
      <div style={{ background: 'var(--color-text-primary)', padding: 12, borderRadius: 8, marginBottom: 12 }}>
        <h3 style={{ marginTop: 0 }}>{t('workflowInstances.instancePrefix')} {data.id.slice(0, 8)}...</h3>
        <div>{t('workflowInstances.colStatus')}: <span style={{ padding: '2px 8px', background: statusColor[data.status], color: 'var(--color-text-primary)', borderRadius: 4 }}>
          {data.status}
        </span></div>
        <div>{t('workflowInstances.workflowLabel')}: {data.workflow_title} ({data.workflow_name})</div>
        <div>{t('workflowInstances.colTriggerTime')}: {data.started_at}</div>
        <div>{t('workflowInstances.colEndTime')}: {data.finished_at || '—'}</div>
        <div>{t('workflowInstances.currentNode')}: #{currentIdx} ({nodes[currentIdx]?.id} - {nodes[currentIdx]?.type})</div>
        <details style={{ marginTop: 6 }}>
          <summary style={{ cursor: 'pointer', fontSize: 13 }}>{t('workflowInstances.triggerDataLabel')}</summary>
          <pre style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-border-light)', padding: 8, borderRadius: 4,
                        fontSize: 12, overflow: 'auto', maxHeight: 160, margin: '6px 0 0' }}>
            {formatJson(data.trigger_data_json, t)}
          </pre>
        </details>
      </div>

      {data.tasks && data.tasks.length > 0 && (
        <div style={{ background: 'var(--color-text-primary)', padding: 12, borderRadius: 8, marginBottom: 12 }}>
          <h4>{t('workflowInstances.approvalTasks')}({data.tasks.length})</h4>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
            <thead>
              <tr style={{ background: 'var(--color-text-primary)', color: 'var(--color-bg-elevated)' }}>
                <th style={{ ...th, fontSize: 12 }}>{t('workflowInstances.colNode')}</th>
                <th style={{ ...th, fontSize: 12 }}>{t('workflowInstances.colType')}</th>
                <th style={{ ...th, fontSize: 12 }}>{t('workflowInstances.colStatus')}</th>
                <th style={{ ...th, fontSize: 12 }}>{t('workflowInstances.colAssignee')}</th>
                <th style={{ ...th, fontSize: 12 }}>{t('workflowInstances.colCreatedAt')}</th>
                <th style={{ ...th, fontSize: 12 }}>{t('workflowInstances.colFinishedAt')}</th>
                <th style={{ ...th, fontSize: 12 }}>{t('workflowInstances.colDuration')}</th>
              </tr>
            </thead>
            <tbody>
              {data.tasks.map((taskItem) => {
                const dur = taskItem.finished_at && taskItem.created_at
                  ? humanDuration(new Date(taskItem.finished_at).getTime() - new Date(taskItem.created_at).getTime(), t)
                  : (taskItem.status === 'PENDING' ? `— ${t('workflowInstances.inProgress')}` : '—');
                return (
                  <tr key={taskItem.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                    <td style={td}><code>{taskItem.node_id}</code></td>
                    <td style={td}>{taskItem.node_type}</td>
                    <td style={td}>
                      <span style={{ padding: '1px 6px', background: statusColor[taskItem.status] || 'var(--color-text-muted)',
                                     color: 'var(--color-text-primary)', borderRadius: 3, fontSize: 11 }}>
                        {taskItem.status}
                      </span>
                    </td>
                    <td style={td}>{taskItem.assignee?.slice(0, 8) ?? '—'}</td>
                    <td style={td}>{taskItem.created_at?.replace('T', ' ').slice(0, 19)}</td>
                    <td style={td}>{taskItem.finished_at?.replace('T', ' ').slice(0, 19) ?? '—'}</td>
                    <td style={td}>{dur}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <div style={{ background: 'var(--color-text-primary)', borderRadius: 8, height: 400 }}>
        <ReactFlow nodes={rfNodes} edges={rfEdges} nodeTypes={nodeTypes} fitView>
          <Background />
          <Controls />
        </ReactFlow>
      </div>

      <div style={{ marginTop: 12, fontSize: 13, color: 'var(--color-bg-elevated)' }}>
        {t('workflowInstances.legend')}: <span style={{ color: 'var(--color-success)' }}>■ {t('workflowInstances.legendCompleted')}</span>{' '}
        <span style={{ color: 'var(--color-warning)' }}>■ {t('workflowInstances.legendCurrent')}</span>{' '}
        <span style={{ color: 'var(--color-text-muted)' }}>■ {t('workflowInstances.legendPending')}</span>
      </div>
    </div>
  );
}

function FlowNode({ data }: { data: { kind: string; config: Record<string, string>; nodeColor: string; borderColor: string; isCurrent: boolean } }) {
  const { t } = useTranslation();
  const label: Record<string, string> = {
    APPROVAL: `📝 ${t('workflowInstances.nodeApproval')}`,
    NOTIFICATION: `🔔 ${t('workflowInstances.nodeNotification')}`,
    CONDITION: `🔀 ${t('workflowInstances.nodeCondition')}`,
    HTTP: `🌐 ${t('workflowInstances.nodeHttp')}`,
  };
  return (
    <div style={{
      padding: 10, minWidth: 140, background: 'var(--color-text-primary)', position: 'relative',
      border: `2px solid ${data.borderColor}`,
      borderRadius: 8, boxShadow: data.isCurrent ? '0 0 12px var(--color-warning)' : '0 2px 6px rgba(0,0,0,0.1)',
    }}>
      <strong style={{ color: data.nodeColor }}>{label[data.kind] || data.kind}</strong>
      <Handle type="target" position={Position.Left} style={{ background: data.nodeColor, width: 10, height: 10 }} />
      <Handle type="source" position={Position.Bottom} style={{ background: data.nodeColor, width: 10, height: 10 }} />
    </div>
  );
}
const nodeTypes = { flowNode: FlowNode };

const th = { padding: 8, textAlign: 'left', fontSize: 13, color: 'var(--color-bg-elevated)' } as const;
const td = { padding: 8, fontSize: 13 } as const;
