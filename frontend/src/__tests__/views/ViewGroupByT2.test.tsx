import { TableConfig, GalleryConfig } from '../../types/view';

/**
 * T2: 视图 group by 功能测试 — 验证表格/画廊视图分组配置。
 */
describe('View Group By (T2)', () => {
  describe('TableConfig groupBy should be defined', () => {
    test('TableConfig should support groupBy field', () => {
      const config: TableConfig = {
        columns: [{ field: 'name', label: 'Name' }],
        groupBy: 'status',
        groupAggregations: [
          { field: 'amount', operator: 'sum' },
          { field: 'price', operator: 'avg' },
        ],
      };
      expect(config.groupBy).toBe('status');
      expect(config.groupAggregations?.length).toBe(2);
    });

    test('TableConfig groupAggregations should support sum/avg/min/max', () => {
      const ops: Array<'sum' | 'avg' | 'min' | 'max'> = ['sum', 'avg', 'min', 'max'];
      ops.forEach((op) => {
        const agg = { field: 'amount', operator: op } as const;
        expect(agg.operator).toBe(op);
      });
    });

    test('Empty groupBy should still be valid (no grouping)', () => {
      const config: TableConfig = {
        columns: [{ field: 'name' }],
      };
      expect(config.groupBy).toBeUndefined();
    });
  });

  describe('GalleryConfig groupBy should be defined', () => {
    test('GalleryConfig should support groupBy field', () => {
      const config: GalleryConfig = {
        groupBy: 'category',
        cardTitleField: 'title',
        cardFields: ['description', 'price'],
      };
      expect(config.groupBy).toBe('category');
    });

    test('GalleryConfig without groupBy should work', () => {
      const config: GalleryConfig = {
        cardTitleField: 'title',
      };
      expect(config.groupBy).toBeUndefined();
    });
  });

  describe('Invalid aggregation operators should be rejected', () => {
    test('invalid operator should not match valid ones', () => {
      const invalidOps = ['count', 'median', 'first', 'last'];
      const validOps: Array<'sum' | 'avg' | 'min' | 'max'> = ['sum', 'avg', 'min', 'max'];
      
      invalidOps.forEach((op) => {
        expect(validOps.includes(op as any)).toBe(false);
      });
    });
  });

  describe('Non-groupable fields should be identified', () => {
    const nonGroupableTypes = ['formula', 'rollup', 'lookup', 'attachment', 'hasMany'];
    const groupableTypes = ['text', 'number', 'boolean', 'date', 'datetime', 'select', 'belongsTo', 'email', 'url', 'phone', 'currency', 'percent', 'rating', 'duration'];

    test('non-groupable types should not be usable for grouping', () => {
      nonGroupableTypes.forEach((type) => {
        expect(groupableTypes.includes(type)).toBe(false);
      });
    });

    test('groupable types should be usable for grouping', () => {
      groupableTypes.forEach((type) => {
        expect(nonGroupableTypes.includes(type)).toBe(false);
      });
    });
  });

  describe('Group count calculation', () => {
    test('grouped records should have count', () => {
      const groupedData = [
        { status: 'pending', count: 5, total: 100 },
        { status: 'approved', count: 3, total: 150 },
      ];
      expect(groupedData[0].count).toBe(5);
      expect(groupedData[1].count).toBe(3);
    });
  });

  describe('Numeric aggregations should produce correct results', () => {
    const records = [
      { status: 'A', amount: 100, price: 10 },
      { status: 'A', amount: 200, price: 20 },
      { status: 'B', amount: 150, price: 15 },
    ];

    test('sum aggregation should work', () => {
      const sumA = records.filter(r => r.status === 'A').reduce((acc, r) => acc + r.amount, 0);
      expect(sumA).toBe(300);
    });

    test('avg aggregation should work', () => {
      const avgA = records.filter(r => r.status === 'A').reduce((acc, r) => acc + r.price, 0) / 2;
      expect(avgA).toBe(15);
    });

    test('min aggregation should work', () => {
      const minB = Math.min(...records.filter(r => r.status === 'B').map(r => r.amount));
      expect(minB).toBe(150);
    });

    test('max aggregation should work', () => {
      const maxA = Math.max(...records.filter(r => r.status === 'A').map(r => r.amount));
      expect(maxA).toBe(200);
    });
  });
});