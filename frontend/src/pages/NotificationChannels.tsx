import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

type ChannelType = 'EMAIL' | 'WEBHOOK' | 'DINGTALK' | 'WECHAT_WORK';

interface Channel {
  id: string;
  type: ChannelType;
  name: string;
  config: Record<string, unknown>;
  events: string;
  enabled: boolean;
  description: string;
  created_at: string;
  updated_at: string;
}

interface TypeSchema {
  type: ChannelType;
  label: string;
  config_schema: Record<string, unknown>;
}

const TYPE_COLORS: Record<ChannelType, string> = {
  EMAIL: '#3b82f6',
  WEBHOOK: '#10b981',
  DINGTALK: '#0ea5e9',
  WECHAT_WORK: '#22c55e',
};

export function NotificationChannelsPage() {
  const nav = useNavigate();
  const [channels, setChannels] = useState<Channel[]>([]);
  const [types, setTypes] = useState<TypeSchema[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Partial<Channel> | null>(null);
  const [testResult, setTestResult] = useState<{ ok: boolean; detail: string } | null>(null);

  const token = localStorage.getItem('access_token') || '';
  const authHeaders = { Authorization: `Bearer ${token}` };

  async function loadChannels() {
    setLoading(true); setError(null);
    try {
      const r = await fetch('/api/admin/notification-channels', { headers: authHeaders });
      if (r.status === 401) { nav('/login'); return; }
      const d = await r.json();
      setChannels(d.data || []);
    } catch (e: any) { setError(e.message); }
    finally { setLoading(false); }
  }

  async function loadTypes() {
    try {
      const r = await fetch('/api/admin/notification-channels/types', { headers: authHeaders });
      if (r.ok) setTypes((await r.json()).data || []);
    } catch { /* ignore */ }
  }

  useEffect(() => { loadTypes(); loadChannels(); }, []);

  async function save() {
    if (!editing) return;
    const method = editing.id ? 'PUT' : 'POST';
    const url = editing.id
      ? `/api/admin/notification-channels/${editing.id}`
      : '/api/admin/notification-channels';
    try {
      const r = await fetch(url, {
        method,
        headers: { ...authHeaders, 'Content-Type': 'application/json' },
        body: JSON.stringify(editing),
      });
      if (!r.ok) { setError(await r.text()); return; }
      setEditing(null);
      await loadChannels();
    } catch (e: any) { setError(e.message); }
  }

  async function remove(id: string) {
    if (!confirm('确定删除这个通知 channel?')) return;
    await fetch(`/api/admin/notification-channels/${id}`, { method: 'DELETE', headers: authHeaders });
    loadChannels();
  }

  async function toggle(c: Channel) {
    await fetch(`/api/admin/notification-channels/${c.id}`, {
      method: 'PUT',
      headers: { ...authHeaders, 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...c, enabled: !c.enabled }),
    });
    loadChannels();
  }

  async function testChannel(c: Channel) {
    setTestResult(null);
    const recipient = prompt('测试收件人(邮箱 / webhook URL / 钉钉 webhook URL):', '');
    if (recipient === null) return;
    try {
      const r = await fetch(`/api/admin/notification-channels/${c.id}/test`, {
        method: 'POST',
        headers: { ...authHeaders, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: `[测试] NocoBase ${c.type} 通知`,
          body: '这是一条测试通知,触发 channel 测试按钮。',
          recipient,
        }),
      });
      const d = await r.json();
      setTestResult({ ok: !!d.data?.success, detail: d.data?.detail || d.message || 'unknown' });
    } catch (e: any) { setTestResult({ ok: false, detail: e.message }); }
  }

  function configString(c: Channel) {
    const entries = Object.entries(c.config || {});
    if (entries.length === 0) return <span style={{ color: '#9ca3af' }}>(无配置)</span>;
    return entries.slice(0, 3).map(([k, v]) => (
      <div key={k} style={{ fontSize: 12, color: '#374151' }}>
        <code style={{ color: '#6b7280' }}>{k}</code> = <code>{String(v).slice(0, 40)}{String(v).length > 40 ? '…' : ''}</code>
      </div>
    ));
  }

  return (
    <div style={{ padding: 24, maxWidth: 1200 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>🔔 通知渠道</h2>
        <button
          onClick={() => setEditing({
            type: 'WEBHOOK',
            name: '',
            config: { url: '', method: 'POST', timeout_seconds: 5 },
            events: '',
            enabled: true,
            description: '',
          })}
          style={{ background: '#3b82f6', color: 'white', border: 'none', padding: '8px 16px', borderRadius: 6, cursor: 'pointer' }}
        >+ 新建 channel</button>
      </div>

      <div style={{ marginBottom: 12, color: '#666', fontSize: 13 }}>
        多渠道通知配置。Workflow 触发通知节点时,会 fan-out 到所有启用且 events 匹配的 channel。
        空 <code>events</code> = 全部事件。
      </div>

      <div style={{ marginBottom: 16, display: 'flex', gap: 12, alignItems: 'center' }}>
        <span style={{ color: '#666', fontSize: 13 }}>{loading ? '加载中…' : `${channels.length} 条`}</span>
        {error && <span style={{ color: '#ef4444', fontSize: 13 }}>{error}</span>}
        {testResult && (
          <span style={{
            color: testResult.ok ? '#10b981' : '#ef4444',
            fontSize: 13,
            background: testResult.ok ? '#d1fae5' : '#fee2e2',
            padding: '4px 10px', borderRadius: 4,
          }}>
            {testResult.ok ? '✓' : '✗'} {testResult.detail}
          </span>
        )}
      </div>

      <table style={{ width: '100%', borderCollapse: 'collapse', background: 'white', boxShadow: '0 1px 3px rgba(0,0,0,0.05)' }}>
        <thead style={{ background: '#f3f4f6' }}>
          <tr>
            <th style={th}>类型</th>
            <th style={th}>名称</th>
            <th style={th}>配置</th>
            <th style={th}>Events</th>
            <th style={th}>启用</th>
            <th style={th}>说明</th>
            <th style={th}>操作</th>
          </tr>
        </thead>
        <tbody>
          {channels.map((c) => (
            <tr key={c.id} style={{ borderTop: '1px solid #e5e7eb' }}>
              <td style={td}>
                <span style={{
                  background: TYPE_COLORS[c.type] || '#6b7280',
                  color: 'white', padding: '2px 8px',
                  borderRadius: 10, fontSize: 12, fontWeight: 600,
                }}>{c.type}</span>
              </td>
              <td style={td}><strong>{c.name}</strong></td>
              <td style={td}>{configString(c)}</td>
              <td style={td}><code style={{ fontSize: 12, color: '#6b7280' }}>{c.events || '(全部)'}</code></td>
              <td style={td}>
                <button
                  onClick={() => toggle(c)}
                  style={{
                    background: c.enabled ? '#10b981' : '#9ca3af',
                    color: 'white', border: 'none', borderRadius: 12,
                    padding: '2px 10px', cursor: 'pointer', fontSize: 12,
                  }}
                >{c.enabled ? '✓ 启用' : '✗ 禁用'}</button>
              </td>
              <td style={{ ...td, maxWidth: 200, color: '#6b7280', fontSize: 13 }}>{c.description}</td>
              <td style={td}>
                <button onClick={() => testChannel(c)} style={btnTest}>📨 测试</button>
                <button onClick={() => setEditing(c)} style={btnEdit}>编辑</button>
                <button onClick={() => remove(c.id)} style={btnDelete}>删除</button>
              </td>
            </tr>
          ))}
          {!loading && channels.length === 0 && (
            <tr><td colSpan={7} style={{ ...td, textAlign: 'center', color: '#9ca3af' }}>
              暂无通知 channel — 点击「+ 新建 channel」开始
            </td></tr>
          )}
        </tbody>
      </table>

      {editing && (
        <div style={modalOverlay} onClick={() => setEditing(null)}>
          <div style={modalCard} onClick={(e) => e.stopPropagation()}>
            <h3>{editing.id ? '编辑 channel' : '新建 channel'}</h3>

            <Field label="类型">
              <select
                value={editing.type}
                onChange={(e) => {
                  const t = e.target.value as ChannelType;
                  const schema = types.find((s) => s.type === t);
                  setEditing({ ...editing, type: t, config: schema ? { ...schema.config_schema } : {} });
                }}
                style={input}
              >
                {types.map((t) => <option key={t.type} value={t.type}>{t.label}({t.type})</option>)}
              </select>
            </Field>

            <Field label="名称">
              <input value={editing.name || ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} style={input} />
            </Field>

            {editing.type === 'EMAIL' && (
              <div style={{ padding: 8, background: '#f8fafc', borderRadius: 4, marginTop: 8 }}>
                <strong style={{ fontSize: 12 }}>SMTP 配置</strong>
                {[
                  { k: 'host', label: 'SMTP 主机' },
                  { k: 'port', label: '端口', type: 'number' as const },
                  { k: 'username', label: '用户名' },
                  { k: 'password', label: '密码', type: 'password' as const },
                  { k: 'encryption', label: '加密', type: 'select' as const, options: ['none', 'starttls', 'ssl'] },
                  { k: 'from', label: '发件人' },
                ].map(({ k, label, type, options }) => (
                  <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
                    <span style={{ fontSize: 11, width: 64, color: '#64748b' }}>{label}</span>
                    {type === 'select' ? (
                      <select
                        value={(editing.config?.[k] as string) ?? ''}
                        onChange={(e) => setEditing({ ...editing, config: { ...editing.config, [k]: e.target.value } })}
                        style={input}
                      >
                        {options.map((o) => <option key={o} value={o}>{o}</option>)}
                      </select>
                    ) : (
                      <input
                        type={type}
                        value={(editing.config?.[k] as string) ?? ''}
                        onChange={(e) => setEditing({ ...editing, config: { ...editing.config, [k]: e.target.value } })}
                        style={input}
                      />
                    )}
                  </div>
                ))}
              </div>
            )}
            <Field label={`Config(JSON / ${editing.type})`}>
              <textarea
                value={JSON.stringify(editing.config || {}, null, 2)}
                onChange={(e) => {
                  try { setEditing({ ...editing, config: JSON.parse(e.target.value) }); }
                  catch { /* ignore parse error mid-typing */ }
                }}
                style={{ ...input, height: 140, fontFamily: 'monospace', fontSize: 12 }}
              />
            </Field>

            <Field label="Events(逗号分隔,空 = 全部)">
              <input
                value={editing.events || ''}
                onChange={(e) => setEditing({ ...editing, events: e.target.value })}
                placeholder="workflow.notification,workflow.approve"
                style={input}
              />
            </Field>

            <Field label="启用">
              <input type="checkbox" checked={editing.enabled ?? true} onChange={(e) => setEditing({ ...editing, enabled: e.target.checked })} />
            </Field>

            <Field label="说明">
              <input value={editing.description || ''} onChange={(e) => setEditing({ ...editing, description: e.target.value })} style={input} />
            </Field>

            <div style={{ marginTop: 16, display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => setEditing(null)} style={btnCancel}>取消</button>
              <button onClick={save} style={btnSave}>保存</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: 'block', marginBottom: 12 }}>
      <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 4 }}>{label}</div>
      {children}
    </label>
  );
}

