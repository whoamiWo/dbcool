import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
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

/** US-410: 工作流版本条目 */
interface WorkflowVersionItem {
  version: number;
  node_count?: number;
  published_at: string;
  published_by?: string;
}

/** US-410: 版本列表响应体 */
interface WorkflowVersionList {
  workflow_id: string;
  current_version: number;
  versions: WorkflowVersionItem[];
}

interface WorkflowNodeData {
  kind: NodeKind;
  config: Record<string, string>;
  onChange: (patch: Partial<WorkflowNodeData>) => void;
  onDelete: () => void;
}

const nodeKindMeta: Record<NodeKind, { icon: string; color: string }> = {
  APPROVAL:     { icon: '📝', color: 'var(--color-info)' },
  NOTIFICATION: { icon: '🔔', color: 'var(--color-secondary-500)' },
  CONDITION:    { icon: '🔀', color: 'var(--color-warning)' },
  HTTP:         { icon: '🌐', color: 'var(--color-success)' },
  DATA_UPDATE:  { icon: '✏️', color: 'var(--color-info)' },
};

// i18n 节点标签
function getNodeLabel(t: (key: string) => string, kind: NodeKind): string {
  const map: Record<NodeKind, string> = {
    APPROVAL: t('workflowDesigner.nodeKindApproval'),
    NOTIFICATION: t('workflowDesigner.nodeKindNotification'),
    CONDITION: t('workflowDesigner.nodeKindCondition'),
    HTTP: t('workflowDesigner.nodeKindHttp'),
    DATA_UPDATE: t('workflowDesigner.nodeKindDataUpdate'),
  };
  return map[kind] ?? kind;
}

