import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

interface DataSource {
  id: string;
  name: string;
  vendor: string;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  status: string;
  lastSyncedAt: string;
  syncMode?: string;
  syncEnabled?: boolean;
  syncCron?: string;
  incrementalKey?: string;
  lastSyncCursor?: string;
  createdAt: string;
}

/** 编辑态：包含仅写入侧的 password 和 driverClassName（后端 toView 不返回密码字段） */
type DataSourceDraft = Partial<DataSource> & { password?: string; driverClassName?: string };

export function DataSourcesPage() {
  const nav = useNavigate();
  const [sources, setSources] = useState<DataSource[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<DataSourceDraft | null>(null);
  const [syncing, setSyncing] = useState<string | null>(null);
  const [syncResult, setSyncResult] = useState<{ id: string; ok: boolean; msg: string } | null>(null);

  const token = localStorage.getItem('access_token') || '';
  const authHeaders = { Authorization: `Bearer ${token}` };

  async function loadSources() {
    setLoading(true);
    setError(null);
    try {
      const r = await fetch('/api/datasources', { headers: authHeaders });
      if (r.status === 401) {
        nav('/login');
        return;
      }
      const d = await r.json();
      setSources(d.data || []);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadSources().catch(err => console.error('load datasources failed:', err));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function testConnection(id: string) {
    const r = await fetch(`/api/datasources/${id}/test`, {
      method: 'POST',
      headers: authHeaders,
    });
    const d = await r.json();
    alert(d.data?.connected ? '连接成功' : '连接失败');
  }

  async function syncNow(id: string) {
    setSyncing(id);
    setSyncResult(null);
    try {
      const r = await fetch(`/api/datasources/${id}/sync`, {
        method: 'POST',
        headers: authHeaders,
      });
      const d = await r.json();
      setSyncResult({ id, ok: d.data?.synced ?? false, msg: d.message || (d.data?.synced ? '同步已触发' : '同步失败') });
    } catch (e: any) {
      setSyncResult({ id, ok: false, msg: e.message });
    } finally {
      setSyncing(null);
    }
  }

  async function saveSyncConfig() {
    if (!editing?.id) return;
    const body = {
      syncEnabled: editing.syncEnabled ?? false,
      syncMode: editing.syncMode || 'FULL',
      syncCron: editing.syncCron || '',
      incrementalKey: editing.incrementalKey || '',
    };
    try {
      const r = await fetch(`/api/datasources/${editing.id}/sync-config`, {
        method: 'POST',
        headers: { ...authHeaders, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!r.ok) {
        setError(await r.text());
        return;
      }
      setEditing(null);
      await loadSources();
    } catch (e: any) {
      setError(e.message);
    }
  }

  async function createDataSource() {
    if (!editing) return;
    const body = {
      name: editing.name,
      vendor: editing.vendor || 'mysql',
      host: editing.host || '',
      port: editing.port ?? 3306,
      databaseName: editing.databaseName || '',
      username: editing.username || '',
      password: editing.password || '',
      driverClassName: editing.driverClassName || undefined,
      syncEnabled: editing.syncEnabled ?? false,
      syncMode: editing.syncMode || 'FULL',
      syncCron: editing.syncCron || '',
      incrementalKey: editing.incrementalKey || '',
    };
    try {
      const r = await fetch('/api/datasources', {
        method: 'POST',
        headers: { ...authHeaders, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!r.ok) {
        setError(await r.text());
        return;
      }
      setEditing(null);
      await loadSources();
    } catch (e: any) {
      setError(e.message);
    }
  }

  async function remove(id: string) {
    if (!confirm('确定要删除该数据源吗？')) return;
    await fetch(`/api/datasources/${id}`, { method: 'DELETE', headers: authHeaders });
    await loadSources();
  }

  function openEdit(source: DataSource) {
    setEditing({
      ...source,
      syncEnabled: source.syncEnabled ?? false,
      syncMode: source.syncMode || 'FULL',
      syncCron: source.syncCron || '',
      incrementalKey: source.incrementalKey || '',
    });
  }

  return (
    <div style={{ padding: 24, maxWidth: 1400 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>外部数据源管理</h2>
        <button
          onClick={() =>
            setEditing({
              vendor: 'mysql',
              host: '',
              port: 3306,
              databaseName: '',
              username: '',
              password: '',
              syncEnabled: false,
              syncMode: 'FULL',
              syncCron: '',
              incrementalKey: '',
            })
          }
          style={{ background: 'var(--color-info)', color: 'var(--color-text-primary)', border: 'none', padding: '8px 16px', borderRadius: 6, cursor: 'pointer' }}
        >新建数据源</button>
      </div>

      <div style={{ marginBottom: 12, color: 'var(--color-text-muted)', fontSize: 13 }}>
        配置外部数据库连接，支持同步到虚拟 Collection。启用同步后可设置定时任务或增量字段。
      </div>

      <div style={{ marginBottom: 16, display: 'flex', gap: 12, alignItems: 'center' }}>
        <span style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>{loading ? '加载中...' : `共 ${sources.length} 个数据源`}</span>
        {error && <span style={{ color: 'var(--color-error)', fontSize: 13 }}>{error}</span>}
        {syncResult && (
          <span style={{
            color: syncResult.ok ? 'var(--color-success)' : 'var(--color-error)',
            fontSize: 13,
            background: syncResult.ok ? 'rgba(var(--color-success-rgb, 16,185,129),0.2)' : 'var(--color-error)',
            padding: '4px 10px', borderRadius: 4,
          }}>
            {syncResult.ok ? '✓' : '✗'} {syncResult.msg}
          </span>
        )}
      </div>

      {/* Wave4 待办池 #11：表格横向滚动容器（移动端窄屏不裁剪） */}
      <div className="page-responsive">
      <table style={{ width: '100%', borderCollapse: 'collapse', background: 'var(--color-text-primary)', boxShadow: '0 1px 3px var(--color-bg-primary)' }}>
        <thead style={{ background: 'var(--color-bg-tertiary)' }}>
          <tr>
            <th style={th}>名称</th>
            <th style={th}>类型</th>
            <th style={th}>连接信息</th>
            <th style={th}>状态</th>
            <th style={th}>同步配置</th>
            <th style={th}>最后同步</th>
            <th style={th}>操作</th>
          </tr>
        </thead>
        <tbody>
          {sources.map((s) => (
            <tr key={s.id} style={{ borderTop: '1px solid var(--color-border-medium)' }}>
              <td style={td}><strong>{s.name}</strong></td>
              <td style={td}>
                <span style={{
                  background: 'var(--color-info)',
                  color: 'var(--color-text-primary)', padding: '2px 8px',
                  borderRadius: 10, fontSize: 12, fontWeight: 600,
                }}>{s.vendor.toUpperCase()}</span>
              </td>
              <td style={td}>
                <div style={{ fontSize: 12 }}>
                  <div>{s.host}:{s.port}/{s.databaseName}</div>
                  <div style={{ color: 'var(--color-text-muted)' }}>用户：{s.username}</div>
                </div>
              </td>
              <td style={td}>
                <span style={{
                  color: s.status === 'CONNECTED' ? 'var(--color-success)' : 'var(--color-error)',
                  fontWeight: 600,
                }}>{s.status || 'UNKNOWN'}</span>
              </td>
              <td style={td}>
                <div style={{ fontSize: 12 }}>
                  <div>{s.syncEnabled ? '✓ 已启用' : '✗ 未启用'}</div>
                  {s.syncMode && <div style={{ color: 'var(--color-text-muted)' }}>模式：{s.syncMode}</div>}
                  {s.syncCron && <div style={{ color: 'var(--color-text-muted)' }}>cron: {s.syncCron}</div>}
                  {s.incrementalKey && <div style={{ color: 'var(--color-text-muted)' }}>增量键：{s.incrementalKey}</div>}
                </div>
              </td>
              <td style={td}>
                <div style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>
                  {s.lastSyncedAt || '从未同步'}
                  {s.lastSyncCursor && <div style={{ marginTop: 4 }}>cursor: {s.lastSyncCursor.slice(0, 20)}…</div>}
                </div>
              </td>
              <td style={td}>
                <button onClick={() => testConnection(s.id)} style={btnTest} aria-label={`测试 ${s.name}`}>测试</button>
                <button onClick={() => syncNow(s.id)} disabled={syncing === s.id} style={btnSync} aria-label={`同步 ${s.name}`}>{syncing === s.id ? '同步中…' : '立即同步'}</button>
                <button onClick={() => openEdit(s)} style={btnEdit} aria-label={`配置 ${s.name}`}>同步配置</button>
                <button onClick={() => remove(s.id)} style={btnDelete} aria-label={`删除 ${s.name}`}>删除</button>
              </td>
            </tr>
          ))}
          {!loading && sources.length === 0 && (
            <tr>
              <td colSpan={7} style={{ ...td, textAlign: 'center', color: 'var(--color-text-muted)', padding: 24 }}>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 }}>
                  <span>暂无数据源</span>
                  <button
                    onClick={() =>
                      setEditing({
                        vendor: 'mysql',
                        host: '',
                        port: 3306,
                        databaseName: '',
                        username: '',
                        password: '',
                        syncEnabled: false,
                        syncMode: 'FULL',
                        syncCron: '',
                        incrementalKey: '',
                      })
                    }
                    style={{
                      padding: '6px 14px',
                      background: 'var(--color-accent, #2563eb)',
                      color: '#fff',
                      border: 'none',
                      borderRadius: 4,
                      cursor: 'pointer',
                    }}
                    aria-label="新建数据源"
                  >
                    新建数据源
                  </button>
                </div>
              </td>
            </tr>
          )}
        </tbody>
      </table></div>
      {editing && (
        <div role="presentation" style={modalOverlay} onClick={() => setEditing(null)}>
          <div role="presentation" style={modalCard} onClick={(e) => e.stopPropagation()}>
            <h3>{editing.id ? '编辑同步配置' : '新建数据源'}</h3>

            {!editing.id && (
              <>
                <Field label="名称">
                  <input value={editing.name || ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} style={input} placeholder="自定义名称" />
                </Field>

                <Field label="数据库类型">
                  <select value={editing.vendor || 'mysql'} onChange={(e) => setEditing({ ...editing, vendor: e.target.value })} style={input}>
                    {/* Wave4 待办池 #14：新增零依赖兼容库（MariaDB/OceanBase→MySQL 驱动；
                        Kingbase/openGauss→PG 驱动）。SQL Server/Oracle 后端未实现，
                        原先展示为可选项属假入口（选中即报错）→ 显式禁用并注明。 */}
                    <option value="mysql">MySQL</option>
                    <option value="postgresql">PostgreSQL</option>
                    <option value="mariadb">MariaDB（MySQL 兼容）</option>
                    <option value="oceanbase">OceanBase（MySQL 模式）</option>
                    <option value="kingbase">KingbaseES（人大金仓，PG 兼容）</option>
                    <option value="opengauss">openGauss（PG 兼容）</option>
                    <option value="sqlserver" disabled>SQL Server（未支持）</option>
                    <option value="oracle" disabled>Oracle（未支持）</option>
                  </select>
                </Field>

                <div className="grid-2">
                  <Field label="主机">
                    <input value={editing.host || ''} onChange={(e) => setEditing({ ...editing, host: e.target.value })} style={input} placeholder="127.0.0.1" />
                  </Field>
                  <Field label="端口">
                    <input type="number" value={editing.port ?? 3306} onChange={(e) => setEditing({ ...editing, port: parseInt(e.target.value) || 3306 })} style={input} />
                  </Field>
                </div>

                <Field label="数据库名">
                  <input value={editing.databaseName || ''} onChange={(e) => setEditing({ ...editing, databaseName: e.target.value })} style={input} />
                </Field>

                <div className="grid-2">
                  <Field label="用户名">
                    <input value={editing.username || ''} onChange={(e) => setEditing({ ...editing, username: e.target.value })} style={input} />
                  </Field>
                  <Field label="密码">
                    <input type="password" value={editing.password || ''} onChange={(e) => setEditing({ ...editing, password: e.target.value })} style={input} />
                  </Field>
                </div>
              </>
            )}

            {editing.id && (
              <div style={{ padding: 12, background: 'var(--color-bg-tertiary)', borderRadius: 6, marginBottom: 12 }}>
                <div style={{ fontSize: 12 }}>
                  <div><strong>{editing.name}</strong> ({editing.vendor?.toUpperCase()})</div>
                  <div style={{ color: 'var(--color-text-muted)' }}>{editing.host}:{editing.port}/{editing.databaseName}</div>
                </div>
              </div>
            )}

            <hr style={{ border: 'none', borderTop: '1px solid var(--color-border-medium)', margin: '16px 0' }} />

            <h4 style={{ margin: '0 0 12px 0' }}>同步设置</h4>

            <Field label="启用同步">
              <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <input type="checkbox" checked={editing.syncEnabled ?? false} onChange={(e) => setEditing({ ...editing, syncEnabled: e.target.checked })} />
                <span style={{ fontSize: 13 }}>开启后将允许定时或手动同步数据</span>
              </label>
            </Field>

            {editing.syncEnabled && (
              <>
                <Field label="同步模式">
                  <select value={editing.syncMode || 'FULL'} onChange={(e) => setEditing({ ...editing, syncMode: e.target.value })} style={input}>
                    <option value="FULL">全量同步</option>
                    <option value="INCREMENTAL">增量同步</option>
                  </select>
                </Field>

                <Field label="定时表达式 (Cron)">
                  <input
                    value={editing.syncCron || ''}
                    onChange={(e) => setEditing({ ...editing, syncCron: e.target.value })}
                    style={input}
                    placeholder="例如：0 0 * * * (每小时整点)"
                  />
                  <div style={{ fontSize: 11, color: 'var(--color-text-muted)', marginTop: 4 }}>
                    留空则不自动调度，仅支持手动触发
                  </div>
                </Field>

                {editing.syncMode === 'INCREMENTAL' && (
                  <Field label="增量键字段">
                    <input
                      value={editing.incrementalKey || ''}
                      onChange={(e) => setEditing({ ...editing, incrementalKey: e.target.value })}
                      style={input}
                      placeholder="例如：updated_at / id"
                    />
                    <div style={{ fontSize: 11, color: 'var(--color-text-muted)', marginTop: 4 }}>
                      用于增量同步的时间戳或自增 ID 字段
                    </div>
                  </Field>
                )}
              </>
            )}

            <div style={{ marginTop: 16, display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => setEditing(null)} style={btnCancel}>取消</button>
              <button onClick={editing.id ? saveSyncConfig : createDataSource} style={btnSave}>{editing.id ? '保存同步配置' : '创建数据源'}</button>
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
      <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginBottom: 4 }}>{label}</div>
      {children}
    </label>
  );
}

const th: React.CSSProperties = { padding: '8px 12px', textAlign: 'left', fontSize: 13, fontWeight: 600 };
const td: React.CSSProperties = { padding: '8px 12px', fontSize: 13, verticalAlign: 'top' };
const input: React.CSSProperties = { width: '100%', padding: '6px 10px', border: '1px solid var(--color-border-medium)', borderRadius: 4 };
const btnTest: React.CSSProperties = { background: 'var(--color-info)', border: 'none', padding: '4px 10px', borderRadius: 4, cursor: 'pointer', marginRight: 4, fontSize: 12 };
const btnSync: React.CSSProperties = { background: 'var(--color-success)', border: 'none', padding: '4px 10px', borderRadius: 4, cursor: 'pointer', marginRight: 4, fontSize: 12 };
const btnEdit: React.CSSProperties = { background: 'var(--color-primary-500)', border: 'none', padding: '4px 10px', borderRadius: 4, cursor: 'pointer', marginRight: 4, fontSize: 12 };
const btnDelete: React.CSSProperties = { background: 'var(--color-error)', border: 'none', padding: '4px 10px', borderRadius: 4, cursor: 'pointer', fontSize: 12 };
const btnCancel: React.CSSProperties = { background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border-medium)', padding: '6px 14px', borderRadius: 4, cursor: 'pointer' };
const btnSave: React.CSSProperties = { background: 'var(--color-info)', color: 'var(--color-text-primary)', border: 'none', padding: '6px 14px', borderRadius: 4, cursor: 'pointer' };
const modalOverlay: React.CSSProperties = { position: 'fixed', inset: 0, background: 'var(--color-bg-primary)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 };
const modalCard: React.CSSProperties = { background: 'var(--color-text-primary)', padding: 24, borderRadius: 8, width: 600, maxHeight: '90vh', overflow: 'auto' };