const th: React.CSSProperties = { padding: '8px 12px', textAlign: 'left', fontSize: 13, fontWeight: 600 };
const td: React.CSSProperties = { padding: '8px 12px', fontSize: 13, verticalAlign: 'top' };
const input: React.CSSProperties = { width: '100%', padding: '6px 10px', border: '1px solid #d1d5db', borderRadius: 4 };
const btnTest: React.CSSProperties = { background: '#dbeafe', border: 'none', padding: '4px 10px', borderRadius: 4, cursor: 'pointer', marginRight: 4 };
const btnEdit: React.CSSProperties = { background: '#e0e7ff', border: 'none', padding: '4px 10px', borderRadius: 4, cursor: 'pointer', marginRight: 4 };
const btnDelete: React.CSSProperties = { background: '#fee2e2', border: 'none', padding: '4px 10px', borderRadius: 4, cursor: 'pointer' };
const btnCancel: React.CSSProperties = { background: '#f3f4f6', border: '1px solid #d1d5db', padding: '6px 14px', borderRadius: 4, cursor: 'pointer' };
const btnSave: React.CSSProperties = { background: '#3b82f6', color: 'white', border: 'none', padding: '6px 14px', borderRadius: 4, cursor: 'pointer' };
const modalOverlay: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 };
const modalCard: React.CSSProperties = { background: 'white', padding: 24, borderRadius: 8, width: 600, maxHeight: '90vh', overflow: 'auto' };
