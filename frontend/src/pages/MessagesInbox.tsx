import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';

interface Message {
  id: string;
  type: string;
  title: string;
  body: string;
  related_id: string;
  read: boolean;
  created_at: string;
}

interface InboxResp {
  unread_count: number;
  messages: Message[];
  limit: number;
  next_cursor: string;
  has_more: boolean;
}

/**
 * 站内信 inbox(US-505/506,Week 12 + Week 13 cursor 分页).
 */
export function MessagesInboxPage() {
  const qc = useQueryClient();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [stack, setStack] = useState<{ cursor: string | null; messages: Message[] }>({
    cursor: null,
    messages: [],
  });

  const { data, isLoading } = useQuery({
    queryKey: ['messages', unreadOnly, stack.cursor],
    queryFn: () => {
      const p = new URLSearchParams();
      p.set('unreadOnly', String(unreadOnly));
      p.set('limit', '20');
      if (stack.cursor) p.set('before', stack.cursor);
      return apiClient.get<InboxResp>('/messages?' + p.toString());
    },
  });

  const merged: Message[] = [
    ...stack.messages,
    ...(data?.messages ?? []),
  ];

  const markRead = useMutation({
    mutationFn: (id: string) => apiClient.post(`/messages/${id}/read`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['messages'] }),
  });

  const loadMore = () => {
    if (!data?.next_cursor) return;
    setStack({ cursor: data.next_cursor, messages: merged });
  };

  return (
    <div style={{ maxWidth: 700 }}>
      <h1>📬 站内信</h1>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
        <span style={{ fontSize: 14, color: '#475569' }}>
          未读 <b style={{ color: '#dc2626' }}>{data?.unread_count ?? 0}</b>
        </span>
        <label style={{ fontSize: 14 }}>
          <input
            type="checkbox"
            checked={unreadOnly}
            onChange={(e) => {
              setUnreadOnly(e.target.checked);
              setStack({ cursor: null, messages: [] });
            }}
          /> 只看未读
        </label>
      </div>
      {isLoading && stack.messages.length === 0 ? <p>加载中…</p> : null}
      {merged.length === 0 && !isLoading ? (
        <p style={{ color: '#64748b' }}>暂无消息</p>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {merged.map((m) => (
            <div
              key={m.id}
              onClick={() => !m.read && markRead.mutate(m.id)}
              style={{
                padding: 12,
                background: m.read ? 'white' : '#fef9c3',
                borderRadius: 8,
                boxShadow: '0 1px 2px rgba(0,0,0,0.05)',
                cursor: m.read ? 'default' : 'pointer',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <strong>{m.title}</strong>
                <small style={{ color: '#64748b' }}>
                  {new Date(m.created_at).toLocaleString()}
                </small>
              </div>
              <div style={{ marginTop: 4, color: '#475569' }}>{m.body}</div>
              {!m.read && <small style={{ color: '#dc2626' }}>● 未读</small>}
            </div>
          ))}
        </div>
      )}
      {data?.has_more && (
        <button onClick={loadMore} style={{ marginTop: 12, ...btnStyle }}>
          加载更多(已加载 {merged.length} 条)
        </button>
      )}
    </div>
  );
}

const btnStyle = {
  padding: '8px 16px',
  background: '#1e293b',
  color: 'white',
  border: 'none',
  borderRadius: 4,
  cursor: 'pointer',
};
