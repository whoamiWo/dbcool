import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { wikiApi } from '@/api/wiki';
import { useWikiLinkAutocomplete, detectWikiLinkTrigger, buildWikiLink } from './useWikiLinkAutocomplete';

vi.mock('@/api/wiki', () => ({
  wikiApi: {
    search: vi.fn(),
  },
  unwrapEnvelope: (data: any) => data,
}));

describe('detectWikiLinkTrigger', () => {
  it('识别基本触发 [[', () => {
    const trigger = detectWikiLinkTrigger('Hello [[world', 13);
    expect(trigger).toEqual({ start: 6, end: 13, query: 'world' });
  });

  it('忽略已闭合的链接', () => {
    // '[[foo]] [[bar' → 第二组 [[ 位于下标 8，光标 13
    const trigger = detectWikiLinkTrigger('[[foo]] [[bar', 13);
    expect(trigger).toEqual({ start: 8, end: 13, query: 'bar' });
  });

  it('遇到 ] 则终止', () => {
    const trigger = detectWikiLinkTrigger('[[foo] bar', 10);
    expect(trigger).toBeNull();
  });

  it('遇到换行则终止', () => {
    const trigger = detectWikiLinkTrigger('[[foo\nbar', 9);
    expect(trigger).toBeNull();
  });

  it('排除 [[[ 情况', () => {
    const trigger = detectWikiLinkTrigger('[[[foo', 6);
    expect(trigger).toBeNull();
  });

  it('限制回溯窗口长度', () => {
    const longPrefix = 'a'.repeat(100);
    const trigger = detectWikiLinkTrigger(`${longPrefix}[[test`, longPrefix.length + 8);
    expect(trigger).toBeNull(); // 超出 64 字符窗口
  });
});

describe('buildWikiLink', () => {
  it('相同标题与 slug 时输出 [[slug]]', () => {
    expect(buildWikiLink({ id: '1', title: 'foo', slug: 'foo' })).toBe('[[foo]]');
  });

  it('不同标题与 slug 时输出 [[slug|title]]', () => {
    expect(buildWikiLink({ id: '1', title: 'Foo Bar', slug: 'foo-bar' })).toBe('[[foo-bar|Foo Bar]]');
  });

  it('无 slug 时使用 title', () => {
    expect(buildWikiLink({ id: '1', title: 'Untitled', slug: '' })).toBe('[[Untitled]]');
  });
});

describe('useWikiLinkAutocomplete', () => {
  let mockSearch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockSearch = vi.fn();
    vi.mocked(wikiApi.search).mockImplementation(mockSearch);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('初始状态为空', () => {
    const { result } = renderHook(() => useWikiLinkAutocomplete({ debounceMs: 0 }));
    expect(result.current.candidates).toEqual([]);
    expect(result.current.loading).toBe(false);
    expect(result.current.open).toBe(false);
  });

  it('检测到触发后加载候选', async () => {
    mockSearch.mockResolvedValue([
      { id: '1', title: 'React 教程', slug: 'react-tutorial' },
      { id: '2', title: 'Vue 指南', slug: 'vue-guide' },
    ]);

    const { result } = renderHook(() => useWikiLinkAutocomplete({ debounceMs: 0 }));

    await act(async () => {
      result.current.detect({ start: 0, end: 2, query: 're' });
    });

    expect(result.current.loading).toBe(true);
    await vi.waitFor(() => {
      expect(result.current.candidates.length).toBe(2);
      expect(result.current.open).toBe(true);
    });

    expect(result.current.activeIndex).toBe(0);
  });

  it('竞态：仅采纳最后一次请求结果', async () => {
    // 第一次请求挂起不返回，第二次请求立即返回 Second；
    // 随后让第一次请求迟到返回 First，必须被 seq 守卫丢弃。
    let resolveFirst: (v: unknown) => void = () => {};
    mockSearch
      .mockImplementationOnce(
        () => new Promise((resolve) => { resolveFirst = resolve; }),
      )
      .mockResolvedValueOnce([{ id: '2', title: 'Second', slug: 'second' }]);

    const { result } = renderHook(() => useWikiLinkAutocomplete({ debounceMs: 0 }));

    // 触发第一次：等 effect 跑完并真正调用 mockSearch
    await act(async () => {
      result.current.detect({ start: 0, end: 2, query: 'q1' });
    });
    await vi.waitFor(() => expect(mockSearch).toHaveBeenCalledTimes(1));

    // 触发第二次：第二个请求发出并返回 Second
    await act(async () => {
      result.current.detect({ start: 0, end: 2, query: 'q2' });
    });
    await vi.waitFor(() => expect(mockSearch).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(result.current.candidates[0]?.title).toBe('Second'));

    // 第一次请求迟到返回 First —— 必须被 seq 守卫丢弃
    await act(async () => {
      resolveFirst([{ id: '1', title: 'First', slug: 'first' }]);
    });
    await new Promise((r) => setTimeout(r, 0));

    expect(result.current.candidates[0]?.title).toBe('Second');
  });

  it('键盘导航循环移动', async () => {
    mockSearch.mockResolvedValue([
      { id: '1', title: 'A', slug: 'a' },
      { id: '2', title: 'B', slug: 'b' },
      { id: '3', title: 'C', slug: 'c' },
    ]);

    const { result } = renderHook(() => useWikiLinkAutocomplete({ debounceMs: 0 }));

    await act(async () => {
      result.current.detect({ start: 0, end: 2, query: 'x' });
    });

    await vi.waitFor(() => expect(result.current.candidates.length).toBe(3));

    act(() => {
      result.current.moveActive(1);
    });
    expect(result.current.activeIndex).toBe(1);

    act(() => {
      result.current.moveActive(1);
    });
    expect(result.current.activeIndex).toBe(2);

    act(() => {
      result.current.moveActive(1);
    });
    expect(result.current.activeIndex).toBe(0); // 循环回首
  });

  it('关闭后清空所有状态', async () => {
    mockSearch.mockResolvedValue([{ id: '1', title: 'X', slug: 'x' }]);

    const { result } = renderHook(() => useWikiLinkAutocomplete({ debounceMs: 0 }));

    await act(async () => {
      result.current.detect({ start: 0, end: 2, query: 'q' });
    });

    await vi.waitFor(() => expect(result.current.candidates.length).toBe(1));

    act(() => {
      result.current.close();
    });

    expect(result.current.trigger).toBeNull();
    expect(result.current.candidates).toEqual([]);
    expect(result.current.loading).toBe(false);
    expect(result.current.open).toBe(false);
  });

  it('禁用时不触发搜索', async () => {
    const { result } = renderHook(() => useWikiLinkAutocomplete({ enabled: false, debounceMs: 0 }));

    await act(async () => {
      result.current.detect({ start: 0, end: 2, query: 'q' });
    });

    expect(mockSearch).not.toHaveBeenCalled();
    expect(result.current.candidates).toEqual([]);
  });
});
