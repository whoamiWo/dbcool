import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import apiClient from '@/api/client';

/**
 * 个人中心(US-502 改密码).
 */
export function ProfilePage() {
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const changeMutation = useMutation({
    mutationFn: () =>
      apiClient.post<{ code: number; message: string }>('/auth/password', {
        oldPassword,
        newPassword,
      }),
    onSuccess: (res) => {
      setSuccess(res.message);
      setError(null);
      setOldPassword('');
      setNewPassword('');
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '修改失败');
      setSuccess(null);
    },
  });

  return (
    <div style={{ maxWidth: 500 }}>
      <h1>👤 个人中心</h1>
      <div style={{ padding: 16, background: 'white', borderRadius: 8, boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
        <h3 style={{ marginTop: 0 }}>修改密码(US-502)</h3>
        {error && <div style={{ padding: 8, marginBottom: 12, background: '#fee2e2', color: '#991b1b', borderRadius: 4 }}>{error}</div>}
        {success && <div style={{ padding: 8, marginBottom: 12, background: '#dcfce7', color: '#166534', borderRadius: 4 }}>{success}</div>}
        <div style={{ marginBottom: 8 }}>
          <label>旧密码</label>
          <input type="password" value={oldPassword} onChange={(e) => setOldPassword(e.target.value)} style={{ ...inputStyle }} />
        </div>
        <div style={{ marginBottom: 8 }}>
          <label>新密码</label>
          <input type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} style={{ ...inputStyle }} />
        </div>
        <button onClick={() => changeMutation.mutate()} disabled={changeMutation.isPending || !oldPassword || !newPassword} style={btnStyle}>
          {changeMutation.isPending ? '修改中…' : '修改密码'}
        </button>
      </div>
    </div>
  );
}

const inputStyle = { padding: 6, width: '100%', border: '1px solid #cbd5e1', borderRadius: 4 };
const btnStyle = { padding: '8px 16px', background: '#1e293b', color: 'white', border: 'none', borderRadius: 4, cursor: 'pointer' };
