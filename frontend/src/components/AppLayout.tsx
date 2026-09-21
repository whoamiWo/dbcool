import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { useMediaQuery } from '@mui/material';
import { useAuthStore } from '@/stores/auth';
import { disconnectStomp } from '@/lib/stompClient';
import { GlobalSearchPanel } from '@/components/GlobalSearchPanel';

/** 移动端底部导航 — 固定 5 个高频入口。 */
const MOBILE_BOTTOM_NAV = [
  { path: '/workbench', label: '工作台', icon: '🏠' },
  { path: '/im', label: '消息', icon: '💬' },
  { path: '/wiki/kb', label: '知识库', icon: '📚' },
  { path: '/projects', label: '项目', icon: '📋' },
  { path: '/profile', label: '我的', icon: '👤' },
];

export function AppLayout() {
  const { user, clear, switchTenant } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const [switching, setSwitching] = useState(false);
  const isMobile = useMediaQuery('(max-width:768px)');

  const handleLogout = () => {
    disconnectStomp();
    clear();
    navigate('/login');
  };

  const navItems = [
    { path: '/home', label: '首页' },
    { path: '/designer/schemas', label: 'Tables' },
    { path: '/wiki/kb', label: 'Docs' },
    { path: '/im', label: 'Chat' },
    { path: '/projects', label: 'Projects' },
    { path: '/designer/workflows', label: 'Automations' },
    { path: '/agent', label: 'AI' },
    { path: '/admin/users', label: '用户' },
    { path: '/admin/roles', label: '角色' },
    { path: '/admin/acl', label: '权限' },
    { path: '/admin/audit', label: '审计' },
    { path: '/admin/row-acl', label: '行级 ACL' },
    { path: '/admin/er', label: 'ER 图' },
    { path: '/admin/notifications', label: '通知渠道' },
    { path: '/swagger', label: 'API 文档', external: 'http://localhost:8080/swagger-ui/index.html' },
    { path: '/messages', label: '站内信' },
    { path: '/profile', label: '我的' },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <header className="app-header">
        <div style={{ display: 'flex', gap: 24, alignItems: 'center' }}>
          <strong style={{ fontSize: 18, letterSpacing: '0.5px' }}>🛠 NocoBase</strong>
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
                    color: 'var(--color-text-primary)',
                    textDecoration: 'none',
                    padding: '4px 8px',
                    borderRadius: 'var(--radius-sm)',
                    background: 'transparent',
                    borderLeft: '2px solid var(--color-bg-tertiary)',
                    marginLeft: 8,
                    transition: 'all var(--transition-fast)',
                  }}
                  onMouseEnter={(e) => {
                    (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.05)';
                  }}
                  onMouseLeave={(e) => {
                    (e.currentTarget as HTMLElement).style.background = 'transparent';
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
                  color: 'var(--color-text-primary)',
                  textDecoration: 'none',
                  padding: '4px 8px',
                  borderRadius: 'var(--radius-sm)',
                  background: active ? 'rgba(99, 102, 241, 0.2)' : 'transparent',
                  transition: 'all var(--transition-fast)',
                  borderBottom: active ? '2px solid var(--color-primary-500)' : '2px solid transparent',
                }}
              >
                {item.label}
              </Link>
            );
          })}
        </div>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <GlobalSearchPanel />
          <span style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>
            {user?.username ?? '游客'}({user?.roles.join(', ') ?? 'no role'})
          </span>
          {user && (
            <button
              onClick={() => setSwitching(true)}
              style={{
                padding: '6px 14px',
                background: 'var(--color-primary-500)',
                color: 'var(--color-text-primary)',
                border: 'none',
                borderRadius: 'var(--radius-sm)',
                cursor: 'pointer',
                fontSize: 12,
                fontWeight: 500,
                transition: 'all var(--transition-fast)',
                boxShadow: 'var(--shadow-glow)',
              }}
              onMouseEnter={(e) => {
                (e.currentTarget as HTMLElement).style.background = 'var(--color-primary-600)';
              }}
              onMouseLeave={(e) => {
                (e.currentTarget as HTMLElement).style.background = 'var(--color-primary-500)';
              }}
            >
              🔄 切换应用({user.tenant_id})
            </button>
          )}
          <button
            onClick={handleLogout}
            style={{
              padding: '6px 14px',
              background: 'var(--color-error)',
              color: 'var(--color-text-primary)',
              border: 'none',
              borderRadius: 'var(--radius-sm)',
              cursor: 'pointer',
              fontSize: 12,
              fontWeight: 500,
              transition: 'all var(--transition-fast)',
            }}
            onMouseEnter={(e) => {
              (e.currentTarget as HTMLElement).style.opacity = '0.85';
            }}
            onMouseLeave={(e) => {
              (e.currentTarget as HTMLElement).style.opacity = '1';
            }}
          >
            登出
          </button>
        </div>
      </header>
      <main className="app-main">
        <Outlet />
      </main>

      {/* 移动端底部导航 (768px 以下) */}
      {isMobile && (
        <nav className="app-footer">
          {MOBILE_BOTTOM_NAV.map((item) => {
            const active = location.pathname.startsWith(item.path);
            return (
              <button
                key={item.path}
                onClick={() => navigate(item.path)}
                style={{
                  flex: 1,
                  background: 'none',
                  border: 'none',
                  color: active ? 'var(--color-primary-400)' : 'var(--color-text-muted)',
                  cursor: 'pointer',
                  padding: '4px 0',
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 2,
                  fontSize: 10,
                  fontWeight: active ? 600 : 400,
                  transition: 'all var(--transition-fast)',
                }}
              >
                <span style={{ fontSize: 20 }}>{item.icon}</span>
                {item.label}
              </button>
            );
          })}
        </nav>
      )}

      {/* 应用切换弹窗 */}
      {switching && user && (
        <div
          role="dialog"
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(15,23,42,0.7)',
            backdropFilter: 'blur(8px)',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            zIndex: 1000,
          }}
        >
          <div className="glass-strong" style={{ padding: 24, width: 400 }}>
            <h3 style={{ marginTop: 0, color: 'var(--color-text-primary)' }}>🔄 切换应用</h3>
            <p style={{ color: 'var(--color-text-muted)', fontSize: 13, margin: 0 }}>
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

  useEffect(() => {
    (async () => {
      try {
        setLoading(true);
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
        <div style={{ padding: 8, background: 'rgba(239,68,68,0.2)', color: '#fca5a5', borderRadius: 'var(--radius-sm)', fontSize: 12 }}>
          {error}
        </div>
      )}
      {loading ? (
        <p style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>加载中…</p>
      ) : tenants.length === 0 ? (
        <p style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>暂无可切换的应用</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {tenants.map((t) => (
            <button
              key={t.id}
              onClick={() => onSelect(t.id)}
              style={{
                padding: '8px 12px',
                background: t.id === currentTenantId ? 'rgba(99,102,241,0.2)' : 'var(--glass-bg-light)',
                color: t.id === currentTenantId ? 'var(--color-primary-300)' : 'var(--color-text-primary)',
                border: `1px solid ${t.id === currentTenantId ? 'var(--color-primary-500)' : 'var(--color-border-light)'}`,
                borderRadius: 'var(--radius-md)',
                cursor: 'pointer',
                textAlign: 'left',
                fontSize: 13,
                transition: 'all var(--transition-fast)',
              }}
            >
              {t.id === currentTenantId ? '✓ ' : ''}
              <strong>{t.name}</strong>
              <span style={{ color: 'var(--color-text-muted)', marginLeft: 8, fontSize: 11 }}>({t.id})</span>
            </button>
          ))}
        </div>
      )}
      <div style={{ marginTop: 12, display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
        <button onClick={onClose} style={{ padding: '6px 14px', background: 'var(--color-bg-tertiary)', color: 'var(--color-text-primary)', border: 'none', borderRadius: 'var(--radius-sm)', cursor: 'pointer' }}>
          关闭
        </button>
      </div>
    </div>
  );
}
