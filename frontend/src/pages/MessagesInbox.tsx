import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import apiClient from '@/api/client';
import EmptyState from '@/components/EmptyState';

interface Message {
  id: string;
  type: string;
  title: string;
  body: string;
  related_id: string;
  deeplink?: string;
  priority?: string;
  digest_count?: number;
  read: boolean;
  read_at?: string;
  created_at: string;
}

interface InboxResp {
  unread_count: number;
  messages: Message[];
  limit: number;
  next_cursor: string;
  has_more: boolean;
}

/** 通知类型（E3）——与后端 NotifyDispatcher 常量一致。 */
const TYPE_TABS: { key: string; label: string }[] = [
  { key: '', label: '全部' },
  { key: 'APPROVAL_TODO', label: '待审批' },
  { key: 'APPROVAL_RESULT', label: '审批结果' },
  { key: 'MENTION', label: '提到我' },
  { key: 'COMMENT', label: '评论' },
  { key: 'DUE_SOON', label: '即将到期' },
  { key: 'WORKFLOW_FAILED', label: '流程失败' },
  { key: 'QUOTA', label: '额度' },
  { key: 'SYSTEM', label: '系统' },
];

/**
 * 通知中心（E3）——在既有站内信页上升级：类型筛选、全部已读、deep link。
 */
