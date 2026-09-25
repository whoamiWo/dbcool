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
  listKb: () => apiClient.get('/wiki/kb'),
  getKb: (id: string) => apiClient.get(`/wiki/kb/${id}`),
  createKb: (body: { name: string; slug: string; description?: string; icon?: string }) =>
    apiClient.post('/wiki/kb', body),
  updateKb: (id: string, body: { name?: string; slug?: string; description?: string; icon?: string }) =>
    apiClient.put(`/wiki/kb/${id}`, body),
  deleteKb: (id: string) => apiClient.delete(`/wiki/kb/${id}`),

  // 文档页面
  listPages: (params?: { kbId?: string; status?: string; page?: number; size?: number }) =>
    apiClient.get('/wiki/pages', { params }),
  getPage: (id: string) => apiClient.get(`/wiki/pages/${id}`),
  // P0 修复：按 slug 取页面（端点必须在 /pages/{id} 之前以避免 Spring 路由冲突）
  getPageBySlug: (slug: string) => apiClient.get(`/wiki/pages/by-slug/${slug}`),
  createPage: (body: { knowledge_base_id: string; slug: string; title: string; content?: string; parent_id?: string }) =>
    apiClient.post('/wiki/pages', body),
  updatePage: (id: string, body: { title?: string; content?: string; slug?: string }) =>
    apiClient.put(`/wiki/pages/${id}`, body),
  deletePage: (id: string) => apiClient.delete(`/wiki/pages/${id}`),
  publishPage: (id: string) => apiClient.post(`/wiki/pages/${id}/publish`),
  archivePage: (id: string) => apiClient.post(`/wiki/pages/${id}/archive`),

  // 版本
  listVersions: (id: string) => apiClient.get(`/wiki/pages/${id}/versions`),
  getVersion: (id: string, version: number) =>
    apiClient.get(`/wiki/pages/${id}/versions/${version}`),
  restoreVersion: (id: string, version: number) =>
    apiClient.post(`/wiki/pages/${id}/versions/${version}/restore`),

  // 分类
  listCategories: (kbId?: string) =>
    kbId
      ? apiClient.get(`/wiki/kb/${kbId}/categories`)
      : apiClient.get('/wiki/categories'),
  createCategory: (body: { name: string; slug: string; parent_id?: string; knowledge_base_id?: string }) =>
    apiClient.post('/wiki/categories', body),
  updateCategory: (id: string, body: { name?: string; slug?: string; parent_id?: string }) =>
    apiClient.put(`/wiki/categories/${id}`, body),
  deleteCategory: (id: string) => apiClient.delete(`/wiki/categories/${id}`),

  // 搜索
  search: (q: string, params?: { kbId?: string; page?: number; size?: number }) =>
    apiClient.get('/wiki/search', { params: { q, ...params } }),

  // Block（Notion 式块级内容）
  listBlocks: (pageId: string) => apiClient.get(`/wiki/pages/${pageId}/blocks`),
  createBlocks: (pageId: string, blocks: Array<{ type: string; content: any }>) =>
    apiClient.post(`/wiki/pages/${pageId}/blocks`, blocks),
  updateBlock: (blockId: string, body: { parent_id?: string; sort_order?: number }) =>
    apiClient.put(`/wiki/blocks/${blockId}`, body),
  deleteBlock: (blockId: string) => apiClient.delete(`/wiki/blocks/${blockId}`),
  reorderBlocks: (pageId: string, blockIds: string[]) =>
    apiClient.put(`/wiki/pages/${pageId}/blocks/reorder`, blockIds),
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