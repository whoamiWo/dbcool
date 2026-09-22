import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  ReactFlowProvider,
  useReactFlow,
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  Handle,
  Position,
  type Node,
  type Edge,
  type Connection,
  type NodeChange,
  type EdgeChange,
} from 'reactflow';
import 'reactflow/dist/style.css';
import apiClient from '@/api/client';

type NodeKind = 'APPROVAL' | 'NOTIFICATION' | 'CONDITION' | 'HTTP' | 'DATA_UPDATE';

interface WorkflowNodeData {
  kind: NodeKind;
  config: Record<string, string>;
  onChange: (patch: Partial<WorkflowNodeData>) => void;
  onDelete: () => void;
}

const nodeKindMeta: Record<NodeKind, { label: string; icon: string; color: string }> = {
  APPROVAL:     { label: '审批',     icon: '📝', color: 'var(--color-info)' },
  NOTIFICATION: { label: '通知',     icon: '🔔', color: 'var(--color-secondary-500)' },
  CONDITION:    { label: '条件',     icon: '🔀', color: 'var(--color-warning)' },
  HTTP:         { label: 'HTTP',    icon: '🌐', color: 'var(--color-success)' },
  DATA_UPDATE:  { label: '数据更新', icon: '✏️', color: 'var(--color-info)' },
};

