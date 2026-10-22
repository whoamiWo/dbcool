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

/** TS: apiClient 方法为泛型函数，mock 后需断言为 vi.fn 才能调 mockResolvedValue。 */
const mockedGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const mockedPost = apiClient.post as unknown as ReturnType<typeof vi.fn>;

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
    mockedPost.mockResolvedValue({ code: 0, message: 'ok', data: result });
    const out = await biApi.pivot({ collection: 'orders', rows: ['region'] });
    expect(mockedPost).toHaveBeenCalledWith('/bi/pivot', { collection: 'orders', rows: ['region'] });
    expect(out).toEqual(result);
  });

  it('chart 解包 envelope.data', async () => {
    const result = { chartType: 'bar', categories: ['A'], series: [{ name: 's', data: [1] }], total: 1 };
    mockedPost.mockResolvedValue({ code: 0, message: 'ok', data: result });
    const out = await biApi.chart({ collection: 'orders', xField: 'region', yField: 'amount' });
    expect(mockedPost).toHaveBeenCalledWith('/bi/chart', {
      collection: 'orders',
      xField: 'region',
      yField: 'amount',
    });
    expect(out.total).toBe(1);
  });

  it('listReports 传 collectionName 参数', async () => {
    mockedGet.mockResolvedValue({ code: 0, message: 'ok', data: [{ id: 'r1' }] });
    const out = await biApi.listReports('orders');
    expect(mockedGet).toHaveBeenCalledWith('/bi/reports', { params: { collectionName: 'orders' } });
    expect(out).toEqual([{ id: 'r1' }]);
  });

  it('saveReport 解包返回', async () => {
    mockedPost.mockResolvedValue({ code: 0, message: 'ok', data: { id: 'r2' } });
    const out = await biApi.saveReport({ name: 'R' });
    expect(mockedPost).toHaveBeenCalledWith('/bi/reports', { name: 'R' });
    expect(out).toEqual({ id: 'r2' });
  });

  it('unwrap 对无 data 字段的响应原样返回', async () => {
    mockedGet.mockResolvedValue([{ id: 'plain' }]);
    const out = await biApi.listReports('orders');
    expect(out).toEqual([{ id: 'plain' }]);
  });
});
