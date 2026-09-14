import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import apiClient from '@/api/client';

interface AuditEntry {
  id: string;
  user_id: string;
  username: string | null;
  action: string;
  resource: string;
  resource_id: string | null;
  payload_json: string | null;
  ip: string | null;
  created_at: string;
}

const ACTION_COLOR: Record<string, string> = {
  CREATE: '#10b981',
  UPDATE: '#3b82f6',
  DELETE: '#ef4444',
  ADD_FIELD: '#06b6d4',
  DROP_FIELD: '#f97316',
  TRIGGER: '#8b5cf6',
  APPROVE: '#10b981',
  REJECT: '#ef4444',
};

const cardStyle: React.CSSProperties = {
  background: 'white', padding: 16, borderRadius: 8,
  boxShadow: '0 1px 3px rgba(0,0,0,0.1)', marginBottom: 16,
};

const headerCard: React.CSSProperties = {
  background: 'linear-gradient(135deg, #1f2937, #374151)',
  color: '#fff', padding: 20, borderRadius: 8, marginBottom: 16,
};

const actionTag = (action: string): React.CSSProperties => ({
  display: 'inline-block',
  padding: '2px 8px',
  borderRadius: 3,
  fontSize: 11,
  fontWeight: 600,
  color: 'white',
  background: ACTION_COLOR[action] || '#6b7280',
});

