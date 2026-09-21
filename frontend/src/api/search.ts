import apiClient from './client';

export interface SearchResult {
  type: 'wiki' | 'record' | 'im' | 'project' | 'automation';
  id: string;
  title: string;
  snippet: string;
  createdAt: string;
  [key: string]: unknown;
}

export interface SearchResponse {
  code: number;
  message: string;
  data: {
    keyword: string;
    total: number;
    results: SearchResult[];
    facets: {
      wiki: number;
      record: number;
      im: number;
      project: number;
      automation: number;
    };
  };
}

export interface SearchFacet {
  label: string;
  count: number;
  color: string;
}

export const searchApi = {
  /** 全局搜索 — 支持 types: wiki/record/im/project/automation */
  search: (keyword: string, types?: string[], limit = 20): Promise<SearchResponse> =>
    apiClient.get<SearchResponse>('/search', {
      params: { keyword, types: types ? types.join(',') : undefined, limit }
    }),

  /** 搜索结果按类型分组 */
  searchByFacets: (keyword: string, limit = 20): Promise<{ keyword: string; total: number; results: SearchResult[]; facets: Record<string, number> }> =>
    apiClient.get<{ keyword: string; total: number; results: SearchResult[]; facets: Record<string, number> }>('/search', {
      params: { keyword, limit }
    }),
};

/** 设计令牌到搜索结果的映射 */
export const SEARCH_FACET_COLORS: Record<string, string> = {
  wiki: 'var(--color-primary-500)',
  record: 'var(--color-info-500)',
  im: 'var(--color-secondary-500)',
  project: 'var(--color-success-500)',
  automation: 'var(--color-warning-500)',
};

export const SEARCH_FACET_LABELS: Record<string, string> = {
  wiki: '文档',
  record: '数据',
  im: '消息',
  project: '任务',
  automation: '流程',
};