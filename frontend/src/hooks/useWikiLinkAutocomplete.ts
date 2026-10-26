import { useCallback, useEffect, useRef, useState } from 'react';
import { wikiApi, unwrapEnvelope } from '@/api/wiki';

/** 补全候选项 */
export interface WikiLinkCandidate {
  id: string;
  title: string;
  slug: string;
}

/** 补全触发状态 */
export interface WikiLinkTrigger {
  /** `[[` 起始位置（`[` 的下标） */
  start: number;
  /** 光标位置 */
  end: number;
  /** `[[` 之后已输入的查询串 */
  query: string;
}

export interface UseWikiLinkAutocompleteOptions {
  /** 关联知识库，限定搜索范围 */
  kbId?: string;
  /** 防抖延迟（毫秒） */
  debounceMs?: number;
  /** 单次返回的最大候选数 */
  limit?: number;
  /** 是否启用 */
  enabled?: boolean;
}

export interface UseWikiLinkAutocompleteResult {
  /** 当前候选列表 */
  candidates: WikiLinkCandidate[];
  /** 是否正在加载 */
  loading: boolean;
  /** 高亮索引 */
  activeIndex: number;
  /** 设置高亮索引（自动夹紧到合法范围） */
  setActiveIndex: (index: number) => void;
  /** 上下移动高亮，循环 */
  moveActive: (delta: number) => void;
  /** 是否应展示浮层 */
  open: boolean;
  /** 关闭浮层并清空候选 */
  close: () => void;
}

/**
 * 从文本与光标位置推导 `[[` 触发状态。
 *
 * <p>规则：从光标向前查找最近的 `[[`，且二者之间不得出现 `]` 或换行；
 * 同时该 `[[` 之前不能是 `[`（避免 `[[[` 误判）。
 *
 * @param text 完整文本
 * @param caret 光标下标
 * @returns 触发状态；未触发返回 null
 */
export function detectWikiLinkTrigger(text: string, caret: number): WikiLinkTrigger | null {
  if (caret < 2 || caret > text.length) return null;

  // 从光标向左最多回溯 64 字符，避免长文本全量扫描
  const floor = Math.max(0, caret - 64);
  const window = text.slice(floor, caret);
  const idx = window.lastIndexOf('[[');
  if (idx === -1) return null;

  const start = floor + idx;
  const between = text.slice(start + 2, caret);
  // 中间出现闭合符或换行则视为已结束
  if (/[\]\n]/.test(between)) return null;
  // 排除 `[[[` 这类三连方括号
  if (start > 0 && text[start - 1] === '[') return null;

  return { start, end: caret, query: between };
}

/**
 * 构造插入到正文的 Wiki 链接片段。
 *
 * <p>当显示文本与 slug 相同时输出 `[[slug]]`，否则输出 `[[slug|title]]`。
 */
export function buildWikiLink(candidate: WikiLinkCandidate): string {
  const slug = candidate.slug || candidate.title;
  return candidate.title && candidate.title !== slug
    ? `[[${slug}|${candidate.title}]]`
    : `[[${slug}]]`;
}

/**
 * Wiki 双向链接自动补全 Hook。
 *
 * <p>职责：接收当前文本与光标位置，检测 `[[` 触发，防抖调用搜索接口，
 * 并处理竞态（仅采纳最后一次请求的结果）与键盘导航状态。
 *
 * <p>用法：由编辑器在每次输入/光标变化时调用 {@link detect}，
 * 把非空结果交给本 Hook 驱动浮层渲染。
 */
export function useWikiLinkAutocomplete(options: UseWikiLinkAutocompleteOptions = {}) {
  const { kbId, debounceMs = 220, limit = 8, enabled = true } = options;

  const [trigger, setTrigger] = useState<WikiLinkTrigger | null>(null);
  const [candidates, setCandidates] = useState<WikiLinkCandidate[]>([]);
  const [loading, setLoading] = useState(false);
  const [activeIndex, setActiveIndexRaw] = useState(0);

  /** 竞态守卫：只接受最新一次请求的响应 */
  const requestSeq = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearTimer = useCallback(() => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const close = useCallback(() => {
    clearTimer();
    requestSeq.current += 1; // 作废在途请求
    setTrigger(null);
    setCandidates([]);
    setLoading(false);
    setActiveIndexRaw(0);
  }, [clearTimer]);

  /** 由编辑器调用：更新触发状态；传 null 表示关闭 */
  const detect = useCallback(
    (next: WikiLinkTrigger | null) => {
      if (!enabled || !next) {
        close();
        return;
      }
      setTrigger(next);
    },
    [close, enabled],
  );

  // 触发状态变化后，防抖拉取候选
  useEffect(() => {
    if (!trigger) return;

    const seq = ++requestSeq.current;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 进入防抖等待即置 loading，属预期交互反馈
    setLoading(true);
    clearTimer();

    timerRef.current = setTimeout(async () => {
      try {
        const raw = await wikiApi.search(trigger.query, {
          kbId,
          page: 1,
          size: limit,
        });
        if (seq !== requestSeq.current) return; // 已有更新的请求

        const rows = unwrapEnvelope<Array<Partial<WikiLinkCandidate>>>(raw) ?? [];
        const list = (Array.isArray(rows) ? rows : [])
          .filter((r) => r && (r.slug || r.title))
          .slice(0, limit)
          .map((r) => ({
            id: String(r.id ?? r.slug ?? r.title),
            title: String(r.title ?? r.slug ?? ''),
            slug: String(r.slug ?? r.title ?? ''),
          }));

        setCandidates(list);
        setActiveIndexRaw((prev) => (prev >= list.length ? 0 : prev));
      } catch {
        if (seq === requestSeq.current) setCandidates([]);
      } finally {
        if (seq === requestSeq.current) setLoading(false);
      }
    }, debounceMs);

    return clearTimer;
  }, [trigger, kbId, limit, debounceMs, clearTimer]);

  // 卸载时清理计时器，防止内存泄漏
  useEffect(() => () => clearTimer(), [clearTimer]);

  const setActiveIndex = useCallback(
    (index: number) => {
      setActiveIndexRaw(() => {
        if (candidates.length === 0) return 0;
        const clamped = Math.max(0, Math.min(index, candidates.length - 1));
        return clamped;
      });
    },
    [candidates.length],
  );

  const moveActive = useCallback(
    (delta: number) => {
      setActiveIndexRaw((prev) => {
        const n = candidates.length;
        if (n === 0) return 0;
        return ((prev + delta) % n + n) % n;
      });
    },
    [candidates.length],
  );

  const open = !!trigger && (loading || candidates.length > 0);

  return {
    trigger,
    candidates,
    loading,
    activeIndex,
    setActiveIndex,
    moveActive,
    open,
    close,
    detect,
    activeCandidate: candidates[activeIndex] as WikiLinkCandidate | undefined,
  };
}
