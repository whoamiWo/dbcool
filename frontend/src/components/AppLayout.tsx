import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { useAuthStore } from '@/stores/auth';
import { disconnectStomp } from '@/lib/stompClient';


export function AppLayout() {
  const { user, clear, switchTenant } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const [switching, setSwitching] = useState(false);

  const handleLogout = () => {
    disconnectStomp(); // 先断开 WS,避免旧 token 连接残留
    clear();
    navigate('/login');
  };

  const navItems = [
    { path: '/home', label: '首页' },
    { path: '/designer/schemas', label: '数据模型' },
    { path: '/designer/views', label: '视图' },
    { path: '/admin/users', label: '用户' },
    { path: '/admin/roles', label: '角色' },
    { path: '/admin/acl', label: '权限' },
    { path: '/admin/audit', label: '审计' },
    { path: '/admin/row-acl', label: '行级 ACL' },
    { path: '/admin/er', label: 'ER 图' },
    { path: '/admin/notifications', label: '通知渠道' },
    { path: '/swagger', label: 'API 文档', external: 'http://localhost:8080/swagger-ui/index.html' },
    { path: '/designer/workflows', label: '工作流' },
    { path: '/messages', label: '站内信' },
    { path: '/im', label: '即时消息' },
    { path: '/profile', label: '我的' },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <header
        style={{
          padding: '12px 24px',
          background: '#1e293b',
          color: 'white',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <div style={{ display: 'flex', gap: 24, alignItems: 'center' }}>
          <strong style={{ fontSize: 18 }}>🛠 NocoBase</strong>
          {navItems.map((item) => {
            const active = !item.external && location.pathname.startsWith(item.path);
            if (item.external) {
              return (
                <a
                  key={item.path}
                  href={item.external}
                  target="_blank"
                  rel="noreferrer"
                  style={{
                    color: 'white',
                    textDecoration: 'none',
                    padding: '4px 8px',
                    borderRadius: 4,
                    background: 'transparent',
                    borderLeft: '2px solid #475569',
                    marginLeft: 8,
                  }}
                >
                  {item.label} ↗
                </a>
              );
            }
            return (
              <Link
                key={item.path}
                to={item.path}
                style={{
                  color: 'white',
                  textDecoration: 'none',
                  padding: '4px 8px',
                  borderRadius: 4,
                  background: active ? '#334155' : 'transparent',
                }}
              >
                {item.label}
              </Link>
            );
          })}
        </div>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <span style={{ fontSize: 13 }}>
            {user?.username ?? '游客'}({user?.roles.join(', ') ?? 'no role'})
          </span>
          {/* US-504: 应用切换 */}
          {user && (
            <button
              onClick={() => setSwitching(true)}
              style={{
                padding: '4px 12px',
                background: '#0ea5e9',
                color: 'white',
                border: 'none',
                borderRadius: 4,
                cursor: 'pointer',
                fontSize: 12,
              }}
            >
              🔄 切换应用({user.tenant_id})
            </button>
          )}
          <button
            onClick={handleLogout}
            style={{
              padding: '4px 12px',
              background: '#dc2626',
              color: 'white',
              border: 'none',
              borderRadius: 4,
              cursor: 'pointer',
            }}
          >
            登出
          </button>
        </div>
      </header>
      <main style={{ flex: 1, padding: 24, background: '#f8fafc' }}>
        <Outlet />
      </main>

      {/* US-504: 应用切换弹窗 */}
      {switching && user && (
        <div
          role="dialog"
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(15,23,42,0.5)',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            zIndex: 100,
          }}
        >
          <div style={{ background: 'white', borderRadius: 8, padding: 24, width: 400, boxShadow: '0 10px 25px rgba(0,0,0,0.2)' }}>
            <h3 style={{ marginTop: 0 }}>🔄 切换应用</h3>
            <p style={{ color: '#64748b', fontSize: 13, margin: 0 }}>
              选择要切换的应用(租户)。切换后将刷新页面以加载新应用上下文。
            </p>
            <TenantSwitcher
              currentTenantId={user.tenant_id}
              onSelect={(tenantId) => {
                switchTenant(tenantId);
                window.location.reload();
              }}
              onClose={() => setSwitching(false)}
            />
          </div>
        </div>
      )}
    </div>
  );
}

/** US-504: 租户切换选择器 */
function TenantSwitcher({
  currentTenantId,
  onSelect,
  onClose,
}: {
  currentTenantId: string;
  onSelect: (tenantId: string) => void;
  onClose: () => void;
}) {
  const [tenants, setTenants] = useState<Array<{ id: string; name: string }>>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);


  // 加载当前用户可切换的租户列表
  useEffect(() => {
    (async () => {
      try {
        setLoading(true);
        // 先尝试用户级 API(US-504 新端点)
        const token = localStorage.getItem('nocobase_access_token');
        const headers: Record<string, string> = {};
        if (token) headers.Authorization = `Bearer ${token}`;
        const r = await fetch('/api/admin/tenants', { headers });
        const d = await r.json();
        const list = (d.data || []).filter((t: { id: string; status: string }) => t.status === 'ACTIVE');
        setTenants(list.map((t: { id: string; name: string }) => ({ id: t.id, name: t.name })));
      } catch (e) {
        setError((e as Error).message);
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  return (
    <div style={{ marginTop: 12 }}>
      {error && (
        <div style={{ padding: 8, background: '#fee2e2', color: '#991b1b', borderRadius: 4, fontSize: 12 }}>
          {error}
        </div>
      )}
      {loading ? (
        <p style={{ color: '#64748b', fontSize: 13 }}>加载中…</p>
      ) : tenants.length === 0 ? (
        <p style={{ color: '#64748b', fontSize: 13 }}>暂无可切换的应用</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {tenants.map((t) => (
            <button
              key={t.id}
              onClick={() => onSelect(t.id)}
              style={{
                padding: '8px 12px',
                background: t.id === currentTenantId ? '#dbeafe' : '#f8fafc',
                color: t.id === currentTenantId ? '#1e40af' : '#334155',
                border: '1px solid ' + (t.id === currentTenantId ? '#93c5fd' : '#e2e8f0'),
                borderRadius: 6,
                cursor: 'pointer',
                textAlign: 'left',
                fontSize: 13,
              }}
            >
              {t.id === currentTenantId ? '✓ ' : ''}
              <strong>{t.name}</strong>
              <span style={{ color: '#64748b', marginLeft: 8, fontSize: 11 }}>({t.id})</span>
            </button>
          ))}
        </div>
      )}
      <div style={{ marginTop: 12, display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
        <button onClick={onClose} style={{ padding: '6px 14px', background: '#e2e8f0', color: '#1e293b', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
          关闭
        </button>
      </div>
    </div>
  );
}
