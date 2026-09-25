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

      const respData = (res as any).data ?? (res as any);
      setAuth(
        respData.access_token ?? respData.accessToken ?? '',
        respData.refresh_token ?? respData.refreshToken ?? '',
        respData.user ?? respData,
      );
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
        background: 'var(--color-bg-primary)',
      }}
    >
      <form
        onSubmit={handleSubmit(onSubmit)}
        style={{
          padding: 32,
          background: 'var(--color-bg-secondary)',
          borderRadius: 8,
          boxShadow: 'var(--shadow-md)',
          width: 360,
          border: '1px solid var(--color-border-light)',
        }}
      >
        <h1 style={{ marginTop: 0, color: 'var(--color-text-primary)' }}>🛠 NocoBase</h1>
        <p style={{ color: 'var(--color-text-muted)', marginTop: 0 }}>登录开始使用</p>

        {error && (
          <div
            role="alert"
            style={{
              padding: '8px 12px',
              marginBottom: 12,
              background: 'rgba(239,68,68,0.2)',
              color: 'rgba(239,68,68,0.2)',
              borderRadius: 4,
              borderLeft: '4px solid var(--color-error)',
              fontSize: 13,
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}
          >
            <span>❌ {error}</span>
            <button type="button" onClick={() => setError(null)}
                    style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--color-error)', fontSize: 14 }}>
              ✕
            </button>
          </div>
        )}

        {successHint && (
          <div style={{ padding: 8, marginBottom: 12, background: 'rgba(16,185,129,0.2)',
                        color: 'var(--color-success)', borderRadius: 4, borderLeft: '4px solid var(--color-success)', fontSize: 13 }}>
            ✅ {successHint}
          </div>
        )}

        <div style={{ marginBottom: 12 }}>
          <label style={{ display: 'block', marginBottom: 4, color: 'var(--color-text-secondary)' }}>用户名</label>
          <input
            {...register('username')}
            autoComplete="username"
            style={{ width: '100%', padding: 8, fontSize: 14, background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border-light)', borderRadius: 'var(--radius-sm)', color: 'var(--color-text-primary)' }}
          />
          {errors.username && (
            <span style={{ color: 'var(--color-error)', fontSize: 12 }}>{errors.username.message}</span>
          )}
        </div>

        <div style={{ marginBottom: 16 }}>
          <label style={{ display: 'block', marginBottom: 4, color: 'var(--color-text-secondary)' }}>密码</label>
          <input
            {...register('password')}
            type="password"
            autoComplete="current-password"
            style={{ width: '100%', padding: 8, fontSize: 14, background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border-light)', borderRadius: 'var(--radius-sm)', color: 'var(--color-text-primary)' }}
          />
          {errors.password && (
            <span style={{ color: 'var(--color-error)', fontSize: 12 }}>{errors.password.message}</span>
          )}
        </div>

        {/* US-501: 记住用户名 + 忘记密码占位 */}
        <div style={{ display: 'flex', justifyContent: 'space-between',
                      alignItems: 'center', marginBottom: 16, fontSize: 13 }}>
          <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer',
                          color: 'var(--color-text-muted)' }}>
            <input type="checkbox" checked={remember}
                   onChange={(e) => setRemember(e.target.checked)}
                   style={{ marginRight: 6 }} />
            记住用户名
          </label>
          <a href="#" onClick={(e) => { e.preventDefault(); alert('请联系管理员重置密码'); }}
             style={{ color: 'var(--color-primary-400)', textDecoration: 'none', fontSize: 13 }}>
            忘记密码?
          </a>
        </div>

        <button
          type="submit"
          disabled={loading}
          style={{
            width: '100%',
            padding: 10,
            background: loading ? 'var(--color-text-disabled)' : 'var(--color-primary-500)',
            color: 'var(--color-text-primary)',
            border: 'none',
            borderRadius: 4,
            cursor: loading ? 'not-allowed' : 'pointer',
            fontSize: 14,
          }}
        >
          {loading ? '登录中…' : '登录'}
        </button>

        {/* W8: 钉钉扫码登录入口（跳转到 /auth/dingtalk） */}
        <button
          type="button"
          onClick={() => navigate('/auth/dingtalk')}
          style={{
            width: '100%',
            padding: 10,
            marginTop: 12,
            background: 'transparent',
            color: 'var(--color-text-secondary)',
            border: '1px solid var(--color-border-light)',
            borderRadius: 4,
            cursor: 'pointer',
            fontSize: 14,
          }}
        >
          钉钉扫码登录
        </button>

        <p style={{ fontSize: 12, color: 'var(--color-text-muted)', marginTop: 16, textAlign: 'center' }}>
          Week 3 脚手架版:任意非空账号密码可登录
        </p>
      </form>
    </div>
  );
}
