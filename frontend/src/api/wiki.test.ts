/**
 * PHASE 4.2 覆盖率补测：api/wiki.ts
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('./client', () => ({
  default: {
    get: vi.fn().mockResolvedValue({}),
    post: vi.fn().mockResolvedValue({}),
    put: vi.fn().mockResolvedValue({}),
    delete: vi.fn().mockResolvedValue({}),
  },
}));

import apiClient from './client';
import {
  wikiApi,
  unwrapEnvelope,
  parseKnowledgeBase,
  parseKnowledgeBaseList,
  parseWikiPage,
  parseWikiPageList,
  parseWikiVersionList,
  parseWikiCategoryList,
  parseSearchResult,
} from './wiki';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('unwrapEnvelope', () => {
  it('数组原样返回', () => {
    expect(unwrapEnvelope([1, 2])).toEqual([1, 2]);
  });

  it('envelope 对象解包 data', () => {
    expect(unwrapEnvelope({ code: 0, message: 'ok', data: { id: 'x' } })).toEqual({ id: 'x' });
  });

  it('非 envelope 对象原样返回', () => {
    expect(unwrapEnvelope({ id: 'y' })).toEqual({ id: 'y' });
  });

  it('null/原始值原样返回', () => {
    expect(unwrapEnvelope(null)).toBeNull();
    expect(unwrapEnvelope('raw')).toBe('raw');
  });
});

describe('wikiApi 知识库', () => {
  it('listKb / getKb / createKb / updateKb / deleteKb 端点正确', async () => {
    await wikiApi.listKb();
    expect(apiClient.get).toHaveBeenCalledWith('/wiki/kb');

    await wikiApi.getKb('kb1');
    expect(apiClient.get).toHaveBeenCalledWith('/wiki/kb/kb1');

    await wikiApi.createKb({ name: 'KB', slug: 'kb' });
    expect(apiClient.post).toHaveBeenCalledWith('/wiki/kb', { name: 'KB', slug: 'kb' });

    await wikiApi.updateKb('kb1', { name: 'KB2' });
    expect(apiClient.put).toHaveBeenCalledWith('/wiki/kb/kb1', { name: 'KB2' });

    await wikiApi.deleteKb('kb1');
    expect(apiClient.delete).toHaveBeenCalledWith('/wiki/kb/kb1');
  });
});

describe('wikiApi 页面', () => {
  it('listPages 传递 params', async () => {
    await wikiApi.listPages({ kbId: 'kb1', status: 'PUBLISHED', page: 1, size: 20 });
    expect(apiClient.get).toHaveBeenCalledWith('/wiki/pages', {
      params: { kbId: 'kb1', status: 'PUBLISHED', page: 1, size: 20 },
    });
  });

  it('getPageBySlug 使用 by-slug 端点', async () => {
    await wikiApi.getPageBySlug('my-page');
    expect(apiClient.get).toHaveBeenCalledWith('/wiki/pages/by-slug/my-page');
  });

  it('createPage / updatePage / deletePage', async () => {
    await wikiApi.createPage({ knowledge_base_id: 'kb1', slug: 's', title: 'T' });
    expect(apiClient.post).toHaveBeenCalledWith('/wiki/pages', {
      knowledge_base_id: 'kb1',
      slug: 's',
      title: 'T',
    });

    await wikiApi.updatePage('p1', { title: 'T2' });
    expect(apiClient.put).toHaveBeenCalledWith('/wiki/pages/p1', { title: 'T2' });

    await wikiApi.deletePage('p1');
    expect(apiClient.delete).toHaveBeenCalledWith('/wiki/pages/p1');
  });

  it('publishPage / archivePage', async () => {
    await wikiApi.publishPage('p1');
    expect(apiClient.post).toHaveBeenCalledWith('/wiki/pages/p1/publish');

    await wikiApi.archivePage('p1');
    expect(apiClient.post).toHaveBeenCalledWith('/wiki/pages/p1/archive');
  });
});

describe('wikiApi 版本', () => {
  it('listVersions / getVersion / restoreVersion', async () => {
    await wikiApi.listVersions('p1');
    expect(apiClient.get).toHaveBeenCalledWith('/wiki/pages/p1/versions');

    await wikiApi.getVersion('p1', 3);
    expect(apiClient.get).toHaveBeenCalledWith('/wiki/pages/p1/versions/3');

    await wikiApi.restoreVersion('p1', 3);
    expect(apiClient.post).toHaveBeenCalledWith('/wiki/pages/p1/versions/3/restore');
  });
});

describe('wikiApi 分类', () => {
  it('listCategories / createCategory / updateCategory / deleteCategory', async () => {
    await wikiApi.listCategories('kb1');
    expect(apiClient.get).toHaveBeenCalledWith('/wiki/kb/kb1/categories');

    await wikiApi.createCategory({ name: 'C', slug: 'c', knowledge_base_id: 'kb1' });
    expect(apiClient.post).toHaveBeenCalledWith('/wiki/kb/kb1/categories', {
      name: 'C',
      slug: 'c',
      knowledge_base_id: 'kb1',
    });

    await wikiApi.updateCategory('c1', { name: 'C2' });
    expect(apiClient.put).toHaveBeenCalledWith('/wiki/categories/c1', { name: 'C2' });

    await wikiApi.deleteCategory('c1');
    expect(apiClient.delete).toHaveBeenCalledWith('/wiki/categories/c1');
  });
});

describe('wikiApi 搜索与 Block', () => {
  it('search 合并 q 与 params', async () => {
    await wikiApi.search('hello', { kbId: 'kb1', page: 1 });
    expect(apiClient.get).toHaveBeenCalledWith('/wiki/search', {
      params: { q: 'hello', kbId: 'kb1', page: 1 },
    });
  });

  it('Block 端点正确', async () => {
    await wikiApi.listBlocks('p1');
    expect(apiClient.get).toHaveBeenCalledWith('/wiki/pages/p1/blocks');

    await wikiApi.createBlocks('p1', [{ type: 'text', content: {} }]);
    expect(apiClient.post).toHaveBeenCalledWith('/wiki/pages/p1/blocks', [
      { type: 'text', content: {} },
    ]);

    await wikiApi.updateBlock('b1', { sort_order: 2 });
    expect(apiClient.put).toHaveBeenCalledWith('/wiki/blocks/b1', { sort_order: 2 });

    await wikiApi.deleteBlock('b1');
    expect(apiClient.delete).toHaveBeenCalledWith('/wiki/blocks/b1');

    await wikiApi.reorderBlocks('p1', ['b1', 'b2']);
    expect(apiClient.put).toHaveBeenCalledWith('/wiki/pages/p1/blocks/reorder', ['b1', 'b2']);
  });
});

describe('parse* 辅助函数', () => {
  it('parseKnowledgeBase 解包', () => {
    expect(parseKnowledgeBase({ code: 0, message: '', data: { id: 'kb' } })).toEqual({ id: 'kb' });
  });

  it('parseKnowledgeBaseList 解包', () => {
    expect(parseKnowledgeBaseList({ code: 0, message: '', data: [{ id: 'kb' }] })).toEqual([
      { id: 'kb' },
    ]);
  });

  it('parseWikiPage / parseWikiPageList', () => {
    expect(parseWikiPage({ code: 0, message: '', data: { id: 'p' } })).toEqual({ id: 'p' });
    expect(parseWikiPageList({ code: 0, message: '', data: { data: [], total: 0, page: 1, size: 10 } })).toEqual({
      data: [],
      total: 0,
      page: 1,
      size: 10,
    });
  });

  it('parseWikiVersionList / parseWikiCategoryList / parseSearchResult', () => {
    expect(parseWikiVersionList({ code: 0, message: '', data: [] })).toEqual([]);
    expect(parseWikiCategoryList({ code: 0, message: '', data: [] })).toEqual([]);
    expect(parseSearchResult({ code: 0, message: '', data: { data: [], total: 0, page: 1, size: 10 } })).toEqual({
      data: [],
      total: 0,
      page: 1,
      size: 10,
    });
  });
});
