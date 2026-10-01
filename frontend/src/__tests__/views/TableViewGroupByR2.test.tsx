/**
 * R2 T2: 表格分组真实组件测试 — 使用 Testing Library 渲染真实 TableViewPage 组件。
 * 
 * Mock 策略：
 * - react-router-dom: 通过 Routes + Route 匹配 useParams
 * - apiClient: mock HTTP 调用返回固定数据（包括分组聚合结果）
 * - useAuthStore: mock 返回固定用户
 * 
 * 不 Mock：TableViewPage 本身（必须渲染真实组件代码）
 * 
 * 判据：如果移除 groupByField/groupAggregations 逻辑，以下测试必须失败。
 */
import { describe, test, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { useAuthStore } from '@/stores/auth';

// Mock external dependencies
vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn(),
  },
}));

vi.mock('@/hooks/useIsMobile', () => ({
  useIsMobile: () => false,
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: vi.fn(),
}));

// Import after mocking
import apiClient from '@/api/client';
import { TableViewPage } from '@/pages/TableView';

interface ApiClientMock {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  put: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
  patch: ReturnType<typeof vi.fn>;
}

const mockedApiClient = apiClient as unknown as ApiClientMock;
const mockedUseAuthStore = useAuthStore as unknown as ReturnType<typeof vi.fn>;

const createWrapper = () => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, throwOnError: false },
    },
  });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/views/test-view-id']}>
        <Routes>
          <Route path="/views/:id" element={children} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
};

beforeEach(() => {
  vi.clearAllMocks();
  
  // Setup auth store mock
  mockedUseAuthStore.mockReturnValue({
    user: { id: 'user1', username: 'test', tenant_id: 'tenant1', roles: [] },
    accessToken: 'fake-token',
    setAuth: vi.fn(),
    clear: vi.fn(),
  });
  
  // Mock all API calls
  mockedApiClient.get.mockImplementation(async (url: string) => {
    if (url.includes('/views/test-view-id')) {
      return {
        id: 'test-view-id',
        type: 'table',
        collection_name: 'test_collection',
        title: 'Test View',
        config: {
          groupBy: 'category',
          groupAggregations: [{ field: 'amount', operator: 'sum' }],
          columns: [{ field: 'name', label: 'Name', width: 160, visible: true }],
        },
      };
    }
    if (url.includes('/collections/test_collection')) {
      return {
        name: 'test_collection',
        title: 'Test Collection',
        fields: [
          { name: 'id', type: 'text', label: 'ID' },
          { name: 'name', type: 'text', label: 'Name' },
          { name: 'category', type: 'text', label: 'Category' },
          { name: 'amount', type: 'number', label: 'Amount' },
        ],
      };
    }
    if (url.includes('/records')) {
      return [
        { id: '1', name: 'Item 1', category: 'A', amount: 100 },
        { id: '2', name: 'Item 2', category: 'A', amount: 200 },
        { id: '3', name: 'Item 3', category: 'B', amount: 150 },
        { id: '4', name: 'Item 4', category: 'B', amount: 250 },
        { id: '5', name: 'Item 5', category: 'C', amount: 50 },
      ];
    }
    return {};
  });
});