export function MessagesInboxPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [type, setType] = useState('');
  const [stack, setStack] = useState<{ cursor: string | null; messages: Message[] }>({
    cursor: null,
    messages: [],
  });
  // 偏好设置（复用 user_preferences，不新建表）
  const [prefs, setPrefs] = useState<Record<string, any>>({});
  const [prefsMsg, setPrefsMsg] = useState<string | null>(null);

  useEffect(() => {
    apiClient
      .get<Record<string, any>>('/users/me/preferences')
      .then(r => setPrefs(r ?? {}))
      .catch(() => setPrefs({}));
  }, []);

  const notifyPrefs: Record<string, any> = prefs.notify ?? {};
  const isEnabled = (type: string) => {
    const v = notifyPrefs[type];
    if (v === undefined) return true; // 默认开启
    return Array.isArray(v) ? v.length > 0 : Boolean(v);
  };
  const toggle = (type: string) => {
    const nextNotify = {
      ...notifyPrefs,
      [type]: isEnabled(type) ? [] : ['EMAIL'],
    };
    setPrefs({ ...prefs, notify: nextNotify });
  };
  const setQuiet = (idx: 0 | 1, value: string) => {
    const current = Array.isArray(notifyPrefs.quietHours) ? notifyPrefs.quietHours : ['22:00', '08:00'];
    const next = [...current];
    next[idx] = value;
    setPrefs({ ...prefs, notify: { ...notifyPrefs, quietHours: next } });
  };
  const savePrefs = async () => {
    setPrefsMsg(null);
    try {
      const saved = await apiClient.put<Record<string, any>>('/users/me/preferences', prefs);
      setPrefs(saved ?? prefs);
      setPrefsMsg('已保存');
    } catch (e: any) {
      setPrefsMsg(e?.response?.data?.message ?? e?.message ?? '保存失败');
    }
  };

  const { data, isLoading } = useQuery({
    queryKey: ['messages', unreadOnly, type, stack.cursor],
    queryFn: () => {
      const p = new URLSearchParams();
      p.set('unreadOnly', String(unreadOnly));
      p.set('limit', '20');
      if (stack.cursor) p.set('before', stack.cursor);
      if (type) p.set('type', type);
      return apiClient.get<InboxResp>('/messages?' + p.toString());
    },
  });

  const merged: Message[] = [...stack.messages, ...(data?.messages ?? [])];

  const markRead = useMutation({
    mutationFn: (id: string) => apiClient.post(`/messages/${id}/read`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['messages'] }),
  });

  const markAll = useMutation({
    mutationFn: () => apiClient.post('/messages/read-all'),
    onSuccess: () => {
      setStack({ cursor: null, messages: [] });
      qc.invalidateQueries({ queryKey: ['messages'] });
      qc.invalidateQueries({ queryKey: ['messages-unread'] });
    },
  });

  const open = (m: Message) => {
    if (!m.read) markRead.mutate(m.id);
    if (m.deeplink) navigate(m.deeplink);
  };

  const loadMore = () => {
    if (!data?.next_cursor) return;
    setStack({ cursor: data.next_cursor, messages: merged });
  };

  const resetPaging = () => setStack({ cursor: null, messages: [] });

  return (
    <div style={{ maxWidth: 760 }}>
      <h1>🔔 通知中心</h1>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 14 }}>
          {t('messages.unread')} <b style={{ color: 'var(--color-error)' }}>{data?.unread_count ?? 0}</b>
        </span>
        <label style={{ fontSize: 14 }}>
          <input
            type="checkbox"
            checked={unreadOnly}
            onChange={(e) => {
              setUnreadOnly(e.target.checked);
              resetPaging();
            }}
          />{t('messages.unreadOnly')}
        </label>
        <button
          onClick={() => markAll.mutate()}
          disabled={markAll.isPending || (data?.unread_count ?? 0) === 0}
          style={btnStyle}
        >
          全部已读
        </button>
      </div>

      <details style={{ marginBottom: 14, fontSize: 13 }}>
        <summary style={{ cursor: 'pointer' }}>通知偏好（免打扰 / 渠道）</summary>
        <div style={{ padding: '10px 0', display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ opacity: 0.7 }}>关闭后该类型不再发送站外渠道通知（站内仍留档）。</div>
          {TYPE_TABS.filter(t => t.key).map(t => (
            <label key={t.key} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <input
                type="checkbox"
                role="switch"
                aria-label={`${t.label} 站外通知`}
                checked={isEnabled(t.key)}
                onChange={() => toggle(t.key)}
              />
              {t.label}
            </label>
          ))}
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 6 }}>
            免打扰
            <input
              type="time"
              value={(notifyPrefs.quietHours?.[0]) ?? '22:00'}
              onChange={e => setQuiet(0, e.target.value)}
            />
            —
            <input
              type="time"
              value={(notifyPrefs.quietHours?.[1]) ?? '08:00'}
              onChange={e => setQuiet(1, e.target.value)}
            />
          </div>
          <div>
            <button onClick={savePrefs} style={btnStyle}>保存偏好</button>
            {prefsMsg && <span style={{ marginLeft: 10, opacity: 0.8 }}>{prefsMsg}</span>}
          </div>
        </div>
      </details>

      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 14 }}>
        {TYPE_TABS.map(tab => (
          <button
            key={tab.key || 'all'}
            onClick={() => {
              setType(tab.key);
              resetPaging();
            }}
            style={{
              ...btnStyle,
              padding: '4px 10px',
              fontSize: 12,
              background: type === tab.key ? 'var(--color-primary-500)' : 'var(--color-bg-secondary)',
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {isLoading && stack.messages.length === 0 ? <p>{t('common.loading')}</p> : null}
      {merged.length === 0 && !isLoading ? (
        <EmptyState title={t('messages.emptyTitle')} description={t('messages.emptyDescription')} />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {merged.map((m) => (
            <div
              role="button"
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  open(m);
                }
              }}
              key={m.id}
              onClick={() => open(m)}
              style={{
                padding: 12,
                background: m.read ? 'var(--color-bg-secondary)' : 'rgba(245,158,11,0.12)',
                borderRadius: 8,
                boxShadow: '0 1px 2px rgba(0,0,0,0.05)',
                cursor: 'pointer',
                borderLeft: `3px solid ${priorityColor(m.priority)}`,
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                <strong>
                  {m.title}
                  {(m.digest_count ?? 1) > 1 && (
                    <span style={{ marginLeft: 8, fontSize: 11, color: 'var(--color-error)' }}>
                      ×{m.digest_count}
                    </span>
                  )}
                </strong>
                <small style={{ color: 'var(--color-text-disabled)' }}>
                  {new Date(m.created_at).toLocaleString()}
                </small>
              </div>
              <div style={{ marginTop: 4, color: 'var(--color-text-secondary)' }}>{m.body}</div>
              <div style={{ marginTop: 4, display: 'flex', gap: 8, fontSize: 11, opacity: 0.6 }}>
                <span>{m.type}</span>
                {m.deeplink && <span>{m.deeplink}</span>}
                {!m.read && <span style={{ color: 'var(--color-error)' }}>{t('messages.unreadLabel')}</span>}
              </div>
            </div>
          ))}
        </div>
      )}
      {data?.has_more && (
        <button onClick={loadMore} style={{ marginTop: 12, ...btnStyle }}>
          {t('messages.loadMore', { count: merged.length })}
        </button>
      )}
    </div>
  );
}

function priorityColor(priority?: string): string {
  if (priority === 'HIGH') return 'var(--color-error)';
  if (priority === 'LOW') return 'var(--color-border-light)';
  return 'var(--color-primary-500)';
}

const btnStyle = {
  padding: '8px 16px',
  background: 'var(--color-bg-secondary)',
  color: 'var(--color-text-primary)',
  border: 'none',
  borderRadius: 4,
  cursor: 'pointer',
};
