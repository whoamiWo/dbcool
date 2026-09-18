import apiClient from './client';
import type {
  KnowledgeBase,
  WikiPage,
  WikiVersion,
  WikiCategory,
  SearchResult,
} from '@/types/wiki';

export interface Envelope<T> {
  code: number;
  message: string;
  data: T;
}

export interface ListResponse<T> {
  data: T[];
  total: number;
  page: number;
  size: number;
}

// 统一解包后端 envelope: {code, message, data} → data
export function unwrapEnvelope<T>(r: any): T {
  if (Array.isArray(r)) return r as unknown as T;
  if (r && typeof r === 'object') {
    // 后端 envelope: {code: 0, message: "success", data: T}
    if ('code' in r && 'data' in r) return r.data as T;
    // 直接返 T (兼容 vitest mock)
    return r as T;
  }
  return r as T;
}

export const wikiApi = {
  // 知识库
  listKb: () => apiClient.get('/api/wiki/kb'),
  getKb: (id: string) => apiClient.get(`/api/wiki/kb/${id}`),
  createKb: (body: { name: string; slug: string; description?: string; icon?: string }) =>
    apiClient.post('/api/wiki/kb', body),
  updateKb: (id: string, body: { name?: string; slug?: string; description?: string; icon?: string }) =>
    apiClient.put(`/api/wiki/kb/${id}`, body),
  deleteKb: (id: string) => apiClient.delete(`/api/wiki/kb/${id}`),

  // 文档页面
  listPages: (params?: { kbId?: string; status?: string; page?: number; size?: number }) =>
    apiClient.get('/api/wiki/pages', { params }),
  getPage: (id: string) => apiClient.get(`/api/wiki/pages/${id}`),
  createPage: (body: { knowledge_base_id: string; slug: string; title: string; content?: string; parent_id?: string }) =>
    apiClient.post('/api/wiki/pages', body),
  updatePage: (id: string, body: { title?: string; content?: string; slug?: string }) =>
    apiClient.put(`/api/wiki/pages/${id}`, body),
  deletePage: (id: string) => apiClient.delete(`/api/wiki/pages/${id}`),
  publishPage: (id: string) => apiClient.post(`/api/wiki/pages/${id}/publish`),
  archivePage: (id: string) => apiClient.post(`/api/wiki/pages/${id}/archive`),

  // 版本
  listVersions: (id: string) => apiClient.get(`/api/wiki/pages/${id}/versions`),
  getVersion: (id: string, version: number) =>
    apiClient.get(`/api/wiki/pages/${id}/versions/${version}`),
  restoreVersion: (id: string, version: number) =>
    apiClient.post(`/api/wiki/pages/${id}/versions/${version}/restore`),

  // 分类
  listCategories: (kbId?: string) =>
    kbId
      ? apiClient.get(`/api/wiki/kb/${kbId}/categories`)
      : apiClient.get('/api/wiki/categories'),
  createCategory: (body: { name: string; slug: string; parent_id?: string; knowledge_base_id?: string }) =>
    apiClient.post('/api/wiki/categories', body),
  updateCategory: (id: string, body: { name?: string; slug?: string; parent_id?: string }) =>
    apiClient.put(`/api/wiki/categories/${id}`, body),
  deleteCategory: (id: string) => apiClient.delete(`/api/wiki/categories/${id}`),

  // 搜索
  search: (q: string, params?: { kbId?: string; page?: number; size?: number }) =>
    apiClient.get('/api/wiki/search', { params: { q, ...params } }),
};

export function parseKnowledgeBase(r: any): KnowledgeBase {
  return unwrapEnvelope<KnowledgeBase>(r);
}
export function parseKnowledgeBaseList(r: any): KnowledgeBase[] {
  return unwrapEnvelope<KnowledgeBase[]>(r);
}
export function parseWikiPage(r: any): WikiPage {
  return unwrapEnvelope<WikiPage>(r);
}
export function parseWikiPageList(r: any): ListResponse<WikiPage> {
  return unwrapEnvelope<ListResponse<WikiPage>>(r);
}
export function parseWikiVersionList(r: any): WikiVersion[] {
  return unwrapEnvelope<WikiVersion[]>(r);
}
export function parseWikiCategoryList(r: any): WikiCategory[] {
  return unwrapEnvelope<WikiCategory[]>(r);
}
export function parseSearchResult(r: any): ListResponse<SearchResult> {
  return unwrapEnvelope<ListResponse<SearchResult>>(r);
}