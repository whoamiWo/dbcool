/**
 * PHASE 4.2 覆盖率补测：pages/SchemaDesigner.tsx
 *
 * 该页为「新建集合」表单页（无挂载数据加载），测试聚焦：
 * 初始渲染、字段增删、类型切换（含自动/派生字段只读）、公式编辑器、
 * 提交校验分支（名称非法 / 无字段 / 成功 / 失败）。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { SchemaDesignerPage } from './SchemaDesigner';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

// FormulaEditor 依赖 Monaco，测试环境替换为轻量替身
vi.mock('@/components/views/FormulaEditor', () => ({
  FormulaEditor: ({ onSave }: { onSave?: (v: string) => void }) => (
    <div data-testid="formula-editor">
      <button type="button" onClick={() => onSave?.('SUM(a,b)')}>save-formula</button>
    </div>
  ),
}));

const wrap = () => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter><SchemaDesignerPage /></MemoryRouter>
    </QueryClientProvider>
  );
};

/** 通过 aria-label 取字段行内的输入控件 */
const fieldNameInputs = () =>
  screen.queryAllByLabelText('schemaDesigner.fieldAriaFieldName') as HTMLInputElement[];

describe('SchemaDesignerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('初始渲染：标题与集合名输入框存在', () => {
    wrap();
    expect(screen.getByText('schemaDesigner.title')).toBeInTheDocument();
    expect(screen.getByLabelText(/schemaDesigner.collectionName/)).toBeInTheDocument();
    expect(screen.getByLabelText('schemaDesigner.displayTitle')).toBeInTheDocument();
    // 初始无字段行
    expect(fieldNameInputs()).toHaveLength(0);
  });

  it('添加字段：新增一行且默认名为 field_1', () => {
    wrap();
    fireEvent.click(screen.getByText('schemaDesigner.addField'));
    const rows = fieldNameInputs();
    expect(rows).toHaveLength(1);
    expect(rows[0].value).toBe('field_1');

    fireEvent.click(screen.getByText('schemaDesigner.addField'));
    expect(fieldNameInputs()).toHaveLength(2);
    expect(fieldNameInputs()[1].value).toBe('field_2');
  });

  it('删除字段：行数回落', () => {
    wrap();
    fireEvent.click(screen.getByText('schemaDesigner.addField'));
    fireEvent.click(screen.getByText('schemaDesigner.addField'));
    expect(fieldNameInputs()).toHaveLength(2);

    const removeButtons = screen.getAllByText('schemaDesigner.removeField');
    fireEvent.click(removeButtons[0]);
    expect(fieldNameInputs()).toHaveLength(1);
  });

  it('切换字段类型为 formula 后出现公式编辑器入口', () => {
    wrap();
    fireEvent.click(screen.getByText('schemaDesigner.addField'));
    const selects = document.querySelectorAll('select');
    fireEvent.change(selects[0], { target: { value: 'formula' } });
    expect(screen.getByLabelText('schemaDesigner.fieldAriaFormula')).toBeInTheDocument();
  });

  it('点击公式按钮打开编辑器并可保存表达式', async () => {
    wrap();
    fireEvent.click(screen.getByText('schemaDesigner.addField'));
    fireEvent.change(document.querySelectorAll('select')[0], { target: { value: 'formula' } });
    fireEvent.click(screen.getByLabelText('schemaDesigner.fieldAriaFormula'));

    expect(screen.getByTestId('formula-editor')).toBeInTheDocument();
    fireEvent.click(screen.getByText('save-formula'));
    await waitFor(() => expect(screen.queryByTestId('formula-editor')).not.toBeInTheDocument());
  });

  it('提交校验：名称非法时不发请求并提示错误', async () => {
    wrap();
    fireEvent.click(screen.getByText('schemaDesigner.addField'));
    fireEvent.change(screen.getByLabelText(/schemaDesigner.collectionName/), { target: { value: '1-Bad Name' } });
    fireEvent.click(screen.getByText('schemaDesigner.saveCollection'));

    await waitFor(() => expect(screen.getByText('schemaDesigner.nameInvalid')).toBeInTheDocument());
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it('提交校验：合法名称但无字段时提示错误', async () => {
    wrap();
    fireEvent.change(screen.getByLabelText(/schemaDesigner.collectionName/), { target: { value: 'orders' } });
    fireEvent.click(screen.getByText('schemaDesigner.saveCollection'));

    await waitFor(() => expect(screen.getByText('schemaDesigner.noFields')).toBeInTheDocument());
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it('提交成功：POST /collections 携带名称与字段', async () => {
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({ name: 'orders' });
    wrap();
    fireEvent.change(screen.getByLabelText(/schemaDesigner.collectionName/), { target: { value: 'orders' } });
    fireEvent.change(screen.getByLabelText('schemaDesigner.displayTitle'), { target: { value: 'Orders' } });
    fireEvent.click(screen.getByText('schemaDesigner.addField'));
    fireEvent.click(screen.getByText('schemaDesigner.saveCollection'));

    await waitFor(() => expect(apiClient.post).toHaveBeenCalled());
    const [url, body] = (apiClient.post as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/collections');
    expect(body).toMatchObject({ name: 'orders', title: 'Orders' });
    expect((body as { fields: unknown[] }).fields).toHaveLength(1);
  });

  it('提交失败：展示后端返回的错误信息', async () => {
    (apiClient.post as ReturnType<typeof vi.fn>).mockRejectedValue({
      response: { data: { message: 'duplicate collection' } },
    });
    wrap();
    fireEvent.change(screen.getByLabelText(/schemaDesigner.collectionName/), { target: { value: 'orders' } });
    fireEvent.click(screen.getByText('schemaDesigner.addField'));
    fireEvent.click(screen.getByText('schemaDesigner.saveCollection'));

    await waitFor(() => expect(screen.getByText('duplicate collection')).toBeInTheDocument());
  });

  it('打开 JSON Schema 导入弹窗', () => {
    wrap();
    fireEvent.click(screen.getByText('schemaDesigner.importSchema'));
    // 弹窗打开后 importSchema 文案会再次出现（对话框内标题/按钮）
    expect(screen.getAllByText(/schemaDesigner\.importSchema/).length).toBeGreaterThanOrEqual(2);
  });
});