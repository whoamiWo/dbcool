import { useEffect, useState } from 'react';
import { searchCrossChannel, type MessagePage } from './api';

interface SearchResultsProps {
  keyword: string;
  limit?: number;
  onMessageClick?: (messageId: string) => void;
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
      <div style={{ padding: 24, textAlign: 'center', color: '#94a3b8', fontSize: 13 }}>
        请输入关键词搜索
      </div>
    );
  }

  return (
    <div>
      {loading && (
        <div style={{ padding: '16px', textAlign: 'center', color: '#64748b', fontSize: 13 }}>
          搜索中...
        </div>
      )}
      {error && (
        <div style={{ padding: '16px', textAlign: 'center', color: '#ef4444', fontSize: 13 }}>
          搜索失败：{error}
        </div>
      )}
      {!loading && results?.messages?.length === 0 && (
        <div style={{ padding: '16px', textAlign: 'center', color: '#94a3b8', fontSize: 13 }}>
          未找到匹配「{keyword}」的消息
        </div>
      )}
      {!loading && results && results.messages.length > 0 && (
        <div>
          <div style={{ padding: '8px 12px', fontSize: 12, color: '#64748b', borderBottom: '1px solid #e2e8f0' }}>
            共 {results.messages.length} 条结果
            {results.has_more && '（还有更多）'}
          </div>
          {results.messages.map((msg) => {
            const highlight = keyword
              ? msg.content.replace(new RegExp(keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi'), (m) => `<mark style="background:#fef08a">${m}</mark>`)
              : msg.content;
            return (
              <div
                key={msg.id}
                onClick={() => onMessageClick?.(msg.id)}
                style={{
                  padding: '10px 12px',
                  borderBottom: '1px solid #f1f5f9',
                  cursor: 'pointer',
                }}
                onMouseEnter={(e) => { (e.currentTarget as HTMLDivElement).style.background = '#f8fafc'; }}
                onMouseLeave={(e) => { (e.currentTarget as HTMLDivElement).style.background = 'transparent'; }}
              >
                <div style={{ fontSize: 11, color: '#94a3b8', marginBottom: 4 }}>
                  频道：{msg.channelId.slice(-6)} · {new Date(msg.createdAt).toLocaleString('zh-CN')}
                </div>
                <div
                  style={{ fontSize: 13, color: '#374151' }}
                  dangerouslySetInnerHTML={{ __html: highlight }}
                />
              </div>
            );
          })}
          {results.has_more && (
            <div style={{ padding: 12, textAlign: 'center' }}>
              <button
                onClick={() => {
                  // 可扩展：加载更多结果
                }}
                style={{
                  padding: '6px 16px',
                  background: '#f1f5f9',
                  border: '1px solid #e2e8f0',
                  borderRadius: 6,
                  fontSize: 12,
                  cursor: 'pointer',
                  color: '#475569',
                }}
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
