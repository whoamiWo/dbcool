/**
 * PHASE 4.2 覆盖率补测：pages/ViewDesigner.tsx（C2 冻结列 + 条件格式）
 *
 * 判据：如果移除 FreezeEditor / ConditionalStyleEditor 的接线，
 * 以下测试必须失败（labels 消失 / config 不含 freezeCount / 无法添加规则）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ViewDesignerPage } from './ViewDesigner';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    put: vi.fn(),
    post: vi.fn(),
  },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => {
      // 模拟真实翻译：返回中文标签而非键本身
      const map: Record<string, string> = {
        freezeCount: '冻结列数',
        freezeHint: '冻结左侧 N 列（0=不冻结）',
        condStyles: '条件格式规则',
        condEmpty: '暂无规则',
        condAdd: '添加规则',
        condField: '字段',
        condOp: '操作',
        condValue: '值',
        condColor: '文字色',
        condBg: '背景色',
        condRemove: '移除',
        opEq: '=',
        opNeq: '≠',
        opContains: '包含',
        opGt: '>',
        opLt: '<',
        opEmpty: '为空',
        opNotEmpty: '不为空',
        saveAndOpen: '保存并打开',
        saving: '保存中...',
      };
      return map[k] ?? k;
    },
  }),
}));

vi.mock('react-router-dom', async () => {
  const mod = await vi.importActual('react-router-dom');
  return { ...mod, useParams: vi.fn() };
});

const apiGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const apiPut = apiClient.put as unknown as ReturnType<typeof vi.fn>;

function renderDesigner(viewConfigOverride?: Record<string, unknown>) {
  (useParams as unknown as ReturnType<typeof vi.fn>).mockReturnValue({
    collection: 'orders',
    id: 'v1',
  });
  // /views/v1 与 /collections/orders 两个 GET
  apiGet.mockImplementation((url: string) => {
    if (url.startsWith('/views/')) {
      return Promise.resolve({
        id: 'v1',
        collection_name: 'orders',
        name: 'order_view',
        title: '销售明细',
        type: 'table',
        config: { pageSize: 20, columns: [
          { field: 'id', label: '编号', width: 160, visible: true },
          { field: 'amount', label: '金额', width: 160, visible: true },
        ], ...viewConfigOverride },
      });
    }
    return Promise.resolve({
      name: 'orders',
      fields: [
        { name: 'id', label: '编号', type: 'text' },
        { name: 'amount', label: '金额', type: 'number' },
        { name: 'status', label: '状态', type: 'text' },
      ],
    });
  });

  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/designer/orders/v1/edit']}>
        <Routes>
          <Route path="/designer/:collection/:id/edit" element={<ViewDesignerPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ViewDesignerPage C2: 冻结列 + 条件格式', () => {
  it('渲染冻结列输入框（FreezeEditor），默认值 0', async () => {
    renderDesigner();
    const freezeInput = (await waitFor(() => screen.getByLabelText('冻结列数'))) as HTMLInputElement;
    expect(freezeInput).toBeInTheDocument();
    expect(freezeInput.value).toBe('0');
  });

  it('视图已有 freezeCount 时回显', async () => {
    renderDesigner({ freezeCount: 2 });
    // 等待字段加载完成
    await waitFor(() =>
      expect(screen.getByText((_, el) => el?.textContent === '条件格式规则 (0)')).toBeInTheDocument(),
    );
    // 等待冻结列数更新为 2
    await waitFor(() => {
      const freezeInput = screen.getByLabelText('冻结列数') as HTMLInputElement;
      expect(freezeInput.value).toBe('2');
    });
  });

  it('条件格式编辑器渲染空态与新增按钮', async () => {
    renderDesigner();
    // 标签渲染为「条件格式规则 (N)」，用函数 matcher 容忍计数
    await waitFor(() =>
      expect(screen.getByText((_, el) => el?.textContent === '条件格式规则 (0)')).toBeInTheDocument(),
    );
    expect(screen.getByText('暂无规则')).toBeInTheDocument();
    expect(screen.getByText('+ 添加规则')).toBeInTheDocument();
  });

  it('点击新增规则后出现规则行并可编辑', async () => {
    renderDesigner();
    await waitFor(() =>
      expect(screen.getByText((_, el) => el?.textContent === '条件格式规则 (0)')).toBeInTheDocument(),
    );

    // 等待新增按钮启用（表示 fields 已加载）
    await waitFor(() => {
      const btn = screen.getByText('+ 添加规则');
      expect(btn.hasAttribute('disabled')).toBe(false);
    });

    fireEvent.click(screen.getByText('+ 添加规则'));

    // 新规则默认选中第一个字段（显示 label 而非 name）
    const fieldSelect = screen.getByTitle('字段') as HTMLSelectElement;
    expect(fieldSelect.options[fieldSelect.selectedIndex]?.value).toBe('id');

    // 切换字段与操作，填写值
    fireEvent.change(fieldSelect, { target: { value: 'amount' } });
    const opSelect = screen.getByTitle('操作') as HTMLSelectElement;
    fireEvent.change(opSelect, { target: { value: 'gt' } });
    const valueInput = screen.getByPlaceholderText('值') as HTMLInputElement;
    fireEvent.change(valueInput, { target: { value: '100' } });
    expect(valueInput.value).toBe('100');
  });

  it('保存时 freezeCount 与 conditionalStyles 写入 config', async () => {
    renderDesigner();
    await waitFor(() =>
      expect(screen.getByText((_, el) => el?.textContent === '条件格式规则 (0)')).toBeInTheDocument(),
    );

    // 冻结列 = 2
    const freezeInput = screen.getByLabelText('冻结列数') as HTMLInputElement;
    fireEvent.change(freezeInput, { target: { value: '2' } });

    // 添加一条条件格式规则：amount gt 100
    await waitFor(() => {
      const btn = screen.getByText('+ 添加规则');
      expect(btn.hasAttribute('disabled')).toBe(false);
    });
    fireEvent.click(screen.getByText('+ 添加规则'));
    const fieldSelect = screen.getByTitle('字段') as HTMLSelectElement;
    fireEvent.change(fieldSelect, { target: { value: 'amount' } });
    const opSelect = screen.getByTitle('操作') as HTMLSelectElement;
    fireEvent.change(opSelect, { target: { value: 'gt' } });
    fireEvent.change(screen.getByPlaceholderText('值'), { target: { value: '100' } });

    // 保存
    apiPut.mockResolvedValue({ id: 'v1' });
    fireEvent.click(screen.getByText('保存并打开'));

    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(1));
    expect(apiPut.mock.calls[0][0]).toBe('/views/v1');
    const body = apiPut.mock.calls[0][1] as { config: string };
    const parsed = JSON.parse(body.config);
    expect(parsed.freezeCount).toBe(2);
    expect(parsed.conditionalStyles).toHaveLength(1);
    expect(parsed.conditionalStyles[0]).toMatchObject({
      field: 'amount',
      op: 'gt',
      value: '100',
    });
  });
});
