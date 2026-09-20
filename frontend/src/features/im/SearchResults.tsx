import { useEffect, useState } from 'react';
import { searchCrossChannel, type MessagePage } from './api';

interface SearchResultsProps {
  keyword: string;
  limit?: number;
  onMessageClick?: (messageId: string, channelId: string) => void;
}

export function SearchResults({ keyword, limit = 20, onMessageClick }: SearchResultsProps) {
  const [results, setResults] = useState<MessagePage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!keyword.trim()) {
      setResults(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    searchCrossChannel(keyword.trim(), limit)
      .then((res) => {
        if (!cancelled) setResults(res.data);
      })
      .catch((err) => {
        if (!cancelled) setError(String(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, limit]);

  if (!keyword.trim()) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: 'var(--color-text-muted)', fontSize: 13 }}>
        请输入关键词搜索
      </div>
    );
  }

  return (
    <div>
      {loading && (
        <div style={{ padding: '16px', textAlign: 'center', color: 'var(--color-text-muted)', fontSize: 13 }}>
          搜索中...
        </div>
      )}
      {error && (
        <div style={{ padding: '16px', textAlign: 'center', color: 'var(--color-error)', fontSize: 13 }}>
          搜索失败：{error}
        </div>
      )}
      {!loading && results?.messages?.length === 0 && (
        <div style={{ padding: '16px', textAlign: 'center', color: 'var(--color-text-muted)', fontSize: 13 }}>
          未找到匹配「{keyword}」的消息
        </div>
      )}
      {!loading && results && results.messages.length > 0 && (
        <div>
          <div style={{ padding: '8px 12px', fontSize: 12, color: 'var(--color-text-muted)', borderBottom: '1px solid rgba(255,255,255,0.1)' }}>
            共 {results.messages.length} 条结果
            {results.has_more && '（还有更多）'}
          </div>
          {results.messages.map((msg) => {
            const highlight = keyword
              ? msg.content.replace(new RegExp(keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi'), (m) => `<mark style="background:rgba(245,158,11,0.3);color:var(--color-warning)">${m}</mark>`)
              : msg.content;
            return (
              <div
                key={msg.id}
                className="glass-light"
                style={{
                  padding: '10px 12px',
                  cursor: 'pointer',
                  borderRadius: 'var(--radius-sm)',
                  transition: 'all var(--transition-fast)',
                }}
                onClick={() => onMessageClick?.(msg.id, msg.channelId)}
                onMouseEnter={(e) => { (e.currentTarget as HTMLDivElement).style.background = 'rgba(255,255,255,0.08)'; }}
                onMouseLeave={(e) => { (e.currentTarget as HTMLDivElement).style.background = 'rgba(255,255,255,0.05)'; }}
              >
                <div style={{ fontSize: 11, color: 'var(--color-text-muted)', marginBottom: 4 }}>
                  频道：{msg.channelId.slice(-6)} · {new Date(msg.createdAt).toLocaleString('zh-CN')}
                </div>
                <div
                  style={{ fontSize: 13, color: 'var(--color-text-primary)' }}
                  dangerouslySetInnerHTML={{ __html: highlight }}
                />
              </div>
            );
          })}
          {results.has_more && (
            <div style={{ padding: 12, textAlign: 'center' }}>
              <button
                className="glass-button"
                style={{ padding: '6px 16px', fontSize: 12 }}
              >
                加载更多
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
