import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import apiClient from '@/api/client';

interface WorkflowMeta {
  id: string;
  name: string;
  title: string;
  description: string;
  collection_name: string;
  enabled: boolean;
  created_at: string;
}

export function WorkflowsListPage() {
  const { data, isLoading } = useQuery({
    queryKey: ['workflows', 'all'],
    queryFn: async () => {
      // 后端 envelope: {code, message, data: WorkflowMeta[]}
      const r = await apiClient.get<{ code: number; data: WorkflowMeta[] }>('/workflows');
      // 兼容 vitest mock 直接返数组 + 后端 envelope: r 是数组 OR {code, data: [...]} 
      if (Array.isArray(r)) return r;  // vitest 模式
      return r.data ?? [];  // 真后端 envelope
    },
  });
  if (isLoading) return <p>加载中…</p>;;
  const workflows = data ?? [];
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h1>⚡ 工作流({workflows.length})</h1>
        <Link to="/designer/workflows/new" style={{ padding: '8px 16px', background: '#1e293b', color: 'white', textDecoration: 'none', borderRadius: 4 }}>+ 新建工作流</Link>
      </div>
      {workflows.length === 0 ? (
        <p style={{ color: '#64748b' }}>暂无工作流</p>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse', background: 'white', borderRadius: 8, overflow: 'hidden', marginTop: 16 }}>
          <thead><tr style={{ background: '#f1f5f9' }}>
            <th style={{ padding: 12, textAlign: 'left' }}>名称</th>
            <th style={{ padding: 12, textAlign: 'left' }}>标题</th>
            <th style={{ padding: 12, textAlign: 'left' }}>Collection</th>
            <th style={{ padding: 12, textAlign: 'left' }}>状态</th>
            <th style={{ padding: 12, textAlign: 'right' }}>操作</th>
          </tr></thead>
          <tbody>
            {workflows.map((w) => (
              <tr key={w.id} style={{ borderTop: '1px solid #e2e8f0' }}>
                <td style={{ padding: 12, fontFamily: 'monospace' }}>{w.name}</td>
                <td style={{ padding: 12 }}>{w.title}</td>
                <td style={{ padding: 12, fontFamily: 'monospace' }}>{w.collection_name}</td>
                <td style={{ padding: 12 }}>
                  {w.enabled ? <span style={{ padding: '2px 8px', background: '#dcfce7', color: '#166534', borderRadius: 4, fontSize: 12 }}>启用</span>
                            : <span style={{ padding: '2px 8px', background: '#fee2e2', color: '#991b1b', borderRadius: 4, fontSize: 12 }}>禁用</span>}
                </td>
                <td style={{ padding: 12, textAlign: 'right' }}>
                  <Link to={`/designer/workflows/${w.id}/edit`} style={{ marginRight: 12, color: '#2563eb', textDecoration: 'none' }}>编辑</Link>
                  <Link to={`/api/workflows/${w.id}/trigger`} style={{ color: '#16a34a', textDecoration: 'none' }}>触发</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
