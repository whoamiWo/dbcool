import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { searchApi, SEARCH_FACET_LABELS, type SearchResult } from '@/api/search';

const TYPES = ['', 'wiki', 'record', 'im', 'project', 'automation'];

/**
 * 全局搜索结果页（E4）。
 *
 * <p>入口来自顶栏搜索框的「查看全部」。结果按类型分组；
 * 点击优先使用后端 {@code deeplink}（保证与权限判定同源）。
 */
export function SearchResultsPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const q = params.get('q') ?? '';
  const [type, setType] = useState(params.get('type') ?? '');

  const { data, isLoading, error } = useQuery({
    queryKey: ['search-results', q, type],
    queryFn: () => searchApi.search(q, type ? [type] : undefined, 50),
    enabled: q.trim().length > 0,
  });

  const results = useMemo<SearchResult[]>(
    () => data?.data?.results ?? [],
    [data],
  );
  const facet = data?.data?.facets;

  const grouped = useMemo(() => {
    const map = new Map<string, SearchResult[]>();
    for (const r of results) {
      const list = map.get(r.type) ?? [];
      list.push(r);
      map.set(r.type, list);
    }
    return map;
  }, [results]);

  const open = (r: SearchResult) => {
    if (r.deeplink) {
      navigate(r.deeplink);
      return;
    }
    if (r.type === 'wiki') navigate(`/wiki/${r.slug ?? r.id}`);
    else if (r.type === 'im') navigate('/im');
    else if (r.type === 'project') navigate('/projects');
    else if (r.type === 'automation') navigate('/designer/workflows');
  };

  return (
    <div style={{ padding: 20, maxWidth: 900 }}>
      <h1 style={{ fontSize: 20, marginBottom: 12 }}>搜索</h1>
      <input
        defaultValue={q}
        onKeyDown={e => {
          if (e.key === 'Enter') {
            const next = (e.target as HTMLInputElement).value;
            setParams(next ? { q: next } : {});
          }
        }}
        placeholder="输入关键词后回车"
        style={{ width: '100%', maxWidth: 480, padding: '8px 12px', borderRadius: 6, border: '1px solid var(--color-border-light)' }}
      />

      <div style={{ display: 'flex', gap: 6, margin: '14px 0', flexWrap: 'wrap' }}>
        {TYPES.map(tp => (
          <button
            key={tp || 'all'}
            onClick={() => {
              setType(tp);
              const next: Record<string, string> = { q };
              if (tp) next.type = tp;
              setParams(next);
            }}
            style={{
              padding: '4px 10px',
              fontSize: 12,
              borderRadius: 4,
              cursor: 'pointer',
              border: '1px solid var(--color-border-light)',
              background: type === tp ? 'var(--color-primary-500)' : 'transparent',
              color: 'var(--color-text-primary)',
            }}
          >
            {tp ? SEARCH_FACET_LABELS[tp] ?? tp : '全部'}
            {facet && tp && facet[tp as keyof typeof facet] ? ` (${facet[tp as keyof typeof facet]})` : ''}
          </button>
        ))}
      </div>

      {!q && <div style={{ opacity: 0.6 }}>请输入关键词</div>}
      {isLoading && <div>搜索中…</div>}
      {error && <div style={{ color: 'var(--color-error)' }}>搜索失败</div>}
      {q && !isLoading && results.length === 0 && (
        <div style={{ opacity: 0.6 }}>没有找到匹配结果（无权访问的内容不会出现在这里）</div>
      )}

      {[...grouped.entries()].map(([tp, list]) => (
        <section key={tp} style={{ marginBottom: 20 }}>
          <h3 style={{ fontSize: 14, opacity: 0.8 }}>
            {SEARCH_FACET_LABELS[tp] ?? tp} · {list.length}
          </h3>
          {list.map(r => (
            <div
              key={`${r.type}-${r.id}`}
              role="button"
              tabIndex={0}
              onClick={() => open(r)}
              onKeyDown={e => {
                if (e.key === 'Enter') open(r);
              }}
              style={{
                padding: 10,
                borderBottom: '1px solid var(--color-border-light)',
                cursor: 'pointer',
              }}
            >
              <div style={{ fontWeight: 600 }}>{r.title || '（无标题）'}</div>
              {r.snippet && (
                // 不用 dangerouslySetInnerHTML：ts_headline 返回的高亮标记外，
                // 记录正文可能含用户输入的 HTML，直接注入会形成 XSS。
                <div style={{ fontSize: 12, opacity: 0.7, marginTop: 2 }}>
                  {String(r.snippet).replace(/<[^>]*>/g, '')}
                </div>
              )}
            </div>
          ))}
        </section>
      ))}
    </div>
  );
}
