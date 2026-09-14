import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
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
  RUNNING:   '#3b82f6',
  PENDING:   '#f59e0b',
  COMPLETED: '#10b981',
  FAILED:    '#ef4444',
  REJECTED:  '#a855f7',
};

/* US-407: 把毫秒时长渲染成人性化字符串 */
function humanDuration(ms: number): string {
  if (ms < 1000) return `${ms} ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s} 秒`;
  const m = Math.floor(s / 60);
  const remS = s % 60;
  if (m < 60) return `${m} 分 ${remS} 秒`;
  const h = Math.floor(m / 60);
  const remM = m % 60;
  return `${h} 时 ${remM} 分`;
}

/* US-407: 把后端 JSON 字符串美化展示(失败则原文) */
function formatJson(s: string | null | undefined): string {
  if (!s) return '(空)';
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
      <h2>工作流实例</h2>
      {!id && (
        <>
          <div style={{ marginBottom: 12, display: 'flex', gap: 8, alignItems: 'center' }}>
            <label>筛选工作流:</label>
            <select value={wfFilter} onChange={(e) => setWfFilter(e.target.value)} style={{ padding: 4 }}>
              <option value="">全部</option>
              {workflows?.map((w) => <option key={w.id} value={w.id}>{w.title || w.name}</option>)}
            </select>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse', background: 'white' }}>
            <thead>
              <tr style={{ background: '#f1f5f9' }}>
                <th style={th}>工作流</th><th style={th}>状态</th>
                <th style={th}>关联记录</th><th style={th}>触发时间</th>
                <th style={th}>结束时间</th><th style={th}></th>
              </tr>
            </thead>
            <tbody>
              {(list ?? []).map((i) => (
                <tr key={i.id} style={{ borderTop: '1px solid #e2e8f0' }}>
                  <td style={td}>{i.workflow_title || i.workflow_name}</td>
                  <td style={td}>
                    <span style={{ padding: '2px 8px', background: statusColor[i.status] || '#94a3b8',
                                   color: 'white', borderRadius: 4, fontSize: 12 }}>
                      {i.status}
                    </span>
                  </td>
                  <td style={td}>{i.record_id || '—'}</td>
                  <td style={td}>{(i.started_at || '').replace('T', ' ').slice(0, 19)}</td>
                  <td style={td}>{(i.finished_at || '').replace('T', ' ').slice(0, 19)}</td>
                  <td style={td}>
                    <Link to={`/designer/instances/${i.id}`}>查看</Link>
                  </td>
                </tr>
              ))}
              {(!list || list.length === 0) && (
                <tr><td colSpan={6} style={{ ...td, textAlign: 'center', color: '#94a3b8' }}>暂无实例</td></tr>
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
  const { data } = useQuery({
    queryKey: ['wf-instance', id],
    queryFn: () => apiClient.get<Instance & { tasks: Task[] }>(`/workflows/instances/${id}`),
  });
  const { data: wf } = useQuery({
    queryKey: ['workflow', data?.workflow_id],
    queryFn: () => apiClient.get<{ nodes_json: string; edges_json: string }>(`/workflows/${data!.workflow_id}`),
    enabled: !!data?.workflow_id,
  });

  if (!data) return <p>加载中...</p>;

  let nodes: NodeDef[] = [];
  let edges: EdgeDef[] = [];
  try { nodes = JSON.parse(wf?.nodes_json || '[]'); } catch {}
  try { edges = JSON.parse(wf?.edges_json || '[]'); } catch {}

  // 状态决定哪些节点完成/当前/未到
  const isFinished = data.status === 'COMPLETED' || data.status === 'FAILED' || data.status === 'REJECTED';
  const currentIdx = data.current_node_index ?? -1;

  const rfNodes: Node[] = nodes.map((n, i) => {
    let nodeColor = '#cbd5e1'; // 灰 — 未到
    let borderColor = '#cbd5e1';
    if (i < currentIdx || (isFinished && i <= currentIdx)) {
      nodeColor = '#10b981'; borderColor = '#10b981'; // 绿 — 已完成
    } else if (i === currentIdx && !isFinished) {
      nodeColor = '#f59e0b'; borderColor = '#f59e0b'; // 黄 — 当前
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
    style: { stroke: currentIdx >= 0 ? '#10b981' : '#cbd5e1' },
  }));

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Link to="/designer/instances">← 返回列表</Link>
      </div>
      <div style={{ background: 'white', padding: 12, borderRadius: 8, marginBottom: 12 }}>
        <h3 style={{ marginTop: 0 }}>实例 {data.id.slice(0, 8)}...</h3>
        <div>状态: <span style={{ padding: '2px 8px', background: statusColor[data.status], color: 'white', borderRadius: 4 }}>
          {data.status}
        </span></div>
        <div>工作流: {data.workflow_title} ({data.workflow_name})</div>
        <div>触发时间: {data.started_at}</div>
        <div>结束时间: {data.finished_at || '—'}</div>
        <div>当前节点: #{currentIdx} ({nodes[currentIdx]?.id} - {nodes[currentIdx]?.type})</div>
        <details style={{ marginTop: 6 }}>
          <summary style={{ cursor: 'pointer', fontSize: 13 }}>触发数据 triggerData</summary>
          <pre style={{ background: '#1e293b', color: '#e2e8f0', padding: 8, borderRadius: 4,
                        fontSize: 12, overflow: 'auto', maxHeight: 160, margin: '6px 0 0' }}>
            {formatJson(data.trigger_data_json)}
          </pre>
        </details>
      </div>

      {data.tasks && data.tasks.length > 0 && (
        <div style={{ background: 'white', padding: 12, borderRadius: 8, marginBottom: 12 }}>
          <h4>审批任务({data.tasks.length})</h4>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
            <thead>
              <tr style={{ background: '#f8fafc', color: '#475569' }}>
                <th style={{ ...th, fontSize: 12 }}>节点</th>
                <th style={{ ...th, fontSize: 12 }}>类型</th>
                <th style={{ ...th, fontSize: 12 }}>状态</th>
                <th style={{ ...th, fontSize: 12 }}>指派</th>
                <th style={{ ...th, fontSize: 12 }}>创建</th>
                <th style={{ ...th, fontSize: 12 }}>完成</th>
                <th style={{ ...th, fontSize: 12 }}>耗时</th>
              </tr>
            </thead>
            <tbody>
              {data.tasks.map((t) => {
                const dur = t.finished_at && t.created_at
                  ? humanDuration(new Date(t.finished_at).getTime() - new Date(t.created_at).getTime())
                  : (t.status === 'PENDING' ? '— 进行中' : '—');
                return (
                  <tr key={t.id} style={{ borderTop: '1px solid #e2e8f0' }}>
                    <td style={td}><code>{t.node_id}</code></td>
                    <td style={td}>{t.node_type}</td>
                    <td style={td}>
                      <span style={{ padding: '1px 6px', background: statusColor[t.status] || '#94a3b8',
                                     color: 'white', borderRadius: 3, fontSize: 11 }}>
                        {t.status}
                      </span>
                    </td>
                    <td style={td}>{t.assignee?.slice(0, 8) ?? '—'}</td>
                    <td style={td}>{t.created_at?.replace('T', ' ').slice(0, 19)}</td>
                    <td style={td}>{t.finished_at?.replace('T', ' ').slice(0, 19) ?? '—'}</td>
                    <td style={td}>{dur}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <div style={{ background: '#f8fafc', borderRadius: 8, height: 400 }}>
        <ReactFlow nodes={rfNodes} edges={rfEdges} nodeTypes={nodeTypes} fitView>
          <Background />
          <Controls />
        </ReactFlow>
      </div>

      <div style={{ marginTop: 12, fontSize: 13, color: '#475569' }}>
        图例: <span style={{ color: '#10b981' }}>■ 已完成</span>{' '}
        <span style={{ color: '#f59e0b' }}>■ 当前</span>{' '}
        <span style={{ color: '#94a3b8' }}>■ 未到</span>
      </div>
    </div>
  );
}

function FlowNode({ data }: { data: { kind: string; config: Record<string, string>; nodeColor: string; borderColor: string; isCurrent: boolean } }) {
  const label: Record<string, string> = {
    APPROVAL: '📝 审批', NOTIFICATION: '🔔 通知', CONDITION: '🔀 条件', HTTP: '🌐 HTTP',
  };
  return (
    <div style={{
      padding: 10, minWidth: 140, background: 'white', position: 'relative',
      border: `2px solid ${data.borderColor}`,
      borderRadius: 8, boxShadow: data.isCurrent ? '0 0 12px #f59e0b' : '0 2px 6px rgba(0,0,0,0.1)',
    }}>
      <strong style={{ color: data.nodeColor }}>{label[data.kind] || data.kind}</strong>
      <Handle type="target" position={Position.Left} style={{ background: data.nodeColor, width: 10, height: 10 }} />
      <Handle type="source" position={Position.Bottom} style={{ background: data.nodeColor, width: 10, height: 10 }} />
    </div>
  );
}
const nodeTypes = { flowNode: FlowNode };

const th = { padding: 8, textAlign: 'left', fontSize: 13, color: '#475569' } as const;
const td = { padding: 8, fontSize: 13 } as const;
