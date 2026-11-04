import { useEffect, useState, type ReactNode } from 'react';
import apiClient from '@/api/client';

interface OrgNode {
  id: string;
  name: string;
  code?: string;
  parentId: string | null;
  source: string;
  editable: boolean;
  sort: number;
  children: OrgNode[];
}

/**
 * 组织结构管理（E1，管理员）。
 *
 * <p>外部来源（钉钉/企微）子树只读 —— 后端会返回 403，这里同时置灰按钮。
 */
export function OrgStructurePage() {
  const [tree, setTree] = useState<OrgNode[]>([]);
  const [selected, setSelected] = useState<OrgNode | null>(null);
  const [name, setName] = useState('');
  const [parentId, setParentId] = useState<string>('');
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    apiClient
      .get<OrgNode[]>('/org/tree')
      .then(r => setTree(r ?? []))
      .catch(e => setError(e?.message ?? '加载失败'));
  };

  useEffect(load, []);

  const create = async () => {
    setError(null);
    try {
      await apiClient.post('/org/units', {
        parentId: parentId || null,
        name,
      });
      setName('');
      setMsg('部门已创建');
      load();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? e?.message ?? '创建失败');
    }
  };

  const rename = async () => {
    if (!selected) return;
    setError(null);
    try {
      await apiClient.put(`/org/units/${selected.id}`, { name });
      setMsg('已保存');
      load();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? e?.message ?? '保存失败');
    }
  };

  const move = async () => {
    if (!selected) return;
    setError(null);
    try {
      await apiClient.put(`/org/units/${selected.id}`, { parentId: parentId || null });
      setMsg('已移动');
      load();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? e?.message ?? '移动失败');
    }
  };

  const remove = async () => {
    if (!selected) return;
    if (!window.confirm(`确认停用部门「${selected.name}」？`)) return;
    setError(null);
    try {
      await apiClient.delete(`/org/units/${selected.id}`);
      setMsg('部门已停用');
      setSelected(null);
      load();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? e?.message ?? '删除失败');
    }
  };

  const renderNode = (n: OrgNode, depth: number): ReactNode => (
    <div key={n.id}>
      <div
        role="button"
        tabIndex={0}
        onClick={() => {
          setSelected(n);
          setName(n.name);
          setParentId('');
        }}
        onKeyDown={e => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            setSelected(n);
            setName(n.name);
            setParentId('');
          }
        }}
        style={{
          padding: '6px 10px',
          paddingLeft: 10 + depth * 16,
          cursor: 'pointer',
          borderRadius: 6,
          background: selected?.id === n.id ? 'rgba(99,102,241,0.2)' : 'transparent',
        }}
      >
        {n.name}
        <span style={{ fontSize: 11, opacity: 0.6, marginLeft: 8 }}>{n.editable ? 'LOCAL' : n.source}</span>
      </div>
      {n.children.map(c => renderNode(c, depth + 1))}
    </div>
  );

  return (
    <div style={{ display: 'flex', gap: 24, padding: 20 }}>
      <section style={{ flex: 1, maxWidth: 520 }}>
        <h2 style={{ fontSize: 16 }}>组织结构</h2>
        <div style={{ border: '1px solid var(--color-border-light)', borderRadius: 8, padding: 8 }}>
          {tree.map(n => renderNode(n, 0))}
          {tree.length === 0 && <div style={{ opacity: 0.6, padding: 8 }}>暂无部门（可在钉钉/企微同步，或在此新建）</div>}
        </div>
      </section>

      <section style={{ width: 380 }}>
        <h3 style={{ fontSize: 14 }}>{selected ? `编辑：${selected.name}` : '新建部门'}</h3>
        {selected && (
          <div style={{ fontSize: 12, opacity: 0.7, marginBottom: 8 }}>
            来源 {selected.source}{selected.editable ? '（可本地编辑）' : '（外部同步，只读）'}
          </div>
        )}
        <label style={{ fontSize: 12 }} htmlFor="org-unit-name">名称</label>
        <input
          id="org-unit-name"
          value={name}
          onChange={e => setName(e.target.value)}
          disabled={selected ? !selected.editable : false}
          style={{ display: 'block', width: '100%', padding: '8px 10px', margin: '6px 0 12px', borderRadius: 6, border: '1px solid var(--color-border-light)' }}
        />
        <label style={{ fontSize: 12 }} htmlFor="org-parent-id">上级部门 ID（留空 = 根）</label>
        <input
          id="org-parent-id"
          value={parentId}
          onChange={e => setParentId(e.target.value)}
          placeholder="UUID"
          style={{ display: 'block', width: '100%', padding: '8px 10px', margin: '6px 0 12px', borderRadius: 6, border: '1px solid var(--color-border-light)' }}
        />
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {!selected && <button onClick={create}>新建</button>}
          {selected && (
            <>
              <button onClick={rename} disabled={!selected.editable}>改名</button>
              <button onClick={move} disabled={!selected.editable}>移动</button>
              <button onClick={remove} disabled={!selected.editable} style={{ color: 'var(--color-error)' }}>停用</button>
            </>
          )}
        </div>
        {msg && <div style={{ marginTop: 10, color: 'var(--color-success-500)' }}>{msg}</div>}
        {error && <div style={{ marginTop: 10, color: 'var(--color-error)' }}>{error}</div>}

        <p style={{ fontSize: 12, opacity: 0.6, marginTop: 24, lineHeight: 1.6 }}>
          外部平台同步的部门为只读镜像；人员调动请在源系统操作，或在「用户管理」中调整部门。
        </p>
      </section>
    </div>
  );
}