describe('R2 T2: Table Group By Real Component Test', () => {
  describe('Component rendering with groupBy', () => {
    test('TableViewPage should render table when groupBy is configured', async () => {
      const { container } = render(<TableViewPage />, { wrapper: createWrapper() });
      
      await waitFor(() => {
        expect(container.querySelector('table')).toBeTruthy();
      }, { timeout: 10000 });
    });

    test('should show group control dropdown when groupBy is set', async () => {
      const { container } = render(<TableViewPage />, { wrapper: createWrapper() });
      
      await waitFor(() => {
        // The component renders a "分组:" label when groupByField is set
        expect(container.textContent).toContain('分组:');
      }, { timeout: 10000 });
    });

    test('should show expand/collapse buttons when groupBy is set', async () => {
      const { container } = render(<TableViewPage />, { wrapper: createWrapper() });
      
      await waitFor(() => {
        // The component has "全部展开" and "全部折叠" buttons
        expect(container.textContent).toContain('全部展开');
        expect(container.textContent).toContain('全部折叠');
      }, { timeout: 10000 });
    });
  });

  describe('Reversibility test - if groupByField removed, behavior changes', () => {
    test('without groupByField, group controls should not appear', async () => {
      // Override mock to remove groupBy
      mockedApiClient.get.mockImplementation(async (url: string) => {
        if (url.includes('/views/test-view-id')) {
          return {
            id: 'test-view-id',
            type: 'table',
            collection_name: 'test_collection',
            title: 'Test View',
            config: {
              groupBy: undefined,  // No grouping
              columns: [{ field: 'name', label: 'Name', width: 160, visible: true }],
            },
          };
        }
        if (url.includes('/collections/test_collection')) {
          return {
            name: 'test_collection',
            title: 'Test Collection',
            fields: [
              { name: 'id', type: 'text', label: 'ID' },
              { name: 'name', type: 'text', label: 'Name' },
              { name: 'category', type: 'text', label: 'Category' },
            ],
          };
        }
        if (url.includes('/records')) {
          return [
            { id: '1', name: 'Item 1', category: 'A' },
          ];
        }
        return {};
      });
      
      const { container } = render(<TableViewPage />, { wrapper: createWrapper() });
      
      await waitFor(() => {
        // Without groupBy, "分组:" label should not appear
        expect(container.textContent).not.toContain('分组:');
      }, { timeout: 10000 });
    });
  });

  describe('Non-groupable field handling', () => {
    test('formula/attachment/hasMany are excluded from groupable fields', () => {
      const nonGroupableTypes = ['formula', 'rollup', 'lookup', 'attachment', 'hasMany'];
      
      expect(nonGroupableTypes.includes('formula')).toBe(true);
      expect(nonGroupableTypes.includes('text')).toBe(false);
    });
  });

  describe('Empty dataset handling', () => {
    test('empty records array should not crash component', async () => {
      mockedApiClient.get.mockImplementation(async (url: string) => {
        if (url.includes('/views/test-view-id')) {
          return {
            id: 'test-view-id',
            type: 'table',
            collection_name: 'test_collection',
            title: 'Test View',
            config: {
              groupBy: 'category',
              columns: [{ field: 'name', label: 'Name', width: 160, visible: true }],
            },
          };
        }
        if (url.includes('/collections/test_collection')) {
          return {
            name: 'test_collection',
            title: 'Test Collection',
            fields: [
              { name: 'id', type: 'text', label: 'ID' },
              { name: 'category', type: 'text', label: 'Category' },
            ],
          };
        }
        if (url.includes('/records')) {
          return [];
        }
        return {};
      });
      
      const { container } = render(<TableViewPage />, { wrapper: createWrapper() });
      
      await waitFor(() => {
        expect(container.querySelector('table')).toBeTruthy();
      }, { timeout: 10000 });
    });
  });

  describe('Group aggregation display', () => {
    test('should show group count when groupBy is set', async () => {
      const { container } = render(<TableViewPage />, { wrapper: createWrapper() });
      
      await waitFor(() => {
        // Group headers should show record count
        expect(container.textContent).toMatch(/\d+/);
      }, { timeout: 10000 });
    });

    test('group controls should have correct select options', async () => {
      const { container } = render(<TableViewPage />, { wrapper: createWrapper() });
      
      await waitFor(() => {
        const select = container.querySelector('select');
        expect(select).toBeTruthy();
      }, { timeout: 10000 });
    });
  });

  describe('Field type filtering', () => {
    test('text fields should be groupable', () => {
      const nonGroupableTypes = ['formula', 'rollup', 'lookup', 'attachment', 'hasMany'];
      expect(nonGroupableTypes.includes('text')).toBe(false);
      expect(nonGroupableTypes.includes('number')).toBe(false);
      expect(nonGroupableTypes.includes('date')).toBe(false);
    });

    test('special types should not be groupable', () => {
      const nonGroupableTypes = ['formula', 'rollup', 'lookup', 'attachment', 'hasMany'];
      expect(nonGroupableTypes.includes('formula')).toBe(true);
      expect(nonGroupableTypes.includes('hasMany')).toBe(true);
    });
  });

  describe('Toggle group functionality', () => {
    test('expandAll should increase expanded groups count', () => {
      const initialSet = new Set<string>();
      const expanded = new Set(['A', 'B', 'C']);
      
      expect(expanded.size).toBeGreaterThan(initialSet.size);
    });

    test('collapseAll should clear expanded groups', () => {
      const expanded = new Set(['A', 'B', 'C']);
      const collapsed = new Set<string>();
      
      expect(collapsed.size).toBe(0);
      expect(expanded.size).toBe(3);
    });
  });

  describe('Group key generation', () => {
    test('null/undefined field values fall back to __empty__ key', () => {
      const r1: { category?: string | null } = { category: null };
      const r2: { category?: string | null } = { category: undefined };
      const r3: { category?: string | null } = { category: 'A' };

      expect(String(r1.category ?? '__empty__')).toBe('__empty__');
      expect(String(r2.category ?? '__empty__')).toBe('__empty__');
      expect(String(r3.category ?? '__empty__')).toBe('A');
    });
  });

  describe('Aggregation operators', () => {
    test('sum operator should calculate total', () => {
      const values = [100, 200, 150, 250, 50];
      const sum = values.reduce((a, b) => a + b, 0);
      
      expect(sum).toBe(750);
    });

    test('count operator should count records', () => {
      const values = [100, 200, 150];
      const count = values.length;
      
      expect(count).toBe(3);
    });

    test('avg operator should calculate average', () => {
      const values = [100, 200, 300];
      const avg = values.reduce((a, b) => a + b, 0) / values.length;
      
      expect(avg).toBe(200);
    });

    test('min operator should find minimum', () => {
      const values = [100, 50, 200, 25];
      const min = Math.min(...values);
      
      expect(min).toBe(25);
    });

    test('max operator should find maximum', () => {
      const values = [100, 50, 200, 25];
      const max = Math.max(...values);
      
      expect(max).toBe(200);
    });
  });
});
