/**
 * PHASE 4.2 覆盖率补测：api/search.ts
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('./client', () => ({
  default: {
    get: vi.fn(),
  },
}));

import apiClient from './client';
import { searchApi, SEARCH_FACET_COLORS, SEARCH_FACET_LABELS } from './search';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('searchApi', () => {
  it('search 调用 /search 并传递参数', async () => {
    apiClient.get.mockResolvedValue({
      code: 0,
      message: 'ok',
      data: {
        keyword: 'test',
        total: 1,
        results: [],
        facets: { wiki: 0, record: 1, im: 0, project: 0, automation: 0 },
      },
    });
    await searchApi.search('test', ['record'], 10);
    expect(apiClient.get).toHaveBeenCalledWith('/search', {
      params: { keyword: 'test', types: 'record', limit: 10 },
    });
  });

  it('searchByFacets 调用 /search 但不传 types', async () => {
    apiClient.get.mockResolvedValue({
      keyword: 'test',
      total: 0,
      results: [],
      facets: { wiki: 0, record: 0, im: 0, project: 0, automation: 0 },
    });
    await searchApi.searchByFacets('test', 5);
    expect(apiClient.get).toHaveBeenCalledWith('/search', {
      params: { keyword: 'test', limit: 5 },
    });
  });
});

describe('SEARCH_FACET_COLORS', () => {
  it('每个类型都有 CSS 变量颜色', () => {
    expect(SEARCH_FACET_COLORS.wiki).toBe('var(--color-primary-500)');
    expect(SEARCH_FACET_COLORS.record).toBe('var(--color-info-500)');
    expect(SEARCH_FACET_COLORS.im).toBe('var(--color-secondary-500)');
    expect(SEARCH_FACET_COLORS.project).toBe('var(--color-success-500)');
    expect(SEARCH_FACET_COLORS.automation).toBe('var(--color-warning-500)');
  });
});

describe('SEARCH_FACET_LABELS', () => {
  it('中文标签映射正确', () => {
    expect(SEARCH_FACET_LABELS.wiki).toBe('文档');
    expect(SEARCH_FACET_LABELS.record).toBe('数据');
    expect(SEARCH_FACET_LABELS.im).toBe('消息');
    expect(SEARCH_FACET_LABELS.project).toBe('任务');
    expect(SEARCH_FACET_LABELS.automation).toBe('流程');
  });
});
