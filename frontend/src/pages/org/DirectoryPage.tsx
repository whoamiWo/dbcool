import { useEffect, useMemo, useState } from 'react';
import apiClient from '@/api/client';

interface OrgNode {
  id: string;
  name: string;
  code?: string;
  parentId: string | null;
  source: string;
  editable: boolean;
  children: OrgNode[];
}

interface Member {
  id: string;
  username: string;
  display_name: string;
  email?: string;
  phone?: string;
  position?: string;
  employee_no?: string;
  status?: string;
  department_id?: string;
  avatar_url?: string;
}

function flatten(nodes: OrgNode[], out: OrgNode[] = []): OrgNode[] {
  for (const n of nodes) {
    out.push(n);
    flatten(n.children, out);
  }
  return out;
}

/**
 * 通讯录（E1）：部门树 + 成员卡片。
 *
 * <p>手机号由后端按角色（ADMIN/HR）决定是否下发，前端不做"隐藏"式伪权限。
 */
export function DirectoryPage() {
  const [tree, setTree] = useState<OrgNode[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [members, setMembers] = useState<Member[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiClient
      .get<OrgNode[]>('/org/tree')
      .then(r => setTree(r ?? []))
      .catch(e => setError(e?.message ?? '加载组织架构失败'));
  }, []);

  useEffect(() => {
    const p = new URLSearchParams();
    if (selected) p.set('unitId', selected);
    if (keyword) p.set('keyword', keyword);
    p.set('includeDescendants', 'true');
    apiClient
      .get<Member[]>(`/org/members?${p.toString()}`)
      .then(r => setMembers(r ?? []))
      .catch(e => setError(e?.message ?? '加载成员失败'));
  }, [selected, keyword]);

  const flat = useMemo(() => flatten(tree), [tree]);

  return (
    <div style={{ display: 'flex', gap: 20, padding: 20, minHeight: '70vh' }}>
      <aside style={{ width: 260, flexShrink: 0 }}>
        <h2 style={{ fontSize: 16, marginBottom: 12 }}>通讯录</h2>
        <button
          onClick={() => setSelected(null)}
          style={{
            width: '100%', textAlign: 'left', padding: '6px 10px', marginBottom: 8,
            background: selected === null ? 'rgba(99,102,241,0.2)' : 'transparent',
            border: '1px solid var(--color-border-light)', borderRadius: 6, cursor: 'pointer',
          }}
        >
          全部成员
        </button>
        {flat.map(n => (
          <button
            key={n.id}
            onClick={() => setSelected(n.id)}
            style={{
              width: '100%', textAlign: 'left', padding: '6px 10px', marginBottom: 4,
              paddingLeft: 10 + Math.max(0, depthOf(tree, n.id)) * 14,
              background: selected === n.id ? 'rgba(99,102,241,0.2)' : 'transparent',
              border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: 13,
              color: 'var(--color-text-primary)',
            }}
          >
            {n.name}
            {!n.editable && <span style={{ fontSize: 10, opacity: 0.6 }}> · {n.source}</span>}
          </button>
        ))}
      </aside>

      <section style={{ flex: 1 }}>
        <input
          value={keyword}
          onChange={e => setKeyword(e.target.value)}
          placeholder="搜索姓名 / 工号 / 邮箱"
          style={{ width: '100%', maxWidth: 360, padding: '8px 12px', marginBottom: 16, borderRadius: 6, border: '1px solid var(--color-border-light)' }}
        />
        {error && <div style={{ color: 'var(--color-error)', marginBottom: 12 }}>{error}</div>}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 12 }}>
          {members.map(m => (
            <div key={m.id} style={{ border: '1px solid var(--color-border-light)', borderRadius: 8, padding: 14 }}>
              <div style={{ fontWeight: 600 }}>{m.display_name || m.username}</div>
              <div style={{ fontSize: 12, opacity: 0.75, marginTop: 4 }}>{m.position || '—'}</div>
              {m.employee_no && <div style={{ fontSize: 12, opacity: 0.6 }}>工号 {m.employee_no}</div>}
              {m.email && <div style={{ fontSize: 12, opacity: 0.6 }}>{m.email}</div>}
              {/* 后端未授权时 phone 字段根本不下发 */}
              {m.phone && <div style={{ fontSize: 12, opacity: 0.6 }}>{m.phone}</div>}
              {m.status && m.status !== 'ACTIVE' && (
                <div style={{ fontSize: 11, color: 'var(--color-warning-500)', marginTop: 4 }}>{m.status}</div>
              )}
            </div>
          ))}
          {members.length === 0 && <div style={{ opacity: 0.6 }}>没有匹配的成员</div>}
        </div>
      </section>
    </div>
  );
}

function depthOf(nodes: OrgNode[], id: string, depth = 0): number {
  for (const n of nodes) {
    if (n.id === id) return depth;
    const d = depthOf(n.children, id, depth + 1);
    if (d >= 0) return d;
  }
  return -1;
}
