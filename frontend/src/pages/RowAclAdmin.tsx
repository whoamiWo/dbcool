import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

type PrincipalType = 'user' | 'role';
type Action = 'read' | 'create' | 'update' | 'delete';

interface Policy {
  id: string;
  collection: string;
  principal_type: PrincipalType;
  principal_id: string;
  action: Action;
  expression: { field?: string; op?: string; value?: unknown };
  priority: number;
  enabled: boolean;
  description: string;
  created_at: string;
  updated_at: string;
}

interface Collection {
  name: string;
  title?: string;
}

const OPS = ['eq', 'neq', 'in', 'is_null', 'not_null', 'contains'];
const ACTIONS: Action[] = ['read', 'create', 'update', 'delete'];
const PRINCIPAL_TYPES: PrincipalType[] = ['user', 'role'];

export function RowAclAdminPage() {
  const nav = useNavigate();
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [collections, setCollections] = useState<Collection[]>([]);
  const [filterCollection, setFilterCollection] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Partial<Policy> | null>(null);

  const token = localStorage.getItem('access_token') || '';
  const authHeaders = { Authorization: `Bearer ${token}` };

  async function loadPolicies() {
    setLoading(true);
    setError(null);
    try {
      const url = filterCollection
        ? `/api/admin/row-acl/by-collection/${filterCollection}`
        : '/api/admin/row-acl';
      const r = await fetch(url, { headers: authHeaders });
      if (r.status === 401) { nav('/login'); return; }
      const data = await r.json();
      setPolicies(data.data || []);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  async function loadCollections() {
    try {
      const r = await fetch('/api/collections', { headers: authHeaders });
      if (r.ok) {
        const data = await r.json();
        setCollections(data.data || []);
      }
    } catch { /* ignore */ }
  }

  useEffect(() => {
    loadCollections();
    loadPolicies();
  }, []);

  useEffect(() => {
    loadPolicies();
  }, [filterCollection]);

  async function save() {
    if (!editing) return;
    const method = editing.id ? 'PUT' : 'POST';
    const url = editing.id
      ? `/api/admin/row-acl/${editing.id}`
      : '/api/admin/row-acl';
    try {
      const r = await fetch(url, {
        method,
        headers: { ...authHeaders, 'Content-Type': 'application/json' },
        body: JSON.stringify(editing),
      });
      if (!r.ok) {
        const t = await r.text();
        setError(t);
        return;
      }
      setEditing(null);
      await loadPolicies();
    } catch (e: any) {
      setError(e.message);
    }
  }

  async function remove(id: string) {
    if (!confirm('确定删除这条策略?')) return;
    try {
      await fetch(`/api/admin/row-acl/${id}`, {
        method: 'DELETE', headers: authHeaders,
      });
      await loadPolicies();
    } catch (e: any) {
      setError(e.message);
    }
  }

  async function toggleEnabled(p: Policy) {
    try {
      await fetch(`/api/admin/row-acl/${p.id}`, {
        method: 'PUT',
        headers: { ...authHeaders, 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...p, enabled: !p.enabled }),
      });
      await loadPolicies();
    } catch (e: any) {
      setError(e.message);
    }
  }

  return (
    <div style={{ padding: 24, maxWidth: 1200 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>🛡️ 行级 ACL 管理(ROW-level)</h2>
        <button
          onClick={() => setEditing({
            collection: collections[0]?.name || '',
            principal_type: 'role',
            principal_id: 'user',
            action: 'read',
            expression: { field: 'owner_id', op: 'eq', value: '$currentUser' },
            priority: 0,
            enabled: true,
            description: '',
          })}
          style={{
            background: 'var(--color-info)', color: 'var(--color-text-primary)', border: 'none',
            padding: '8px 16px', borderRadius: 6, cursor: 'pointer',
          }}
        >+ 新建策略</button>
      </div>

      <div style={{ marginBottom: 12, color: 'var(--color-text-muted)', fontSize: 13 }}>
        基于表达式的单条记录级访问控制。OR 语义:任一适用策略命中 → 通过;
        无适用策略 → 放行。<code>value</code> 支持 <code>$currentUser</code> / <code>$currentRoles</code> 占位符。
      </div>

      <div style={{ marginBottom: 16, display: 'flex', gap: 12, alignItems: 'center' }}>
        <label>按 collection 过滤:</label>
        <select
          value={filterCollection}
          onChange={(e) => setFilterCollection(e.target.value)}
          style={{ padding: '6px 10px', borderRadius: 4, border: '1px solid var(--color-border-medium)' }}
        >
          <option value="">(全部)</option>
          {collections.map((c) => (
            <option key={c.name} value={c.name}>{c.name}{c.title ? ` — ${c.title}` : ''}</option>
          ))}
        </select>
        <span style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>{loading ? '加载中…' : `${policies.length} 条`}</span>
        {error && <span style={{ color: 'var(--color-error)', fontSize: 13 }}>{error}</span>}
      </div>

      <table style={{ width: '100%', borderCollapse: 'collapse', background: 'var(--color-text-primary)', boxShadow: '0 1px 3px rgba(0,0,0,0.05)' }}>
        <thead style={{ background: 'var(--color-bg-tertiary)' }}>
          <tr>
            <th style={th}>Collection</th>
            <th style={th}>Principal</th>
            <th style={th}>Action</th>
            <th style={th}>Expression</th>
            <th style={th}>P</th>
            <th style={th}>启用</th>
            <th style={th}>说明</th>
            <th style={th}>操作</th>
          </tr>
        </thead>
        <tbody>
          {policies.map((p) => (
            <tr key={p.id} style={{ borderTop: '1px solid var(--color-border-medium)' }}>
              <td style={tdMono}>{p.collection}</td>
              <td style={td}>
                <span style={badge(p.principal_type === 'role' ? 'var(--color-secondary-500)' : 'var(--color-info)')}>
                  {p.principal_type}
                </span>
                <code style={{ marginLeft: 6 }}>{p.principal_id}</code>
              </td>
              <td style={td}>
                <span style={badge(actionColor(p.action))}>{p.action}</span>
              </td>
              <td style={tdMono}>
                <code>{p.expression.field}</code>{' '}
                <span style={{ color: 'var(--color-text-muted)' }}>{p.expression.op}</span>{' '}
                <code>{String(p.expression.value)}</code>
              </td>
              <td style={td}>{p.priority}</td>
              <td style={td}>
                <button
                  onClick={() => toggleEnabled(p)}
                  style={{
                    background: p.enabled ? 'var(--color-success)' : 'var(--color-text-muted)',
                    color: 'var(--color-text-primary)', border: 'none', borderRadius: 12,
                    padding: '2px 10px', cursor: 'pointer', fontSize: 12,
                  }}
                >{p.enabled ? '✓ 启用' : '✗ 禁用'}</button>
              </td>
              <td style={{ ...td, maxWidth: 200, color: 'var(--color-text-muted)', fontSize: 13 }}>
                {p.description}
              </td>
              <td style={td}>
                <button onClick={() => setEditing(p)} style={btnEdit}>编辑</button>
                <button onClick={() => remove(p.id)} style={btnDelete}>删除</button>
              </td>
            </tr>
          ))}
          {!loading && policies.length === 0 && (
            <tr><td colSpan={8} style={{ ...td, textAlign: 'center', color: 'var(--color-text-muted)' }}>
              暂无策略 — 点击「+ 新建策略」开始
            </td></tr>
          )}
        </tbody>
      </table>

      {editing && (
        <div style={modalOverlay} onClick={() => setEditing(null)}>
          <div style={modalCard} onClick={(e) => e.stopPropagation()}>
            <h3>{editing.id ? '编辑策略' : '新建策略'}</h3>
            <Field label="Collection">
              <select value={editing.collection || ''} onChange={(e) => setEditing({ ...editing, collection: e.target.value })} style={input}>
                {collections.map((c) => <option key={c.name} value={c.name}>{c.name}</option>)}
              </select>
            </Field>
            <div style={{ display: 'flex', gap: 12 }}>
              <Field label="Principal 类型">
                <select value={editing.principal_type} onChange={(e) => setEditing({ ...editing, principal_type: e.target.value as PrincipalType })} style={input}>
                  {PRINCIPAL_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </Field>
              <Field label="Principal ID">
                <input
                  value={editing.principal_id || ''}
                  onChange={(e) => setEditing({ ...editing, principal_id: e.target.value })}
                  placeholder={editing.principal_type === 'user' ? 'user-uuid' : 'role name (如 user/admin)'}
                  style={input}
                />
              </Field>
              <Field label="Action">
                <select value={editing.action} onChange={(e) => setEditing({ ...editing, action: e.target.value as Action })} style={input}>
                  {ACTIONS.map((a) => <option key={a} value={a}>{a}</option>)}
                </select>
              </Field>
            </div>
            <Field label="Expression.field">
              <input
                value={editing.expression?.field || ''}
                onChange={(e) => setEditing({ ...editing, expression: { ...editing.expression!, field: e.target.value } })}
                placeholder="owner_id"
                style={input}
              />
            </Field>
            <div style={{ display: 'flex', gap: 12 }}>
              <Field label="op">
                <select
                  value={editing.expression?.op || 'eq'}
                  onChange={(e) => setEditing({ ...editing, expression: { ...editing.expression!, op: e.target.value } })}
                  style={input}
                >
                  {OPS.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              </Field>
              <Field label="value($currentUser / $currentRoles / 字面值)">
                <input
                  value={String(editing.expression?.value ?? '')}
                  onChange={(e) => setEditing({ ...editing, expression: { ...editing.expression!, value: e.target.value } })}
                  style={input}
                />
              </Field>
            </div>
            <div style={{ display: 'flex', gap: 12 }}>
              <Field label="Priority">
                <input type="number" value={editing.priority || 0} onChange={(e) => setEditing({ ...editing, priority: parseInt(e.target.value) || 0 })} style={input} />
              </Field>
              <Field label="启用">
                <input type="checkbox" checked={editing.enabled ?? true} onChange={(e) => setEditing({ ...editing, enabled: e.target.checked })} />
              </Field>
            </div>
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
    <label style={{ display: 'block', marginBottom: 12, flex: 1 }}>
      <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginBottom: 4 }}>{label}</div>
      {children}
    </label>
  );
}

const th: React.CSSProperties = { padding: '8px 12px', textAlign: 'left', fontSize: 13, fontWeight: 600 };
const td: React.CSSProperties = { padding: '8px 12px', fontSize: 13 };
const tdMono: React.CSSProperties = { ...td, fontFamily: 'monospace' };
const input: React.CSSProperties = {
  width: '100%', padding: '6px 10px', border: '1px solid var(--color-border-medium)', borderRadius: 4,
};
const btnEdit: React.CSSProperties = { background: 'rgba(99,102,241,0.2)', border: 'none', padding: '4px 10px', borderRadius: 4, cursor: 'pointer', marginRight: 4 };
const btnDelete: React.CSSProperties = { background: 'rgba(239,68,68,0.2)', border: 'none', padding: '4px 10px', borderRadius: 4, cursor: 'pointer' };
const btnCancel: React.CSSProperties = { background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border-medium)', padding: '6px 14px', borderRadius: 4, cursor: 'pointer' };
const btnSave: React.CSSProperties = { background: 'var(--color-info)', color: 'var(--color-text-primary)', border: 'none', padding: '6px 14px', borderRadius: 4, cursor: 'pointer' };
const modalOverlay: React.CSSProperties = {
  position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100,
};
const modalCard: React.CSSProperties = {
  background: 'var(--color-text-primary)', padding: 24, borderRadius: 8,
  width: 560, maxHeight: '90vh', overflow: 'auto',
};

function badge(color: string): React.CSSProperties {
  return { background: color, color: 'var(--color-text-primary)', padding: '2px 8px', borderRadius: 10, fontSize: 12, fontWeight: 600 };
}
function actionColor(a: string) {
  return { read: 'var(--color-info)', create: 'var(--color-success)', update: 'var(--color-warning)', delete: 'var(--color-error)' }[a] || 'var(--color-text-muted)';
}
