/**
 * PHASE 4.2 覆盖率补测：features/bi/api.ts
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

import apiClient from '@/api/client';
import { biApi } from './api';

beforeEach(() => {
  vi.clearAllMocks();
});

describe('biApi', () => {
  it('pivot 解包 envelope.data', async () => {
    const result = {
      rows: ['r1'],
      columns: ['c1'],
      values: ['v1'],
      data: [{ r1: 1 }],
      totalRows: 1,
      totalCols: 1,
    };
    apiClient.post.mockResolvedValue({ code: 0, message: 'ok', data: result });
    const out = await biApi.pivot({ collection: 'orders', rows: ['region'] });
    expect(apiClient.post).toHaveBeenCalledWith('/bi/pivot', { collection: 'orders', rows: ['region'] });
    expect(out).toEqual(result);
  });

  it('chart 解包 envelope.data', async () => {
    const result = { chartType: 'bar', categories: ['A'], series: [{ name: 's', data: [1] }], total: 1 };
    apiClient.post.mockResolvedValue({ code: 0, message: 'ok', data: result });
    const out = await biApi.chart({ collection: 'orders', xField: 'region', yField: 'amount' });
    expect(apiClient.post).toHaveBeenCalledWith('/bi/chart', {
      collection: 'orders',
      xField: 'region',
      yField: 'amount',
    });
    expect(out.total).toBe(1);
  });

  it('listReports 传 collectionName 参数', async () => {
    apiClient.get.mockResolvedValue({ code: 0, message: 'ok', data: [{ id: 'r1' }] });
    const out = await biApi.listReports('orders');
    expect(apiClient.get).toHaveBeenCalledWith('/bi/reports', { params: { collectionName: 'orders' } });
    expect(out).toEqual([{ id: 'r1' }]);
  });

  it('saveReport 解包返回', async () => {
    apiClient.post.mockResolvedValue({ code: 0, message: 'ok', data: { id: 'r2' } });
    const out = await biApi.saveReport({ name: 'R' });
    expect(apiClient.post).toHaveBeenCalledWith('/bi/reports', { name: 'R' });
    expect(out).toEqual({ id: 'r2' });
  });

  it('unwrap 对无 data 字段的响应原样返回', async () => {
    apiClient.get.mockResolvedValue([{ id: 'plain' }]);
    const out = await biApi.listReports('orders');
    expect(out).toEqual([{ id: 'plain' }]);
  });
});
