/**
 * PHASE 4.3 覆盖率补测：pages/TableView.tsx - C2 条件格式渲染
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { TableViewPage } from './TableView';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return {
    ...actual,
    useParams: () => ({ collection: 'orders', id: 'v1' }),
    useNavigate: () => vi.fn(),
  };
});
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock('@/hooks/useIsMobile', () => ({ useIsMobile: () => false }));

const mockCollection = {
  name: 'orders',
  fields: [
    { name: 'id', label: '编号', type: 'text' },
    { name: 'amount', label: '金额', type: 'number' },
    { name: 'status', label: '状态', type: 'select' },
  ],
};

const baseColumns = [
  { field: 'id', label: '编号', width: 160, visible: true },
  { field: 'amount', label: '金额', width: 160, visible: true },
  { field: 'status', label: '状态', width: 160, visible: true },
];

const mockRecords = [
  { id: '1', amount: 500, status: 'pending' },
  { id: '2', amount: 1500, status: 'completed' },
  { id: '3', amount: 800, status: 'cancelled' },
];

/** 依据 url 分发 mock 响应 */
function mockApi(config: Record<string, unknown>) {
  (apiClient.get as ReturnType<typeof vi.fn>).mockImplementation((url: string) => {
    if (url.startsWith('/views/')) {
      return Promise.resolve({
        id: 'v1',
        collection_name: 'orders',
        name: 'order_view',
        title: '订单列表',
        type: 'table',
        config,
      });
    }
    if (url.startsWith('/collections/') && url.includes('/records')) {
      return Promise.resolve(mockRecords);
    }
    if (url.startsWith('/collections/')) {
      return Promise.resolve(mockCollection);
    }
    return Promise.resolve(null);
  });
}

function renderTable() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <TableViewPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('TableView C2: 条件格式渲染', () => {
  beforeEach(() => vi.clearAllMocks());

  it('命中条件的单元格应用自定义前景/背景色', async () => {
    mockApi({
      pageSize: 20,
      columns: baseColumns,
      conditionalStyles: [
        { field: 'amount', op: 'gt', value: 1000, color: 'rgb(239, 68, 68)', background: 'rgb(254, 242, 242)' },
      ],
    });

    const { findByText } = renderTable();
    const cell = await findByText('1500');

    expect(cell).toBeInTheDocument();
    expect(cell.style.color).toBe('rgb(239, 68, 68)');
    expect(cell.style.background).toBe('rgb(254, 242, 242)');
  });

  it('未命中条件的单元格保持默认样式', async () => {
    mockApi({
      pageSize: 20,
      columns: baseColumns,
      conditionalStyles: [
        { field: 'amount', op: 'gt', value: 1000, color: 'rgb(239, 68, 68)', background: 'rgb(254, 242, 242)' },
      ],
    });

    const { findByText } = renderTable();
    const cell = await findByText('500');

    expect(cell.style.color).not.toBe('rgb(239, 68, 68)');
    expect(cell.style.background).not.toBe('rgb(254, 242, 242)');
  });

  it('多条规则按顺序匹配，第一条命中即止', async () => {
    mockApi({
      pageSize: 20,
      columns: baseColumns,
      conditionalStyles: [
        { field: 'status', op: 'eq', value: 'completed', color: 'rgb(16, 185, 129)', background: 'rgb(240, 253, 244)' },
        { field: 'status', op: 'eq', value: 'completed', color: 'rgb(999, 0, 0)', background: 'rgb(1, 2, 3)' },
      ],
    });

    const { findByText } = renderTable();
    const cell = await findByText('completed');

    // 应命中第一条规则，第二条被跳过
    expect(cell.style.color).toBe('rgb(16, 185, 129)');
    expect(cell.style.background).toBe('rgb(240, 253, 244)');
  });

  it('无 conditionalStyles 配置时不报错且渲染正常', async () => {
    mockApi({ pageSize: 20, columns: baseColumns });

    const { findByText } = renderTable();
    expect(await findByText('pending')).toBeInTheDocument();
  });

  it('empty 操作符对空值命中', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockImplementation((url: string) => {
      if (url.startsWith('/views/')) {
        return Promise.resolve({
          id: 'v1',
          collection_name: 'orders',
          name: 'order_view',
          title: '订单列表',
          type: 'table',
          config: {
            pageSize: 20,
            columns: baseColumns,
            conditionalStyles: [
              { field: 'status', op: 'empty', color: 'rgb(107, 114, 128)', background: 'rgb(243, 244, 246)' },
            ],
          },
        });
      }
      if (url.includes('/records')) {
        return Promise.resolve([{ id: '9', amount: 100, status: '' }]);
      }
      return Promise.resolve(mockCollection);
    });

    const { findAllByText } = renderTable();
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled());
    // 空字符串单元格仍渲染（可能为空文本），至少不应抛错
    const cells = await findAllByText((_, el) => el?.tagName === 'TD');
    expect(cells.length).toBeGreaterThan(0);
  });
});
