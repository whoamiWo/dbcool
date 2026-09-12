import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/auth';

export function AppLayout() {
  const { user, clear } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();

  const handleLogout = () => {
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
    </div>
  );
}