export function AuditLogsPage() {
  const [logs, setLogs] = useState<AuditEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [actionFilter, setActionFilter] = useState('');
  const [resourceFilter, setResourceFilter] = useState('');
  const [limit, setLimit] = useState(100);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  const fetch = useCallback(async () => {
    setLoading(true);
    try {
      const params: Record<string, any> = { limit };
      if (actionFilter) params.action = actionFilter;
      if (resourceFilter) params.resource = resourceFilter;
      const r = await apiClient.get('/audit/logs', { params });
      // axios 响应拦截器已解包(r.data 是后端业务 data),后端 {code, message, data}
      // 所以 r.code / r.data.logs,不是 r.data.code / r.data.data
      if ((r as any).code === 0) {
        setLogs((r as any).data.logs);
        setTotal((r as any).data.total);
      }
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  }, [actionFilter, resourceFilter, limit]);

  useEffect(() => { fetch(); }, [fetch]);

  const byAction = logs.reduce<Record<string, number>>((acc, l) => {
    acc[l.action] = (acc[l.action] || 0) + 1;
    return acc;
  }, {});

  const actions = Object.keys(ACTION_COLOR);

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <div style={headerCard}>
        <h1 style={{ color: '#fff', margin: 0 }}>🔍 审计日志</h1>
        <p style={{ color: '#d1d5db', marginTop: 8, marginBottom: 0 }}>
          租户内共 <strong style={{ color: '#fbbf24' }}>{total}</strong> 条操作记录 — 当前展示 <strong>{logs.length}</strong> 条
        </p>
      </div>

      <div style={cardStyle}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
          <span style={{ color: '#64748b', fontSize: 13 }}>Action:</span>
          <select value={actionFilter} onChange={e => setActionFilter(e.target.value)}
            style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid #d1d5db' }}>
            <option value="">全部</option>
            {actions.map(a => <option key={a} value={a}>{a}</option>)}
          </select>
          <span style={{ color: '#64748b', fontSize: 13 }}>Resource:</span>
          <input
            placeholder="例: audit_test"
            value={resourceFilter}
            onChange={e => setResourceFilter(e.target.value)}
            style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid #d1d5db', minWidth: 180 }}
          />
          <span style={{ color: '#64748b', fontSize: 13 }}>Limit:</span>
          <select value={limit} onChange={e => setLimit(Number(e.target.value))}
            style={{ padding: '4px 8px', borderRadius: 4, border: '1px solid #d1d5db' }}>
            {[50, 100, 200, 500].map(n => <option key={n} value={n}>{n}</option>)}
          </select>
          <button onClick={fetch}
            style={{
              padding: '4px 16px', borderRadius: 4, border: 'none',
              background: '#3b82f6', color: 'white', cursor: 'pointer',
            }}>🔄 刷新</button>
        </div>
      </div>

      {Object.keys(byAction).length > 0 && (
        <div style={cardStyle}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {Object.entries(byAction).map(([act, cnt]) => (
              <span key={act} style={actionTag(act)}>{act}: {cnt}</span>
            ))}
          </div>
        </div>
      )}

      <div style={cardStyle}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ borderBottom: '2px solid #e5e7eb', textAlign: 'left', color: '#64748b' }}>
              <th style={{ padding: 8 }}>时间</th>
              <th style={{ padding: 8 }}>Action</th>
              <th style={{ padding: 8 }}>Resource</th>
              <th style={{ padding: 8 }}>用户</th>
              <th style={{ padding: 8 }}>IP</th>
              <th style={{ padding: 8 }}>Detail</th>
              <th style={{ padding: 8, width: 40 }}></th>
            </tr>
          </thead>
          <tbody>
            {loading && <tr><td colSpan={7} style={{ padding: 24, textAlign: 'center', color: '#9ca3af' }}>加载中…</td></tr>}
            {!loading && logs.length === 0 && (
              <tr><td colSpan={7} style={{ padding: 24, textAlign: 'center', color: '#9ca3af' }}>暂无审计记录</td></tr>
            )}
            {logs.map(l => (
              <>
                <tr key={l.id} style={{ borderBottom: '1px solid #f1f5f9' }}>
                  <td style={{ padding: 8, fontFamily: 'monospace', fontSize: 12, color: '#64748b' }}>
                    {l.created_at.replace('T', ' ').slice(0, 19)}
                  </td>
                  <td style={{ padding: 8 }}>
                    <span style={actionTag(l.action)}>{l.action}</span>
                  </td>
                  <td style={{ padding: 8 }}>
                    <code style={{ background: '#f3f4f6', padding: '2px 6px', borderRadius: 3 }}>
                      {l.resource}
                      {l.resource_id && <span style={{ color: '#9ca3af', marginLeft: 4 }}>#{l.resource_id.slice(0, 8)}</span>}
                    </code>
                  </td>
                  <td style={{ padding: 8 }}>
                    <div style={{ fontSize: 12 }}>
                      <strong>{l.username || 'anonymous'}</strong>
                    </div>
                    <code style={{ fontSize: 10, color: '#9ca3af' }}>{l.user_id.slice(0, 8)}</code>
                  </td>
                  <td style={{ padding: 8, fontFamily: 'monospace', fontSize: 11, color: '#64748b' }}>
                    {l.ip || '—'}
                  </td>
                  <td style={{ padding: 8, fontSize: 11, color: '#6b7280', maxWidth: 240 }}>
                    {l.payload_json ? (() => {
                      try {
                        const p = JSON.parse(l.payload_json);
                        const ks = Object.keys(p).slice(0, 3);
                        return ks.map(k => `${k}: ${JSON.stringify(p[k]).slice(0, 40)}`).join(' | ');
                      } catch { return l.payload_json.slice(0, 60); }
                    })() : <span style={{ color: '#9ca3af' }}>—</span>}
                  </td>
                  <td style={{ padding: 8 }}>
                    {l.payload_json && (
                      <button
                        onClick={() => setExpanded(p => ({ ...p, [l.id]: !p[l.id] }))}
                        style={{
                          padding: '2px 8px', borderRadius: 3,
                          background: expanded[l.id] ? '#ef4444' : '#e5e7eb',
                          color: expanded[l.id] ? 'white' : '#374151',
                          border: 'none', cursor: 'pointer', fontSize: 11,
                        }}>{expanded[l.id] ? '收起' : '展开'}</button>
                    )}
                  </td>
                </tr>
                {expanded[l.id] && (
                  <tr key={l.id + '-expanded'} style={{ background: '#f9fafb' }}>
                    <td colSpan={7} style={{ padding: 12 }}>
                      <pre style={{
                        background: '#1f2937', color: '#d1d5db', padding: 12, borderRadius: 4,
                        fontSize: 11, overflow: 'auto', maxHeight: 200, margin: 0,
                      }}>
                        {l.payload_json ? JSON.stringify(JSON.parse(l.payload_json), null, 2) : '(无 payload)'}
                      </pre>
                    </td>
                  </tr>
                )}
              </>
            ))}
          </tbody>
        </table>
      </div>

      <div style={{ color: '#9ca3af', fontSize: 12, textAlign: 'center', marginTop: 16 }}>
        数据保留策略待定 — 当前每次查询最新 <strong>{limit}</strong> 条
      </div>
    </div>
  );
}