function FlowNode({ data, selected }: { data: WorkflowNodeData; selected: boolean }) {
  const meta = nodeKindMeta[data.kind];
  const isCondition = data.kind === 'CONDITION';
  return (
    <div style={{
      padding: 10, minWidth: 160, position: 'relative',
      background: 'var(--color-text-primary)',
      border: `2px solid ${selected ? 'var(--color-bg-primary)' : meta.color}`,
      borderRadius: 8,
      boxShadow: '0 2px 6px rgba(0,0,0,0.1)',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <strong>{meta.icon} {meta.label}</strong>
        <button
          onClick={(e) => { e.stopPropagation(); data.onDelete(); }}
          style={{ background: 'none', border: 'none', color: 'var(--color-error)', cursor: 'pointer', fontSize: 16 }}
        >×</button>
      </div>
      <div style={{ marginTop: 4, fontSize: 11, color: 'var(--color-text-disabled)' }}>
        {data.kind === 'NOTIFICATION' && (data.config.message?.slice(0, 40) || '(未设置)')}
        {data.kind === 'HTTP' && `${data.config.method || 'GET'} ${(data.config.url || '').slice(0, 30)}`}
        {data.kind === 'CONDITION' && `if ${data.config.field || '?'} ${data.config.op || '?'} ${data.config.value || '?'}`}
        {data.kind === 'DATA_UPDATE' && `${data.config.collection || '?'} / ${data.config.recordId || '?'}`}
        {data.kind === 'APPROVAL' && '(单审批人)'}
      </div>
      {/* 左边:输入 handle(所有节点) */}
      <Handle type="target" position={Position.Left} style={{ background: meta.color, width: 10, height: 10 }} />
      {/* CONDITION: 底部两个 source handle(then/else) */}
      {isCondition ? (
        <>
          <Handle id="true" type="source" position={Position.Bottom}
                  style={{ left: '30%', background: 'var(--color-success)', width: 12, height: 12, border: '2px solid var(--color-text-primary)' }} />
          <span style={{ position: 'absolute', bottom: -18, left: '20%', fontSize: 10, color: 'var(--color-success)', fontWeight: 'bold' }}>
            ✓ then
          </span>
          <Handle id="false" type="source" position={Position.Bottom}
                  style={{ left: '70%', background: 'var(--color-error)', width: 12, height: 12, border: '2px solid var(--color-text-primary)' }} />
          <span style={{ position: 'absolute', bottom: -18, right: '10%', fontSize: 10, color: 'var(--color-error)', fontWeight: 'bold' }}>
            ✗ else
          </span>
        </>
      ) : (
        // 其它节点:底部 1 个 source
        <Handle type="source" position={Position.Bottom}
                style={{ background: meta.color, width: 10, height: 10 }} />
      )}
    </div>
  );
}
const nodeTypes = { flowNode: FlowNode };

/**
 * 工作流设计器画布版(Week 13 — ReactFlow).
 * 左侧节点面板可拖拽到画布,右侧编辑选中节点的 config.
 */
export function WorkflowDesignerPage() {
  const { id } = useParams<{ id?: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const isEdit = !!id;

  const [name, setName] = useState('new_workflow');
  const [triggerType, setTriggerType] = useState('manual');
  const [title, setTitle] = useState('新工作流');
  const [description, setDescription] = useState('');
  const [collectionName, setCollectionName] = useState('customer');
  const [enabled, setEnabled] = useState(true);
  const [nodes, setNodes] = useState<Node<WorkflowNodeData>[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showSimulate, setShowSimulate] = useState(false);
  const [simulateData, setSimulateData] = useState('{\n  "record_id": "test-001"\n}');
  const [simulateResult, setSimulateResult] = useState<{ instanceId?: string; error?: string } | null>(null);
  const reactFlowWrapper = useRef<HTMLDivElement>(null);
  const { project } = useReactFlow();

  const { data: existing } = useQuery({
    queryKey: ['workflow', id],
    queryFn: async () => {
      // 后端 envelope: {code, message, data: WorkflowMeta}
      // 兼容 vitest mock 直接返 WorkflowMeta 数组 vs 真后端 envelope
      const r = await apiClient.get<WorkflowMeta | { code: number; data: WorkflowMeta }>(`/workflows/${id}`);
      if (Array.isArray(r)) return null; // 数组 → 不可能(详情接口不返数组)
      if ('name' in r) return r as WorkflowMeta; // vitest 模式
      return (r as { code: number; data: WorkflowMeta }).data;
    },
    enabled: isEdit,
  });

  useEffect(() => {
    if (existing) {
      setName(existing.name);
      setTitle(existing.title);
      setDescription(existing.description);
      setCollectionName(existing.collection_name);
      setEnabled(existing.enabled);
      try {
        const t = JSON.parse(existing.trigger_json || '{}');
        if (t.type) setTriggerType(t.type);
      } catch (e) {}
      try {
        const ns = JSON.parse(existing.nodes_json) as WorkflowNodeDef[];
        setNodes(ns.map((n, i) => ({
          id: n.id || `n${i}`,
          type: 'flowNode',
          position: n.position || { x: 100 + i * 220, y: 100 },
          data: {
            kind: n.type as NodeKind,
            config: n.config || {},
            onChange: () => {}, // 会被下面的 useEffect 重新绑定
            onDelete: () => {},
          },
        })));
        setEdges(buildEdgesFromSequence(ns.map((n, i) => n.id || `n${i}`)));
      } catch (e) {}
    }
  }, [existing]);

  // 给每个 node 的 data 绑定回调(避免闭包过期)
  useEffect(() => {
    setNodes((nds) => nds.map((n) => ({
      ...n,
      data: {
        ...n.data,
        onChange: (patch) => updateNodeData(n.id, patch),
        onDelete: () => deleteNode(n.id),
      },
    })));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodes.length, edges.length]);

  const buildEdgesFromSequence = (ids: string[]): Edge[] => {
    const es: Edge[] = [];
    for (let i = 0; i < ids.length - 1; i++) {
      es.push({ id: `e${i}`, source: ids[i], target: ids[i + 1] });
    }
    return es;
  };

  const onNodesChange = useCallback((changes: NodeChange[]) => {
    setNodes((nds) => applyNodeChanges(changes, nds) as Node<WorkflowNodeData>[]);
  }, []);
  const onEdgesChange = useCallback((changes: EdgeChange[]) => {
    setEdges((eds) => applyEdgeChanges(changes, eds));
  }, []);
  const onConnect = useCallback((conn: Connection) => {
    setEdges((eds) => addEdge({ ...conn, id: `e${Date.now()}` }, eds));
  }, []);

  const updateNodeData = (id: string, patch: Partial<WorkflowNodeData>) => {
    setNodes((nds) => nds.map((n) => n.id === id ? { ...n, data: { ...n.data, ...patch } } : n));
  };

  const deleteNode = (id: string) => {
    setNodes((nds) => nds.filter((n) => n.id !== id));
    setEdges((eds) => eds.filter((e) => e.source !== id && e.target !== id));
    if (selectedNodeId === id) setSelectedNodeId(null);
  };

  const onDragStart = (event: React.DragEvent, kind: NodeKind) => {
    event.dataTransfer.setData('application/reactflow', kind);
    event.dataTransfer.effectAllowed = 'move';
  };
  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);
  const onDrop = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    const kind = event.dataTransfer.getData('application/reactflow') as NodeKind;
    if (!kind || !reactFlowWrapper.current) return;
    const bounds = reactFlowWrapper.current.getBoundingClientRect();
    const position = project({ x: event.clientX - bounds.left, y: event.clientY - bounds.top });
    const newNode: Node<WorkflowNodeData> = {
      id: `n${Date.now()}`,
      type: 'flowNode',
      position,
      data: {
        kind, config: defaultConfig(kind),
        onChange: () => {}, onDelete: () => {},
      },
    };
    setNodes((nds) => [...nds, newNode]);
  }, [project]);

  const saveMutation = useMutation({
    mutationFn: () => {
      const nodesOut: WorkflowNodeDef[] = nodes.map((n) => ({
        id: n.id, type: n.data.kind, config: n.data.config,
        position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
      }));
      // 边序列化:React Flow 自动在每条边上带 sourceHandle(用户拖边时选了哪个 handle)
      const edgesOut = edges.map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        sourceHandle: e.sourceHandle ?? null,
      }));
      const body = {
        name, title, description, collectionName, enabled,
        trigger: JSON.stringify({ type: triggerType }),
        nodes: JSON.stringify(nodesOut),
        edges: JSON.stringify(edgesOut),
      };
      return isEdit
        ? apiClient.put<WorkflowMeta>(`/workflows/${id}`, body)
        : apiClient.post<WorkflowMeta>('/workflows', body);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      navigate('/designer/workflows');
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '保存失败');
    },
  });

  const simulateMutation = useMutation({
    mutationFn: async () => {
      // 先保存当前编辑状态,确保触发的是最新版本
      let workflowId = id;
      if (!workflowId) {
        // 新建未保存:先保存
        const saved = await saveMutation.mutateAsync();
        workflowId = (saved as { id: string }).id;
      }
      let parsed: unknown;
      try {
        parsed = JSON.parse(simulateData || '{}');
      } catch {
        throw new Error('triggerData 不是合法 JSON');
      }
      const res = await apiClient.post<{ data?: { id?: string } }>(
        `/workflows/${workflowId}/trigger`, parsed);
      return { workflowId, instanceId: (res as { data?: { id?: string }; id?: string }).data?.id
              ?? (res as { id?: string }).id };
    },
    onSuccess: (r) => {
      setSimulateResult({ instanceId: r.instanceId });
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message
        : (err as { response?: { data?: { message?: string } } }).response?.data?.message ?? '运行失败';
      setSimulateResult({ error: msg });
    },
  });

  const selectedNode = nodes.find((n) => n.id === selectedNodeId);

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 100px)', gap: 12 }}>
      {/* 左:节点面板 + 元数据 */}
      <aside style={{ width: 200, background: 'var(--color-text-primary)', padding: 12, borderRadius: 8, boxShadow: '0 1px 3px rgba(0,0,0,0.1)', overflowY: 'auto' }}>
        <button onClick={() => navigate('/designer/workflows')}
                style={{ background: 'none', border: 'none', color: 'var(--color-text-disabled)', cursor: 'pointer', marginBottom: 8 }}>
          ← 返回列表
        </button>
        <h3 style={{ marginTop: 0 }}>{isEdit ? '编辑' : '新建'}工作流</h3>
        {error && <div style={{ padding: 4, marginBottom: 8, background: 'rgba(239,68,68,0.2)', color: 'var(--color-error)', borderRadius: 4, fontSize: 11 }}>{error}</div>}
        <label style={lbl}>技术名 *</label>
        <input value={name} onChange={(e) => setName(e.target.value)} style={inp} />
        <label style={lbl}>标题</label>
        <input value={title} onChange={(e) => setTitle(e.target.value)} style={inp} />
        <label style={lbl}>Collection</label>
        <input value={collectionName} onChange={(e) => setCollectionName(e.target.value)} style={inp} />
        <label style={lbl}>
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> 启用
        </label>
        <label style={lbl}>触发器</label>
        <select value={triggerType} onChange={(e) => setTriggerType(e.target.value)} style={inp}>
          <option value="manual">⚡ 手动触发</option>
          <option value="on_create">📥 数据创建时</option>
          <option value="on_update">✏️ 数据更新时</option>
          <option value="schedule">⏰ 定时调度</option>
        </select>
        <h4 style={{ marginTop: 12 }}>拖拽节点</h4>
        {(Object.keys(nodeKindMeta) as NodeKind[]).map((k) => {
          const m = nodeKindMeta[k];
          return (
            <div key={k}
                 draggable
                 onDragStart={(e) => onDragStart(e, k)}
                 style={{ padding: 8, marginBottom: 6, background: m.color, color: 'var(--color-text-primary)',
                          borderRadius: 4, cursor: 'grab', fontSize: 13 }}>
              {m.icon} {m.label}
            </div>
          );
        })}
        <button onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}
                style={{ marginTop: 12, width: '100%', padding: 8, background: saveMutation.isPending ? 'var(--color-text-muted)' : 'var(--color-success)',
                         color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
          {saveMutation.isPending ? '保存中…' : '💾 保存'}
        </button>
        {/* US-406: 测试运行 */}
        <button onClick={() => { setSimulateResult(null); setShowSimulate(true); }}
                style={{ marginTop: 8, width: '100%', padding: 8,
                         background: 'var(--color-info)', color: 'var(--color-text-primary)', border: 'none',
                         borderRadius: 4, cursor: 'pointer' }}>
          ▶ 测试运行(模拟数据)
        </button>
      </aside>

      {/* 中:画布 */}
      <div ref={reactFlowWrapper} style={{ flex: 1, background: 'var(--color-text-primary)', borderRadius: 8 }} onDrop={onDrop} onDragOver={onDragOver}>
        <ReactFlow
          nodes={nodes} edges={edges}
          onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={onConnect}
          nodeTypes={nodeTypes}
          onNodeClick={(_e, n) => setSelectedNodeId(n.id)}
          onPaneClick={() => setSelectedNodeId(null)}
          fitView
        >
          <Background />
          <Controls />
          <MiniMap />
        </ReactFlow>
      </div>

      {/* 右:配置面板 */}
      <aside style={{ width: 280, background: 'var(--color-text-primary)', padding: 12, borderRadius: 8, boxShadow: '0 1px 3px rgba(0,0,0,0.1)', overflowY: 'auto' }}>
        <h3 style={{ marginTop: 0 }}>节点配置</h3>
        {!selectedNode ? (
          <p style={{ color: 'var(--color-text-disabled)' }}>点选节点以编辑</p>
        ) : (
          <NodeConfigEditor
            node={selectedNode}
            onChange={(patch) => updateNodeData(selectedNode.id, patch)}
          />
        )}
      </aside>

      {/* US-406 测试运行弹窗 */}
      {showSimulate && (
        <div role="dialog"
             style={{ position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.5)',
                      display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 100 }}>
          <div style={{ background: 'var(--color-text-primary)', borderRadius: 8, padding: 24, width: 480,
                        boxShadow: '0 10px 25px rgba(0,0,0,0.2)' }}>
            <h3 style={{ marginTop: 0 }}>▶ 测试运行工作流</h3>
            <p style={{ color: 'var(--color-text-disabled)', fontSize: 13 }}>
              输入模拟的 triggerData,系统将创建一个工作流实例并跳转到详情页。
              {!id && <strong style={{ color: 'var(--color-error)' }}>(会先自动保存当前编辑)</strong>}
            </p>
            <label style={{ display: 'block', fontWeight: 500, fontSize: 13, marginBottom: 4 }}>
              triggerData (JSON)
            </label>
            <textarea value={simulateData} onChange={(e) => setSimulateData(e.target.value)}
                      style={{ width: '100%', height: 160, fontFamily: 'monospace', fontSize: 12,
                               padding: 8, border: '1px solid var(--color-border-medium)', borderRadius: 4 }}
                      placeholder='{"record_id":"test-001","name":"测试"}' />
            {simulateResult?.instanceId && (
              <div style={{ marginTop: 12, padding: 8, background: 'rgba(16,185,129,0.2)', color: 'var(--color-success)',
                            borderRadius: 4, fontSize: 13 }}>
                ✅ 实例已创建: <code>{simulateResult.instanceId.slice(0, 8)}…</code>
                <div style={{ marginTop: 6 }}>
                  <a href={`/designer/instances/${simulateResult.instanceId}`}
                     onClick={(e) => { e.preventDefault(); navigate(`/designer/instances/${simulateResult.instanceId}`); setShowSimulate(false); }}
                     style={{ color: 'var(--color-info)', textDecoration: 'underline' }}>
                    查看实例详情 →
                  </a>
                </div>
              </div>
            )}
            {simulateResult?.error && (
              <div style={{ marginTop: 12, padding: 8, background: 'rgba(239,68,68,0.2)', color: 'var(--color-error)',
                            borderRadius: 4, fontSize: 13 }}>
                ❌ {simulateResult.error}
              </div>
            )}
            <div style={{ marginTop: 16, display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => setShowSimulate(false)}
                      style={{ padding: '6px 14px', background: 'var(--color-border-light)', color: 'var(--color-bg-secondary)',
                               border: 'none', borderRadius: 4, cursor: 'pointer' }}>
                关闭
              </button>
              <button onClick={() => simulateMutation.mutate()}
                      disabled={simulateMutation.isPending || saveMutation.isPending}
                      style={{ padding: '6px 14px', background: 'var(--color-info)', color: 'var(--color-text-primary)',
                               border: 'none', borderRadius: 4,
                               cursor: simulateMutation.isPending ? 'not-allowed' : 'pointer' }}>
                {simulateMutation.isPending ? '运行中…' : '▶ 运行'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function NodeConfigEditor({ node, onChange }: { node: Node<WorkflowNodeData>; onChange: (patch: Partial<WorkflowNodeData>) => void }) {
  const meta = nodeKindMeta[node.data.kind];
  const cfg = node.data.config;
  const setCfg = (k: string, v: string) => onChange({ config: { ...cfg, [k]: v } });
  return (
    <div>
      <div style={{ padding: 6, marginBottom: 8, background: meta.color, color: 'var(--color-text-primary)', borderRadius: 4 }}>
        {meta.icon} {meta.label}(id: {node.id})
      </div>
      {node.data.kind === 'NOTIFICATION' && (
        <>
          <label style={lbl}>标题</label>
          <input value={cfg.title || ''} onChange={(e) => setCfg('title', e.target.value)} style={inp} />
          <label style={lbl}>消息内容</label>
          <textarea value={cfg.message || ''} onChange={(e) => setCfg('message', e.target.value)} style={{ ...inp, height: 60 }} />
        </>
      )}
      {node.data.kind === 'HTTP' && (
        <>
          <label style={lbl}>Method</label>
          <select value={cfg.method || 'GET'} onChange={(e) => setCfg('method', e.target.value)} style={inp}>
            <option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option>
          </select>
          <label style={lbl}>URL</label>
          <input value={cfg.url || ''} onChange={(e) => setCfg('url', e.target.value)} style={inp} />
        </>
      )}
      {node.data.kind === 'CONDITION' && (
        <>
          <label style={lbl}>字段名(来自 triggerData)</label>
          <input value={cfg.field || ''} onChange={(e) => setCfg('field', e.target.value)} style={inp} />
          <label style={lbl}>操作符</label>
          <select value={cfg.op || 'eq'} onChange={(e) => setCfg('op', e.target.value)} style={inp}>
            <option value="eq">等于 eq</option>
            <option value="neq">不等于 neq</option>
            <option value="contains">包含 contains</option>
            <option value="gt">大于 gt</option>
            <option value="lt">小于 lt</option>
          </select>
          <label style={lbl}>比较值</label>
          <input value={cfg.value || ''} onChange={(e) => setCfg('value', e.target.value)} style={inp} />
        </>
      )}
      {node.data.kind === 'DATA_UPDATE' && (
        <>
          <label style={lbl}>Collection</label>
          <input value={cfg.collection || ''} onChange={(e) => setCfg('collection', e.target.value)} style={inp} />
          <label style={lbl}>recordId(留空=触发事件的 recordId)</label>
          <input value={cfg.recordId || ''} onChange={(e) => setCfg('recordId', e.target.value)} style={inp} />
          <label style={lbl}>data(JSON —— 要写入/合并的字段)</label>
          <textarea
            value={cfg.data || ''}
            onChange={(e) => setCfg('data', e.target.value)}
            style={{ ...inp, height: 80, fontFamily: 'monospace', fontSize: 12 }}
            placeholder='{"status": "approved"}'
          />
        </>
      )}
      {node.data.kind === 'APPROVAL' && (
        <p style={{ color: 'var(--color-text-disabled)', fontSize: 13 }}>(审批节点使用当前用户作为审批人,无配置项)</p>
      )}
    </div>
  );
}

function defaultConfig(kind: NodeKind): Record<string, string> {
  if (kind === 'NOTIFICATION') return { title: '通知', message: '' };
  if (kind === 'HTTP') return { method: 'GET', url: '' };
  if (kind === 'CONDITION') return { field: '', op: 'eq', value: '' };
  if (kind === 'DATA_UPDATE') return { collection: '', recordId: '', data: '' };
  return {};
}

const lbl = { display: 'block', fontSize: 12, color: 'var(--color-bg-elevated)', marginTop: 6 };
const inp = { padding: 4, width: '100%', border: '1px solid var(--color-border-medium)', borderRadius: 4, fontSize: 13 };

interface WorkflowMeta {
  id: string;
  name: string;
  title: string;
  description: string;
  collection_name: string;
  trigger_json: string;
  nodes_json: string;
  enabled: boolean;
  created_at: string;
}
interface WorkflowNodeDef {
  id: string;
  type: string;
  config?: Record<string, string>;
  position?: { x: number; y: number };
}

/** Provider 包装,React Flow 11 需要 */
export function WorkflowDesignerPageWithProvider() {
  return (
    <ReactFlowProvider>
      <WorkflowDesignerPage />
    </ReactFlowProvider>
  );
}
