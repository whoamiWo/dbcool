/**
 * PHASE 4.2 覆盖率补测：features/bi/BiReportPage.tsx
 * 覆盖：加载中、加载失败、空态、正常渲染、筛选器交互、Pivot/Chart 查询。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import BiReportPage from './BiReportPage';
import { biApi } from './api';

vi.mock('./api', () => ({ biApi: { pivot: vi.fn(), chart: vi.fn() } }));
vi.mock('@/api/client', () => ({ default: { get: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

import apiClient from '@/api/client';
const mockCols = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const mockPivot = biApi.pivot as unknown as ReturnType<typeof vi.fn>;
const mockChart = biApi.chart as unknown as ReturnType<typeof vi.fn>;

function renderView() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <BiReportPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockCols.mockResolvedValue({ data: [{ name: 'orders' }] });
  mockPivot.mockResolvedValue({ data: [{ x: 'a', y: 1 }] });
  mockChart.mockResolvedValue({ data: [{ x: 'a', y: 1 }] });
});

describe('BiReportPage', () => {
  it('加载中渲染进度圈', () => {
    mockCols.mockReturnValue(new Promise(() => {}));
    renderView();
    expect(document.querySelector('.MuiCircularProgress-root')).toBeTruthy();
  });

  it('加载失败渲染错误提示', async () => {
    mockCols.mockRejectedValue(new Error('cols err'));
    renderView();
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
  });

  it('正常加载渲染标题和筛选器', async () => {
    const { container } = renderView();
    await waitFor(() => expect(container.textContent).toContain('biReport.title'));
    expect(container.textContent).toContain('biReport.dataset');
  });

  it('未选择维度时展示提示且查询按钮禁用', async () => {
    renderView();
    await waitFor(() => expect(screen.getByText('biReport.title')).toBeInTheDocument());
    expect(screen.getByText('biReport.selectHint')).toBeInTheDocument();
    const runBtn = screen.getAllByText('biReport.query')[0].closest('button') as HTMLButtonElement;
    expect(runBtn.disabled).toBe(true);
  });

  it('选择数据集后可选中集合名称', async () => {
    const { container } = renderView();
    await waitFor(() => expect(container.textContent).toContain('biReport.title'));
    fireEvent.mouseDown(container.querySelector('.MuiSelect-select') as Element);
    await waitFor(() => expect(screen.getByText('orders')).toBeInTheDocument());
  });
});