function FlowNode({ data, selected }: { data: WorkflowNodeData; selected: boolean }) {
  const { t } = useTranslation('workflowDesigner');
  const meta = nodeKindMeta[data.kind];
  const isCondition = data.kind === 'CONDITION';
  const label = getNodeLabel(t, data.kind);
  return (
    <div style={{
      padding: 10, minWidth: 160, position: 'relative',
      background: 'var(--color-text-primary)',
      border: `2px solid ${selected ? 'var(--color-bg-primary)' : meta.color}`,
      borderRadius: 8,
      boxShadow: '0 2px 6px var(--color-bg-primary)',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <strong>{meta.icon} {label}</strong>
        {/* label 已通过 getNodeLabel(t, data.kind) 国际化，nodeKindMeta.label 已移除 —— PHASE117 i18n 补全 */}
        <button
          onClick={(e) => { e.stopPropagation(); data.onDelete(); }}
          style={{ background: 'none', border: 'none', color: 'var(--color-error)', cursor: 'pointer', fontSize: 16 }}
        >×</button>
      </div>
      <div style={{ marginTop: 4, fontSize: 11, color: 'var(--color-text-disabled)' }}>
        {data.kind === 'NOTIFICATION' && (data.config.message?.slice(0, 40) || t('workflowDesigner.nodePreviewNotSet'))}
        {data.kind === 'HTTP' && `${data.config.method || 'GET'} ${(data.config.url || '').slice(0, 30)}`}
        {data.kind === 'CONDITION' && `if ${data.config.field || '?'} ${data.config.op || '?'} ${data.config.value || '?'}`}
        {data.kind === 'DATA_UPDATE' && `${data.config.collection || '?'} / ${data.config.recordId || '?'}`}
        {data.kind === 'APPROVAL' && t('workflowDesigner.nodePreviewApproval')}
      </div>
      {/* 左边:输入 handle(所有节点) */}
      <Handle type="target" position={Position.Left} style={{ background: meta.color, width: 10, height: 10 }} />
      {/* CONDITION: 底部两个 source handle(then/else) */}
      {isCondition ? (
        <>
          <Handle id="true" type="source" position={Position.Bottom}
                  style={{ left: '30%', background: 'var(--color-success)', width: 12, height: 12, border: '2px solid var(--color-text-primary)' }} />
          <span style={{ position: 'absolute', bottom: -18, left: '20%', fontSize: 10, color: 'var(--color-success)', fontWeight: 'bold' }}>
            {t('workflowDesigner.then')}
          </span>
          <Handle id="false" type="source" position={Position.Bottom}
                  style={{ left: '70%', background: 'var(--color-error)', width: 12, height: 12, border: '2px solid var(--color-text-primary)' }} />
          <span style={{ position: 'absolute', bottom: -18, right: '10%', fontSize: 10, color: 'var(--color-error)', fontWeight: 'bold' }}>
            {t('workflowDesigner.else')}
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
  const { t } = useTranslation();
  const { id } = useParams<{ id?: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const isEdit = !!id;

  const [name, setName] = useState('new_workflow');
  const [triggerType, setTriggerType] = useState('manual');
  // Wave4 #6a：webhook 触发 token（后端生成，前端只读展示）
  const [webhookToken, setWebhookToken] = useState('');
  const [title, setTitle] = useState(t('workflowDesigner.createMode'));
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
  // US-410: 版本管理
  const [showVersions, setShowVersions] = useState(false);
  const [rollbackTarget, setRollbackTarget] = useState<number | null>(null);
  const [versionNotice, setVersionNotice] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
  const reactFlowWrapper = useRef<HTMLDivElement>(null);
  const { project } = useReactFlow();

  // US-410: 版本列表(仅编辑态且有 id 时拉取)
  const { data: versionData } = useQuery({
    queryKey: ['workflow-versions', id],
    queryFn: async () => {
      const r = await apiClient.get<{ data: WorkflowVersionList }>(`/workflows/${id}/versions`);
      return (r as { data: WorkflowVersionList }).data;
    },
    enabled: isEdit && showVersions,
  });

  const publishMutation = useMutation({
    mutationFn: () => apiClient.post<{ data: { version: number } }>(`/workflows/${id}/versions/publish`, {}),
    onSuccess: (r) => {
      const v = (r as { data?: { version?: number } }).data?.version;
      setVersionNotice({ kind: 'ok', text: t('workflowDesigner.publishSuccess', { version: v }) });
      qc.invalidateQueries({ queryKey: ['workflow-versions', id] });
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setVersionNotice({ kind: 'err', text: e.response?.data?.message ?? t('workflowDesigner.publishFailed') });
    },
  });

  const rollbackMutation = useMutation({
    mutationFn: (version: number) =>
      apiClient.post<{ data: { rolled_back_to: number } }>(`/workflows/${id}/versions/${version}/rollback`, {}),
    onSuccess: (_r, version) => {
      setRollbackTarget(null);
      setVersionNotice({ kind: 'ok', text: t('workflowDesigner.rollbackSuccess', { version }) });
      qc.invalidateQueries({ queryKey: ['workflow-versions', id] });
      // 重新加载工作流定义(覆盖后的快照)
      qc.invalidateQueries({ queryKey: ['workflow', id] });
    },
    onError: (err: unknown) => {
      setRollbackTarget(null);
      const e = err as { response?: { data?: { message?: string } } };
      setVersionNotice({ kind: 'err', text: e.response?.data?.message ?? t('workflowDesigner.rollbackFailed') });
    },
  });

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

  // 以下三个工具函数必须定义在依赖它们的 useEffect 之前，否则 React Compiler 会因 TDZ 报 immutability
  const buildEdgesFromSequence = (ids: string[]): Edge[] => {
    const es: Edge[] = [];
    for (let i = 0; i < ids.length - 1; i++) {
      es.push({ id: `e${i}`, source: ids[i], target: ids[i + 1] });
    }
    return es;
  };

  const updateNodeData = (id: string, patch: Partial<WorkflowNodeData>) => {
    setNodes((nds) => nds.map((n) => n.id === id ? { ...n, data: { ...n.data, ...patch } } : n));
  };

  const deleteNode = (id: string) => {
    setNodes((nds) => nds.filter((n) => n.id !== id));
    setEdges((eds) => eds.filter((e) => e.source !== id && e.target !== id));
    if (selectedNodeId === id) setSelectedNodeId(null);
  };

   
  useEffect(() => {
    if (existing) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：异步/条件/初始化模式，非连续渲染
      setName(existing.name);
      setTitle(existing.title);
      setDescription(existing.description);
      setCollectionName(existing.collection_name);
      setEnabled(existing.enabled);
      try {
        const t = JSON.parse(existing.trigger_json || '{}');
        if (t.type) setTriggerType(t.type);
        if (t.token) setWebhookToken(t.token);
      } catch {}
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
      } catch {}
    }
  }, [existing]);

  // 给每个 node 的 data 绑定回调(避免闭包过期)
   
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：异步/条件/初始化模式，非连续渲染
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

  const onNodesChange = useCallback((changes: NodeChange[]) => {
    setNodes((nds) => applyNodeChanges(changes, nds) as Node<WorkflowNodeData>[]);
  }, []);
  const onEdgesChange = useCallback((changes: EdgeChange[]) => {
    setEdges((eds) => applyEdgeChanges(changes, eds));
  }, []);
  const onConnect = useCallback((conn: Connection) => {
    setEdges((eds) => addEdge({ ...conn, id: `e${Date.now()}` }, eds));
  }, []);

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
    mutationFn: async () => {
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
        trigger: JSON.stringify(triggerType === 'webhook' && webhookToken
          ? { type: triggerType, token: webhookToken }
          : { type: triggerType }),
        nodes: JSON.stringify(nodesOut),
        edges: JSON.stringify(edgesOut),
      };
      const resp = isEdit
        ? await apiClient.put<WorkflowMeta>(`/workflows/${id}`, body)
        : await apiClient.post<WorkflowMeta>('/workflows', body);
      // 后端对 webhook 触发器自动生成 token → 回读展示
      try {
        const t = JSON.parse(resp?.trigger_json || '{}');
        if (t.token) setWebhookToken(t.token);
      } catch {}
      return resp;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      navigate('/designer/workflows');
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? t('workflowDesigner.saveFailed'));
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
        throw new Error(t('workflowDesigner.triggerDataInvalid'));
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
        : (err as { response?: { data?: { message?: string } } }).response?.data?.message ?? t('workflowDesigner.runFailed');
      setSimulateResult({ error: msg });
    },
  });

  const selectedNode = nodes.find((n) => n.id === selectedNodeId);

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 100px)', gap: 12 }}>
      {/* 左:节点面板 + 元数据 */}
      <aside style={{ width: 200, background: 'var(--color-text-primary)', padding: 12, borderRadius: 8, boxShadow: '0 1px 3px var(--color-bg-primary)', overflowY: 'auto' }}>
        <button onClick={() => navigate('/designer/workflows')}
                style={{ background: 'none', border: 'none', color: 'var(--color-text-disabled)', cursor: 'pointer', marginBottom: 8 }}>
          {t('workflowDesigner.backToList')}
        </button>
        <h3 style={{ marginTop: 0 }}>{isEdit ? t('workflowDesigner.editMode') : t('workflowDesigner.createMode')} {t('workflowDesigner.title')}</h3>
        {error && <div style={{ padding: 4, marginBottom: 8, background: 'var(--color-error)', color: 'var(--color-error)', borderRadius: 4, fontSize: 11 }}>{error}</div>}
        <label style={lbl} htmlFor="w-lbl-1">{t('workflowDesigner.technicalName')}</label>
        <input id="w-lbl-1" value={name} onChange={(e) => setName(e.target.value)} style={inp} />
        <label style={lbl} htmlFor="w-lbl-2">{t('workflowDesigner.displayName')}</label>
        <input id="w-lbl-2" value={title} onChange={(e) => setTitle(e.target.value)} style={inp} />
        <label style={lbl} htmlFor="w-lbl-3">{t('workflowDesigner.collection')}</label>
        <input id="w-lbl-3" value={collectionName} onChange={(e) => setCollectionName(e.target.value)} style={inp} />
        <label style={lbl}>
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> {t('workflowDesigner.enabled')}
        </label>
        <label style={lbl} htmlFor="w-lbl-4">{t('workflowDesigner.trigger')}</label>
        <select id="w-lbl-4" value={triggerType} onChange={(e) => setTriggerType(e.target.value)} style={inp}>
          <option value="manual">{t('workflowDesigner.triggerManual')}</option>
          <option value="on_create">{t('workflowDesigner.triggerOnCreate')}</option>
          <option value="on_update">{t('workflowDesigner.triggerOnUpdate')}</option>
          <option value="schedule">{t('workflowDesigner.triggerSchedule')}</option>
          <option value="webhook">{t('workflowDesigner.triggerWebhook')}</option>
        </select>
        {triggerType === 'webhook' && (
          <div style={{ marginTop: 8, padding: 8, border: '1px dashed var(--color-border-light)', borderRadius: 6 }}>
            <label style={{ ...lbl, display: 'block' }} htmlFor="w-lbl-webhook-token">{t('workflowDesigner.webhookToken')}</label>
            <input id="w-lbl-webhook-token" readOnly value={webhookToken || '—'} style={{ ...inp, fontFamily: 'monospace' }} />
            <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginTop: 4 }}>
              {t('workflowDesigner.webhookTokenHint')}
            </div>
          </div>
        )}
        <h4 style={{ marginTop: 12 }}>{t('workflowDesigner.dragNodes')}</h4>
        {(Object.keys(nodeKindMeta) as NodeKind[]).map((k) => {
          const m = nodeKindMeta[k];
          return (
            // eslint-disable-next-line jsx-a11y/no-static-element-interactions -- 拖拽手柄用 HTML5 DnD，非点击交互
            <div key={k}
                 draggable
                 onDragStart={(e) => onDragStart(e, k)}
                 style={{ padding: 8, marginBottom: 6, background: m.color, color: 'var(--color-text-primary)',
                          borderRadius: 4, cursor: 'grab', fontSize: 13 }}>
              {m.icon} {getNodeLabel(t, k)}
            </div>
          );
        })}
        <button onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}
                style={{ marginTop: 12, width: '100%', padding: 8, background: saveMutation.isPending ? 'var(--color-text-muted)' : 'var(--color-success)',
                         color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
          {saveMutation.isPending ? t('workflowDesigner.saving') : t('workflowDesigner.save')}
        </button>
        {/* US-406: 测试运行 */}
        <button onClick={() => { setSimulateResult(null); setShowSimulate(true); }}
                style={{ marginTop: 8, width: '100%', padding: 8,
                         background: 'var(--color-info)', color: 'var(--color-text-primary)', border: 'none',
                         borderRadius: 4, cursor: 'pointer' }}>
          {t('workflowDesigner.testRun')}
        </button>
      </aside>

      {/* 中:画布 */}
      <div
        ref={reactFlowWrapper}
        role="presentation"
        aria-label={t('workflowDesigner.canvasAria')}
        style={{ flex: 1, background: 'var(--color-text-primary)', borderRadius: 8 }}
        onDrop={onDrop}
        onDragOver={onDragOver}
      >
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

      {/* 右:配置面板 + 版本侧栏 */}
      {showVersions ? (
        /* US-410: 版本侧栏 */
        <aside style={{ width: 280, background: 'var(--color-text-primary)', padding: 12, borderRadius: 8, boxShadow: '0 1px 3px var(--color-bg-primary)', overflowY: 'auto' }}>
          <button onClick={() => setShowVersions(false)}
                  style={{ background: 'none', border: 'none', color: 'var(--color-text-disabled)', cursor: 'pointer', marginBottom: 8 }}>
            ← {t('workflowDesigner.backToList')}
          </button>
          <h3 style={{ marginTop: 0 }}>{t('workflowDesigner.versions')}</h3>
          {versionNotice && (
            <div style={{ padding: 8, marginBottom: 12, borderRadius: 4, fontSize: 13,
                            background: versionNotice.kind === 'ok' ? 'rgba(16,185,129,0.2)' : 'rgba(239,68,68,0.2)',
                            color: versionNotice.kind === 'ok' ? 'var(--color-success)' : 'var(--color-error)' }}>
              {versionNotice.text}
            </div>
          )}
          {(!versionData?.versions?.length || versionData.versions.length === 0) ? (
            <p style={{ color: 'var(--color-text-disabled)' }}>{t('workflowDesigner.noVersions')}</p>
          ) : (
            <ul style={{ listStyle: 'none', padding: 0 }}>
              {versionData?.versions.map((v) => (
                <li key={v.version} style={{ padding: 8, marginBottom: 6, background: 'var(--color-bg-secondary)', borderRadius: 4 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontWeight: 500 }}>{t('workflowDesigner.versionLabel', { version: v.version })}</span>
                    {v.version === (versionData.current_version ?? 0) && (
                      <span style={{ fontSize: 11, color: 'var(--color-text-disabled)' }}>{t('workflowDesigner.currentVersion', { version: v.version })}</span>
                    )}
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--color-text-disabled)', marginTop: 4 }}>
                    {t('workflowDesigner.nodeCount', { count: v.node_count ?? 0 })} • {new Date(v.published_at).toLocaleString()}
                  </div>
                  {v.version < (versionData.current_version ?? 0) && (
                    <button onClick={() => setRollbackTarget(v.version)}
                            style={{ marginTop: 4, padding: '4px 8px', background: 'var(--color-error)',
                                     color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, fontSize: 11, cursor: 'pointer' }}>
                      {t('workflowDesigner.rollback')}
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
          <button onClick={() => {
            if (rollbackTarget !== null) {
              if (window.confirm(t('workflowDesigner.rollbackConfirmBody', { version: rollbackTarget }))) {
                rollbackMutation.mutate(rollbackTarget);
              }
            }
          }}
                  disabled={rollbackMutation.isPending || rollbackTarget === null}
                  style={{ marginTop: 12, width: '100%', padding: 8,
                           background: rollbackMutation.isPending ? 'var(--color-text-muted)' : 'var(--color-error)',
                           color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
            {rollbackMutation.isPending ? t('workflowDesigner.running') : t('workflowDesigner.confirmRollback')}
          </button>
          <button onClick={() => setShowVersions(false)}
                  style={{ marginTop: 8, width: '100%', padding: 8, background: 'var(--color-bg-secondary)',
                           color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
            {t('workflowDesigner.cancel')}
          </button>
        </aside>
      ) : (
        <aside style={{ width: 280, background: 'var(--color-text-primary)', padding: 12, borderRadius: 8, boxShadow: '0 1px 3px var(--color-bg-primary)', overflowY: 'auto' }}>
          <h3 style={{ marginTop: 0 }}>{t('workflowDesigner.nodeConfig')}</h3>

          {/* US-410: 发布版本 + 版本历史按钮（仅编辑态） */}
          {isEdit && id && (
            <div style={{ marginTop: 16, marginBottom: 12 }}>
              <button onClick={() => publishMutation.mutate()}
                      disabled={publishMutation.isPending}
                      style={{ width: '100%', padding: 8,
                               background: publishMutation.isPending ? 'var(--color-text-muted)' : 'var(--color-info)',
                               color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
                {publishMutation.isPending ? t('workflowDesigner.publishing') : t('workflowDesigner.publishVersion')}
              </button>
              <button onClick={() => setShowVersions(true)}
                      style={{ width: '100%', marginTop: 8, padding: 8, background: 'var(--color-bg-secondary)',
                               color: 'var(--color-text-primary)', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
                {t('workflowDesigner.versions')}
              </button>
            </div>
          )}

          {!selectedNode ? (
            <p style={{ color: 'var(--color-text-disabled)' }}>{t('workflowDesigner.selectNode')}</p>
          ) : (
            <NodeConfigEditor
              node={selectedNode}
              onChange={(patch) => updateNodeData(selectedNode.id, patch)}
              t={t}
            />
          )}
        </aside>
      )}

      {/* US-406 测试运行弹窗 */}
      {showSimulate && (
        <div role="dialog"
             style={{ position: 'fixed', inset: 0, background: 'var(--glass-bg-strong)',
                      display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 100 }}>
          <div style={{ background: 'var(--color-text-primary)', borderRadius: 8, padding: 24, width: 480,
                        boxShadow: '0 10px 25px var(--color-bg-primary)' }}>
            <h3 style={{ marginTop: 0 }}>{t('workflowDesigner.testRunTitle')}</h3>
            <p style={{ color: 'var(--color-text-disabled)', fontSize: 13 }}>
              {t('workflowDesigner.testRunHint')}
              {!id && <strong style={{ color: 'var(--color-error)' }}>({t('workflowDesigner.testRunAutoSave')})</strong>}
            </p>
            <label style={{ display: 'block', fontWeight: 500, fontSize: 13, marginBottom: 4 }} htmlFor="w-lbl-triggerdata">
              {t('workflowDesigner.triggerDataLabel')}
            </label>
            <textarea id="w-lbl-triggerdata" value={simulateData} onChange={(e) => setSimulateData(e.target.value)}
                      style={{ width: '100%', height: 160, fontFamily: 'monospace', fontSize: 12,
                               padding: 8, border: '1px solid var(--color-border-medium)', borderRadius: 4 }}
                      placeholder={t('workflowDesigner.triggerDataPlaceholder')} />
            {simulateResult?.instanceId && (
              <div style={{ marginTop: 12, padding: 8, background: 'rgba(var(--color-success-rgb, 16,185,129),0.2)', color: 'var(--color-success)',
                            borderRadius: 4, fontSize: 13 }}>
                ✅ {t('workflowDesigner.instanceCreated')}: <code>{simulateResult.instanceId.slice(0, 8)}…</code>
                <div style={{ marginTop: 6 }}>
                  <a href={`/designer/instances/${simulateResult.instanceId}`}
                     onClick={(e) => { e.preventDefault(); navigate(`/designer/instances/${simulateResult.instanceId}`); setShowSimulate(false); }}
                     style={{ color: 'var(--color-info)', textDecoration: 'underline' }}>
                    {t('workflowDesigner.viewInstance')}
                  </a>
                </div>
              </div>
            )}
            {simulateResult?.error && (
              <div style={{ marginTop: 12, padding: 8, background: 'var(--color-error)', color: 'var(--color-error)',
                            borderRadius: 4, fontSize: 13 }}>
                ❌ {simulateResult.error}
              </div>
            )}
            <div style={{ marginTop: 16, display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => setShowSimulate(false)}
                      style={{ padding: '6px 14px', background: 'var(--color-border-light)', color: 'var(--color-bg-secondary)',
                               border: 'none', borderRadius: 4, cursor: 'pointer' }}>
                {t('workflowDesigner.close')}
              </button>
              <button onClick={() => simulateMutation.mutate()}
                      disabled={simulateMutation.isPending || saveMutation.isPending}
                      style={{ padding: '6px 14px', background: 'var(--color-info)', color: 'var(--color-text-primary)',
                               border: 'none', borderRadius: 4,
                               cursor: simulateMutation.isPending ? 'not-allowed' : 'pointer' }}>
                {simulateMutation.isPending ? t('workflowDesigner.running') : t('workflowDesigner.run')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function NodeConfigEditor({ node, onChange, t }: { node: Node<WorkflowNodeData>; onChange: (patch: Partial<WorkflowNodeData>) => void; t: (key: string) => string }) {
  const meta = nodeKindMeta[node.data.kind];
  const cfg = node.data.config;
  const setCfg = (k: string, v: string) => onChange({ config: { ...cfg, [k]: v } });
  const nodeLabel = getNodeLabel(t, node.data.kind);
  return (
    <div>
      <div style={{ padding: 6, marginBottom: 8, background: meta.color, color: 'var(--color-text-primary)', borderRadius: 4 }}>
        {meta.icon} {nodeLabel}(id: {node.id})
      </div>
      {node.data.kind === 'NOTIFICATION' && (
        <>
          <label style={lbl} htmlFor="w-lbl-6">{t('workflowDesigner.notificationTitle')}</label>
          <input id="w-lbl-6" value={cfg.title || ''} onChange={(e) => setCfg('title', e.target.value)} style={inp} />
          <label style={lbl} htmlFor="w-lbl-7">{t('workflowDesigner.notificationMessage')}</label>
          <textarea id="w-lbl-7" value={cfg.message || ''} onChange={(e) => setCfg('message', e.target.value)} style={{ ...inp, height: 60 }} />
        </>
      )}
      {node.data.kind === 'HTTP' && (
        <>
          <label style={lbl} htmlFor="w-lbl-8">{t('workflowDesigner.httpMethod')}</label>
          <select id="w-lbl-8" value={cfg.method || 'GET'} onChange={(e) => setCfg('method', e.target.value)} style={inp}>
            <option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option>
          </select>
          <label style={lbl} htmlFor="w-lbl-9">{t('workflowDesigner.httpUrl')}</label>
          <input id="w-lbl-9" value={cfg.url || ''} onChange={(e) => setCfg('url', e.target.value)} style={inp} />
          {/* PHASE132: US-409 HTTP 节点鉴权配置面板 */}
          <label style={lbl} htmlFor="w-lbl-auth">{t('workflowDesigner.httpAuth')}</label>
          <select id="w-lbl-auth" value={cfg.authType || 'none'} onChange={(e) => setCfg('authType', e.target.value)} style={inp}>
            <option value="none">{t('workflowDesigner.httpAuthNone')}</option>
            <option value="bearer">{t('workflowDesigner.httpAuthBearer')}</option>
            <option value="basic">{t('workflowDesigner.httpAuthBasic')}</option>
          </select>
          {cfg.authType === 'bearer' && (
            <>
              <label style={lbl} htmlFor="w-lbl-token">{t('workflowDesigner.httpAuthToken')}</label>
              <input id="w-lbl-token" type="password" value={cfg.authToken || ''} onChange={(e) => setCfg('authToken', e.target.value)} style={inp} placeholder="sk-..." />
            </>
          )}
          {cfg.authType === 'basic' && (
            <>
              <label style={lbl} htmlFor="w-lbl-user">{t('workflowDesigner.httpAuthUsername')}</label>
              <input id="w-lbl-user" value={cfg.authUsername || ''} onChange={(e) => setCfg('authUsername', e.target.value)} style={inp} />
              <label style={lbl} htmlFor="w-lbl-pass">{t('workflowDesigner.httpAuthPassword')}</label>
              <input id="w-lbl-pass" type="password" value={cfg.authPassword || ''} onChange={(e) => setCfg('authPassword', e.target.value)} style={inp} />
            </>
          )}
        </>
      )}
      {node.data.kind === 'CONDITION' && (
        <>
          <label style={lbl} htmlFor="w-lbl-10">{t('workflowDesigner.conditionField')}</label>
          <input id="w-lbl-10" value={cfg.field || ''} onChange={(e) => setCfg('field', e.target.value)} style={inp} />
          <label style={lbl} htmlFor="w-lbl-11">{t('workflowDesigner.conditionOp')}</label>
          <select id="w-lbl-11" value={cfg.op || 'eq'} onChange={(e) => setCfg('op', e.target.value)} style={inp}>
            <option value="eq">{t('workflowDesigner.opEq')}</option>
            <option value="neq">{t('workflowDesigner.opNeq')}</option>
            <option value="contains">{t('workflowDesigner.opContains')}</option>
            <option value="gt">{t('workflowDesigner.opGt')}</option>
            <option value="lt">{t('workflowDesigner.opLt')}</option>
          </select>
          <label style={lbl} htmlFor="w-lbl-12">{t('workflowDesigner.conditionValue')}</label>
          <input id="w-lbl-12" value={cfg.value || ''} onChange={(e) => setCfg('value', e.target.value)} style={inp} />
        </>
      )}
      {node.data.kind === 'DATA_UPDATE' && (
        <>
          <label style={lbl} htmlFor="w-lbl-13">{t('workflowDesigner.dataUpdateCollection')}</label>
          <input id="w-lbl-13" value={cfg.collection || ''} onChange={(e) => setCfg('collection', e.target.value)} style={inp} />
          <label style={lbl} htmlFor="w-lbl-14">{t('workflowDesigner.dataUpdateRecordId')}</label>
          <input id="w-lbl-14" value={cfg.recordId || ''} onChange={(e) => setCfg('recordId', e.target.value)} style={inp} />
          <label style={lbl} htmlFor="w-lbl-15">{t('workflowDesigner.dataUpdateData')}</label>
          <textarea id="w-lbl-15"
            value={cfg.data || ''}
            onChange={(e) => setCfg('data', e.target.value)}
            style={{ ...inp, height: 80, fontFamily: 'monospace', fontSize: 12 }}
            placeholder='{"status": "approved"}'
          />
        </>
      )}
      {node.data.kind === 'APPROVAL' && (
        <p style={{ color: 'var(--color-text-disabled)', fontSize: 13 }}>{t('workflowDesigner.approvalHint')}</p>
      )}
    </div>
  );
}

function defaultConfig(kind: NodeKind): Record<string, string> {
  if (kind === 'NOTIFICATION') return { title: 'Notification', message: '' };
  if (kind === 'HTTP') return { method: 'GET', url: '', authType: 'none', authToken: '', authUsername: '', authPassword: '' };
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
