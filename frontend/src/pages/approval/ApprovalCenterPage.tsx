import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import apiClient from '@/api/client';

interface Template {
  id: string;
  name: string;
  icon: string;
  category: string;
  approverType: string;
}

interface ApprovalItem {
  id: string;
  templateId: string;
  templateName?: string;
  status: string;
  submittedAt: string;
  formData?: Record<string, unknown>;
  taskId?: string;
}

const STATUS_LABEL: Record<string, string> = {
  PENDING: '审批中',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  CANCELED: '已撤回',
};

type Tab = 'todo' | 'mine' | 'cc';

/**
 * 审批中心（E2）：待办 / 我发起的 / 抄送 + 发起审批。
 */
export function ApprovalCenterPage() {
  const [tab, setTab] = useState<Tab>('todo');
  const [templates, setTemplates] = useState<Template[]>([]);
  const [items, setItems] = useState<ApprovalItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState({ templateId: '', title: '', reason: '' });
  // 管理员：从预设生成审批模板（后端 POST /approvals/templates/from-preset）
  const [tpl, setTpl] = useState({
    name: '',
    category: '',
    levels: 1,
    approverType: 'MANAGER',
    assignee: '',
  });
  const [tplMsg, setTplMsg] = useState<string | null>(null);

  const loadTemplates = () => {
    apiClient
      .get<Template[]>('/approvals/templates')
      .then(r => setTemplates(r ?? []))
      .catch(() => setTemplates([]));
  };

  const loadItems = (which: Tab) => {
    setError(null);
    apiClient
      .get<ApprovalItem[]>(`/approvals/${which}`)
      .then(r => setItems(r ?? []))
      .catch(e => setError(e?.response?.data?.message ?? e?.message ?? '加载失败'));
  };

  useEffect(loadTemplates, []);
  useEffect(() => {
    // 避免在 effect 体里同步 setState（会触发级联渲染）：
    // 状态更新放在请求回调 / catch 中。
    let cancelled = false;
    apiClient
      .get<ApprovalItem[]>(`/approvals/${tab}`)
      .then(r => {
        if (!cancelled) {
          setItems(r ?? []);
          setError(null);
        }
      })
      .catch(e => {
        if (!cancelled) {
          setError(e?.response?.data?.message ?? e?.message ?? '加载失败');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [tab]);

  const createTemplate = async () => {
    setTplMsg(null);
    try {
      await apiClient.post('/approvals/templates/from-preset', {
        name: tpl.name,
        category: tpl.category,
        levels: Number(tpl.levels) || 1,
        approverType: tpl.approverType,
        assignee: tpl.assignee || null,
      });
      setTpl({ name: '', category: '', levels: 1, approverType: 'MANAGER', assignee: '' });
      setTplMsg('模板已创建');
      loadTemplates();
    } catch (e: any) {
      setTplMsg(e?.response?.data?.message ?? e?.message ?? '创建失败（需要管理员权限）');
    }
  };

  const submit = async () => {
    if (!form.templateId) {
      setError('请选择审批模板');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await apiClient.post('/approvals', {
        templateId: form.templateId,
        formData: { title: form.title, reason: form.reason },
        ccUserIds: [],
      });
      setForm({ templateId: '', title: '', reason: '' });
      loadItems('mine');
      setTab('mine');
    } catch (e: any) {
      // 例如「未配置直属上级」——后端返回可读文案
      setError(e?.response?.data?.message ?? e?.message ?? '提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ padding: 20 }}>
      <h2 style={{ fontSize: 18, marginBottom: 12 }}>审批中心</h2>

      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        {(['todo', 'mine', 'cc'] as Tab[]).map(t => (
          <button
            key={t}
            onClick={() => setTab(t)}
            style={{
              padding: '6px 14px',
              borderRadius: 6,
              border: '1px solid var(--color-border-light)',
              background: tab === t ? 'var(--color-primary-500)' : 'transparent',
              color: 'var(--color-text-primary)',
              cursor: 'pointer',
            }}
          >
            {t === 'todo' ? '待我审批' : t === 'mine' ? '我发起的' : '抄送我的'}
          </button>
        ))}
      </div>

      {error && <div style={{ color: 'var(--color-error)', marginBottom: 12 }}>{error}</div>}

      <div style={{ display: 'flex', gap: 20 }}>
        <section style={{ flex: 1 }}>
          {items.length === 0 && <div style={{ opacity: 0.6 }}>暂无记录</div>}
          {items.map(it => (
            <Link
              key={it.id}
              to={`/approvals/${it.id}`}
              style={{ display: 'block', textDecoration: 'none', color: 'inherit' }}
            >
              <div style={{ border: '1px solid var(--color-border-light)', borderRadius: 8, padding: 14, marginBottom: 10 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <strong>{it.templateName ?? '审批单'}</strong>
                  <span style={{ fontSize: 12, color: statusColor(it.status) }}>
                    {STATUS_LABEL[it.status] ?? it.status}
                  </span>
                </div>
                <div style={{ fontSize: 12, opacity: 0.7, marginTop: 6 }}>
                  {(it.formData?.title as string) || '（无标题）'}
                </div>
                <div style={{ fontSize: 11, opacity: 0.5, marginTop: 4 }}>
                  {new Date(it.submittedAt).toLocaleString()}
                </div>
              </div>
            </Link>
          ))}
        </section>

        <aside style={{ width: 340 }}>
          <h3 style={{ fontSize: 14 }}>发起审批</h3>
          <select
            value={form.templateId}
            onChange={e => setForm({ ...form, templateId: e.target.value })}
            style={{ width: '100%', padding: '8px 10px', margin: '6px 0 10px', borderRadius: 6, border: '1px solid var(--color-border-light)' }}
          >
            <option value="">选择审批模板…</option>
            {templates.map(t => (
              <option key={t.id} value={t.id}>{t.name}（{t.approverType}）</option>
            ))}
          </select>
          <input
            placeholder="标题"
            value={form.title}
            onChange={e => setForm({ ...form, title: e.target.value })}
            style={{ width: '100%', padding: '8px 10px', marginBottom: 10, borderRadius: 6, border: '1px solid var(--color-border-light)' }}
          />
          <textarea
            placeholder="申请事由"
            value={form.reason}
            onChange={e => setForm({ ...form, reason: e.target.value })}
            rows={4}
            style={{ width: '100%', padding: '8px 10px', marginBottom: 10, borderRadius: 6, border: '1px solid var(--color-border-light)' }}
          />
          <button onClick={submit} disabled={submitting}>
            {submitting ? '提交中…' : '提交'}
          </button>
          {templates.length === 0 && (
            <p style={{ fontSize: 12, opacity: 0.6, marginTop: 12 }}>
              暂无审批模板，请管理员在下方创建。
            </p>
          )}

          <details style={{ marginTop: 20 }}>
            <summary style={{ cursor: 'pointer', fontSize: 13 }}>新建审批模板（管理员）</summary>
            <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 8 }}>
              <input
                placeholder="模板名称，如「请假单」"
                value={tpl.name}
                onChange={e => setTpl({ ...tpl, name: e.target.value })}
                style={{ padding: '8px 10px', borderRadius: 6, border: '1px solid var(--color-border-light)' }}
              />
              <input
                placeholder="分类（可选）"
                value={tpl.category}
                onChange={e => setTpl({ ...tpl, category: e.target.value })}
                style={{ padding: '8px 10px', borderRadius: 6, border: '1px solid var(--color-border-light)' }}
              />
              <label style={{ fontSize: 12 }}>
                审批级数
                <input
                  type="number"
                  min={1}
                  max={5}
                  value={tpl.levels}
                  onChange={e => setTpl({ ...tpl, levels: Number(e.target.value) })}
                  style={{ width: 80, marginLeft: 8 }}
                />
              </label>
              <select
                value={tpl.approverType}
                onChange={e => setTpl({ ...tpl, approverType: e.target.value })}
                style={{ padding: '8px 10px', borderRadius: 6, border: '1px solid var(--color-border-light)' }}
              >
                <option value="MANAGER">直属上级审批</option>
                <option value="USER">指定人审批</option>
                <option value="ROLE">角色成员审批</option>
              </select>
              {tpl.approverType !== 'MANAGER' && (
                <input
                  placeholder={tpl.approverType === 'ROLE' ? '角色 ID' : '审批人用户 ID'}
                  value={tpl.assignee}
                  onChange={e => setTpl({ ...tpl, assignee: e.target.value })}
                  style={{ padding: '8px 10px', borderRadius: 6, border: '1px solid var(--color-border-light)' }}
                />
              )}
              <button onClick={createTemplate} disabled={!tpl.name}>创建模板</button>
              {tplMsg && <div style={{ fontSize: 12, opacity: 0.8 }}>{tplMsg}</div>}
            </div>
          </details>
        </aside>
      </div>
    </div>
  );
}

function statusColor(status: string): string {
  switch (status) {
    case 'APPROVED': return 'var(--color-success-500)';
    case 'REJECTED': return 'var(--color-error)';
    case 'CANCELED': return 'var(--color-text-secondary)';
    default: return 'var(--color-warning-500)';
  }
}
