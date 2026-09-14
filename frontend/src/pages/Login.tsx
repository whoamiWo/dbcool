import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import apiClient from '@/api/client';
import { useAuthStore } from '@/stores/auth';

const REMEMBER_KEY = 'nocobase:login:lastUsername';

const loginSchema = z.object({
  username: z.string().min(1, '请输入用户名'),
  password: z.string().min(1, '请输入密码'),
});

type LoginForm = z.infer<typeof loginSchema>;

export function LoginPage() {
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [remember, setRemember] = useState(true);
  const [successHint, setSuccessHint] = useState<string | null>(null);
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);

  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<LoginForm>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      username: localStorage.getItem(REMEMBER_KEY) ?? '',
      password: '',
    },
  });

  useEffect(() => {
    const last = localStorage.getItem(REMEMBER_KEY);
    if (last) setValue('username', last);
  }, [setValue]);

  const onSubmit = async (data: LoginForm) => {
    setLoading(true);
    setError(null);
    setSuccessHint(null);
    try {
      const res = await apiClient.post<{
        code: number;
        message: string;
        data: {
          access_token: string;
          refresh_token: string;
          user: { id: string; username: string; tenant_id: string; roles: string[] };
        };
      }>('/auth/login', data);

      if ((res as any).code !== 0) {
        setError((res as any).message);
        return;
      }

      // 记住用户名(US-501)
      if (remember) {
        localStorage.setItem(REMEMBER_KEY, data.username);
      } else {
        localStorage.removeItem(REMEMBER_KEY);
      }

      setAuth((res as any).data?.access_token ?? (res as any).access_token, (res as any).data?.user ?? (res as any).user);
      setSuccessHint('登录成功,正在跳转…');
      setTimeout(() => navigate('/home'), 300);
    } catch (e) {
      const err = e as { response?: { data?: { message?: string } } };
      setError(err.response?.data?.message ?? '登录失败,请检查网络');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '100vh',
        background: '#f1f5f9',
      }}
    >
      <form
        onSubmit={handleSubmit(onSubmit)}
        style={{
          padding: 32,
          background: 'white',
          borderRadius: 8,
          boxShadow: '0 4px 6px rgba(0,0,0,0.1)',
          width: 360,
        }}
      >
        <h1 style={{ marginTop: 0 }}>🛠 NocoBase</h1>
        <p style={{ color: '#64748b', marginTop: 0 }}>登录开始使用</p>

        {error && (
          <div
            role="alert"
            style={{
              padding: '8px 12px',
              marginBottom: 12,
              background: '#fee2e2',
              color: '#991b1b',
              borderRadius: 4,
              borderLeft: '4px solid #dc2626',
              fontSize: 13,
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}
          >
            <span>❌ {error}</span>
            <button type="button" onClick={() => setError(null)}
                    style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#991b1b', fontSize: 14 }}>
              ✕
            </button>
          </div>
        )}

        {successHint && (
          <div style={{ padding: 8, marginBottom: 12, background: '#dcfce7',
                        color: '#166534', borderRadius: 4, borderLeft: '4px solid #10b981', fontSize: 13 }}>
            ✅ {successHint}
          </div>
        )}

        <div style={{ marginBottom: 12 }}>
          <label style={{ display: 'block', marginBottom: 4 }}>用户名</label>
          <input
            {...register('username')}
            autoComplete="username"
            style={{ width: '100%', padding: 8, fontSize: 14 }}
          />
          {errors.username && (
            <span style={{ color: '#dc2626', fontSize: 12 }}>{errors.username.message}</span>
          )}
        </div>

        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 4 }}>密码</label>
          <input
            {...register('password')}
            type="password"
            autoComplete="current-password"
            style={{ width: '100%', padding: 8, fontSize: 14 }}
          />
          {errors.password && (
            <span style={{ color: '#dc2626', fontSize: 12 }}>{errors.password.message}</span>
          )}
        </div>

        {/* US-501: 记住用户名 + 忘记密码占位 */}
        <div style={{ display: 'flex', justifyContent: 'space-between',
                      alignItems: 'center', marginBottom: 16, fontSize: 13 }}>
          <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer',
                          color: '#475569' }}>
            <input type="checkbox" checked={remember}
                   onChange={(e) => setRemember(e.target.checked)}
                   style={{ marginRight: 6 }} />
            记住用户名
          </label>
          <a href="#" onClick={(e) => { e.preventDefault(); alert('请联系管理员重置密码'); }}
             style={{ color: '#1e40af', textDecoration: 'none', fontSize: 13 }}>
            忘记密码?
          </a>
        </div>

        <button
          type="submit"
          disabled={loading}
          style={{
            width: '100%',
            padding: 10,
            background: loading ? '#94a3b8' : '#1e293b',
            color: 'white',
            border: 'none',
            borderRadius: 4,
            cursor: loading ? 'not-allowed' : 'pointer',
            fontSize: 14,
          }}
        >
          {loading ? '登录中…' : '登录'}
        </button>

        <p style={{ fontSize: 12, color: '#64748b', marginTop: 16, textAlign: 'center' }}>
          Week 3 脚手架版:任意非空账号密码可登录
        </p>
      </form>
    </div>
  );
}
