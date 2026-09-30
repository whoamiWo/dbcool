import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuthStore } from '@/stores/auth';
import { useIsMobile } from '@/hooks/useIsMobile';
import { disconnectStomp } from '@/lib/stompClient';
import { GlobalSearchPanel } from '@/components/GlobalSearchPanel';
import { useTranslation } from 'react-i18next';
import { changeLanguage } from '@/i18n';

/** AppLayout 内部的 QueryClient（测试覆盖用） */
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, staleTime: Infinity } },
});

/** 移动端底部导航 — 固定 6 个高频入口（用 labelKey 而非 label，渲染时再 t()） */
const MOBILE_BOTTOM_NAV = [
  { path: '/workbench', labelKey: 'im.workbench', icon: '🏠' },
  { path: '/im', labelKey: 'im.messages', icon: '💬' },
  { path: '/wiki/kb', labelKey: 'im.wiki', icon: '📚' },
  { path: '/projects', labelKey: 'im.projects', icon: '📋' },
  { path: '/admin/automations', labelKey: 'im.automations', icon: '⚙️' },
  { path: '/profile', labelKey: 'im.profile', icon: '👤' },
];

export function AppLayout() {
  const { user, clear, switchTenant } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const [switching, setSwitching] = useState(false);
  const isMobile = useIsMobile();
  const { i18n, t } = useTranslation();

  const handleLogout = () => {
    disconnectStomp();
    clear();
    navigate('/login');
  };

  const navItems = [
    { path: '/home', labelKey: 'nav.home' },
    { path: '/designer/schemas', labelKey: 'nav.tables' },
    { path: '/wiki/kb', labelKey: 'nav.wiki' },
    { path: '/im', labelKey: 'nav.im' },
    { path: '/projects', labelKey: 'nav.projects' },
    { path: '/designer/workflows', labelKey: 'nav.workflows' },
    { path: '/agent', labelKey: 'nav.agent' },
    { path: '/admin/users', labelKey: 'nav.users' },
    { path: '/admin/roles', labelKey: 'nav.roles' },
    { path: '/admin/acl', labelKey: 'nav.permissions' },
    { path: '/admin/audit', labelKey: 'nav.audit' },
    { path: '/admin/row-acl', labelKey: 'nav.rowAcl' },
    { path: '/admin/er', labelKey: 'nav.er' },
    { path: '/admin/notifications', labelKey: 'nav.notifications' },
    { path: '/swagger', labelKey: 'nav.apiDocs', external: 'http://localhost:8080/swagger-ui/index.html' },
    { path: '/messages', labelKey: 'nav.inAppMessages' },
    { path: '/profile', labelKey: 'nav.profile' },
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
                    (e.currentTarget as HTMLElement).style.background = 'var(--color-border-light)';
                  }}
                  onMouseLeave={(e) => {
                    (e.currentTarget as HTMLElement).style.background = 'transparent';
                  }}
                >
                  {t(item.labelKey)} ↗
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
                {t(item.labelKey)}
              </Link>
            );
          })}
        </div>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <GlobalSearchPanel />
          <span style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>
            {user?.username ?? t('im.guest')}({user?.roles.join(', ') ?? t('im.noRole')})
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
              {t('im.switchApp')} ({user.tenant_id})
            </button>
          )}
          <div style={{ display: 'flex', gap: '4px', alignItems: 'center' }}>
            {['zh-CN', 'en-US'].map((lang) => (
              <button
                key={lang}
                onClick={() => changeLanguage(lang as 'zh-CN' | 'en-US')}
                style={{
                  padding: '6px 10px',
                  background: i18n.language === lang ? 'var(--color-primary-500)' : 'var(--color-bg-secondary)',
                  color: i18n.language === lang ? 'var(--color-text-primary)' : 'var(--color-text-secondary)',
                  border: '1px solid var(--color-border-light)',
                  borderRadius: 'var(--radius-sm)',
                  cursor: 'pointer',
                  fontSize: 12,
                  fontWeight: 500,
                  transition: 'all var(--transition-fast)',
                }}
              >
                {lang === 'zh-CN' ? t('im.langZh') : t('im.langEn')}
              </button>
            ))}
          </div>
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
            {t('im.logout')}
          </button>
        </div>
      </header>
      <QueryClientProvider client={queryClient}>
        <main className="app-main">
          <Outlet />
        </main>
      </QueryClientProvider>

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
                {t(item.labelKey)}
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
            background: 'var(--glass-bg-strong)',
            backdropFilter: 'blur(8px)',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            zIndex: 1000,
          }}
        >
          <div className="glass-strong" style={{ padding: 24, width: 400 }}>
            <h3 style={{ marginTop: 0, color: 'var(--color-text-primary)' }}>{t('im.switchApp')}</h3>
            <p style={{ color: 'var(--color-text-muted)', fontSize: 13, margin: 0 }}>
              {t('im.switchAppHint')}
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
  const { t } = useTranslation();
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
        <div style={{ padding: 8, background: 'var(--color-error)', color: 'var(--color-error)', borderRadius: 'var(--radius-sm)', fontSize: 12 }}>
          {error}
        </div>
      )}
      {loading ? (
        <p style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>{t('im.loadingApps')}</p>
      ) : tenants.length === 0 ? (
        <p style={{ color: 'var(--color-text-muted)', fontSize: 13 }}>{t('im.noApps')}</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {tenants.map((t) => (
            <button
              key={t.id}
              onClick={() => onSelect(t.id)}
              style={{
                padding: '8px 12px',
                background: t.id === currentTenantId ? 'var(--color-primary-500)' : 'var(--glass-bg-light)',
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
          {t('im.close')}
        </button>
      </div>
    </div>
  );
}
