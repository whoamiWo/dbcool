import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';

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

interface NodeDef {
  id: string;
  type: string;
  config?: Record<string, unknown>;
}

const NODE_TYPES = [
  { value: 'APPROVAL', label: '📝 审批(单人)' },
  { value: 'NOTIFICATION', label: '🔔 通知' },
  { value: 'CONDITION', label: '🔀 条件分支' },
  { value: 'HTTP', label: '🌐 HTTP 调用' },
];

/**
 * 工作流设计器(Week 11 MVP)— 节点列表编辑(暂不画流程图).
 * 触发 + 节点类型都通过 JSON 编辑.
 */
export function WorkflowDesignerPage() {
  const { id } = useParams<{ id?: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const isEdit = !!id;

  const [name, setName] = useState('new_workflow');
  const [title, setTitle] = useState('新工作流');
  const [description, setDescription] = useState('');
  const [collectionName, setCollectionName] = useState('customer');
  const [triggerType, setTriggerType] = useState('manual');
  const [enabled, setEnabled] = useState(true);
  const [nodes, setNodes] = useState<NodeDef[]>([{ id: 'n1', type: 'APPROVAL' }]);
  const [error, setError] = useState<string | null>(null);

  const { data: existing } = useQuery({
    queryKey: ['workflow', id],
    queryFn: () => apiClient.get<WorkflowMeta>(`/workflows/${id}`),
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
        const trig = JSON.parse(existing.trigger_json);
        if (trig.type) setTriggerType(trig.type);
        const ns = JSON.parse(existing.nodes_json);
        if (Array.isArray(ns)) setNodes(ns);
      } catch (e) {}
    }
  }, [existing]);

  const saveMutation = useMutation({
    mutationFn: () => {
      const body = {
        name, title, description, collectionName, enabled,
        trigger: JSON.stringify({ type: triggerType }),
        nodes: JSON.stringify(nodes),
      };
      return isEdit
        ? apiClient.put<WorkflowMeta>(`/workflows/${id}`, body)
        : apiClient.post<WorkflowMeta>('/workflows', body);
    },
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['workflows'] });
      navigate(`/workflows/${res.id}/run`);
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '保存失败');
    },
  });

  const addNode = () => {
    const next = `n${nodes.length + 1}`;
    setNodes([...nodes, { id: next, type: 'NOTIFICATION' }]);
  };

  const removeNode = (i: number) => {
    setNodes(nodes.filter((_, idx) => idx !== i));
  };

  const moveNode = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    if (j < 0 || j >= nodes.length) return;
    const next = [...nodes];
    [next[i], next[j]] = [next[j], next[i]];
    setNodes(next);
  };

  const updateNode = (i: number, patch: Partial<NodeDef>) => {
    setNodes(nodes.map((n, idx) => (idx === i ? { ...n, ...patch } : n)));
  };

  const updateConfig = (i: number, key: string, value: string) => {
    setNodes(nodes.map((n, idx) => {
      if (idx !== i) return n;
      const cfg = { ...(n.config ?? {}), [key]: value };
      return { ...n, config: cfg };
    }));
  };

  return (
    <div style={{ maxWidth: 800 }}>
      <button
        onClick={() => navigate('/designer/workflows')}
        style={{ background: 'none', border: 'none', color: '#64748b', cursor: 'pointer', marginBottom: 16 }}
      >
        ← 返回列表
      </button>
      <h1>{isEdit ? '编辑' : '新建'}工作流</h1>

      {error && (
        <div style={{ padding: 8, marginBottom: 12, background: '#fee2e2', color: '#991b1b', borderRadius: 4 }}>{error}</div>
      )}

      <div style={{ padding: 16, background: 'white', borderRadius: 8, marginBottom: 16, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
        <div style={{ marginBottom: 8 }}>
          <label>技术名 *</label>
          <input value={name} onChange={(e) => setName(e.target.value)} style={inputStyle} />
        </div>
        <div style={{ marginBottom: 8 }}>
          <label>显示标题</label>
          <input value={title} onChange={(e) => setTitle(e.target.value)} style={inputStyle} />
        </div>
        <div style={{ marginBottom: 8 }}>
          <label>Collection</label>
          <input value={collectionName} onChange={(e) => setCollectionName(e.target.value)} style={inputStyle} />
        </div>
        <div style={{ marginBottom: 8 }}>
          <label>触发器</label>
          <select value={triggerType} onChange={(e) => setTriggerType(e.target.value)} style={inputStyle}>
            <option value="manual">manual(手动)</option>
          </select>
        </div>
        <div style={{ marginBottom: 8 }}>
          <label>
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> 启用
          </label>
        </div>
      </div>

      <h3>节点列表({nodes.length})</h3>
      {nodes.map((n, i) => (
        <div key={i} style={{ ...cardStyle, marginBottom: 8 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <span style={{ width: 30, color: '#64748b' }}>{i + 1}.</span>
            <input value={n.id} onChange={(e) => updateNode(i, { id: e.target.value })} style={{ ...inputStyle, width: 100, fontFamily: 'monospace' }} />
            <select value={n.type} onChange={(e) => updateNode(i, { type: e.target.value })} style={{ ...inputStyle, width: 180 }}>
              {NODE_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
            <button onClick={() => moveNode(i, -1)} disabled={i === 0} style={btnSm}>↑</button>
            <button onClick={() => moveNode(i, 1)} disabled={i === nodes.length - 1} style={btnSm}>↓</button>
            <button onClick={() => removeNode(i)} style={{ ...btnSm, background: '#dc2626' }}>删</button>
          </div>
          <NodeConfigEditor node={n} onChange={(k, v) => updateConfig(i, k, v)} />
        </div>
      ))}
      <button onClick={addNode} style={{ ...btnStyle, background: '#475569' }}>+ 添加节点</button>

      <button
        onClick={() => saveMutation.mutate()}
        disabled={saveMutation.isPending}
        style={{ ...btnStyle, marginTop: 16, background: saveMutation.isPending ? '#94a3b8' : '#16a34a' }}
      >
        {saveMutation.isPending ? '保存中…' : '保存并触发'}
      </button>
    </div>
  );
}

function NodeConfigEditor({ node, onChange }: { node: NodeDef; onChange: (k: string, v: string) => void }) {
  if (node.type === 'NOTIFICATION') {
    return (
      <div style={{ marginTop: 8, paddingLeft: 38 }}>
        <input
          placeholder="通知内容"
          defaultValue={(node.config?.message as string) ?? ''}
          onBlur={(e) => onChange('message', e.target.value)}
          style={{ ...inputStyle, fontSize: 12 }}
        />
      </div>
    );
  }
  if (node.type === 'CONDITION') {
    return (
      <div style={{ marginTop: 8, paddingLeft: 38, fontSize: 12, color: '#64748b' }}>
        JSON config(在 triggerData 上判断):
        <code style={{ marginLeft: 8 }}>
          {`{when: {field, op, value}, then: <id>, else: <id>}`}
        </code>
      </div>
    );
  }
  if (node.type === 'HTTP') {
    return (
      <div style={{ marginTop: 8, paddingLeft: 38, display: 'flex', gap: 4 }}>
        <input placeholder="GET/POST" defaultValue={(node.config?.method as string) ?? 'GET'} onBlur={(e) => onChange('method', e.target.value)} style={{ ...inputStyle, width: 70, fontSize: 12 }} />
        <input placeholder="https://..." defaultValue={(node.config?.url as string) ?? ''} onBlur={(e) => onChange('url', e.target.value)} style={{ ...inputStyle, flex: 1, fontSize: 12 }} />
      </div>
    );
  }
  return null;
}

const inputStyle = { padding: 6, width: '100%', border: '1px solid #cbd5e1', borderRadius: 4 };
const cardStyle = { padding: 12, background: 'white', borderRadius: 8, boxShadow: '0 1px 2px rgba(0,0,0,0.05)' };
const btnStyle = { padding: '8px 16px', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' };
const btnSm = { padding: '2px 8px', background: '#1e293b', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 12 };
