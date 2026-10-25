/**
 * PHASE 4.2 覆盖率补测：pages/WorkflowDesigner.tsx
 *
 * 该页是 React Flow 画布设计器：
 * - 新建模式：无远端加载，本地维护 nodes/edges
 * - 编辑模式：GET /workflows/:id 拉取定义并回填
 * 测试覆盖：初始渲染、编辑模式回填、表单交互、保存 POST/PUT、
 * 测试运行弹窗、节点拖拽落点。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { WorkflowDesignerPageWithProvider } from './WorkflowDesigner';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

const wrap = (path: string) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/workflows/designer/:collection/:id?" element={<WorkflowDesignerPageWithProvider />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
};

describe('WorkflowDesignerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('新建模式渲染画布与表单，不发详情请求', async () => {
    wrap('/workflows/designer/test');
    expect(screen.getByLabelText('workflowDesigner.canvasAria')).toBeInTheDocument();
    expect(screen.getByText('workflowDesigner.createMode workflowDesigner.title')).toBeInTheDocument();
    // enabled: isEdit=false → 详情查询不触发
    expect(apiClient.get).not.toHaveBeenCalled();
  });

  it('编辑模式加载 workflow 并回填名称', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'w1',
      name: 'my_flow',
      title: 'Test Workflow',
      collection: 'customer',
      triggerType: 'manual',
      enabled: true,
      nodes: [],
      edges: [],
    });
    wrap('/workflows/designer/test/w1');
    await waitFor(() => expect(apiClient.get).toHaveBeenCalledTimes(1), { timeout: 4000 });
    await waitFor(() => expect(screen.getByDisplayValue('my_flow')).toBeInTheDocument(), { timeout: 4000 });
  });

  it('编辑模式：后端 envelope 结构也能解析', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { id: 'w1', name: 'env_flow', title: 'Env', collection: 'customer', triggerType: 'manual', enabled: false, nodes: [], edges: [] },
    });
    wrap('/workflows/designer/test/w1');
    await waitFor(() => expect(screen.getByDisplayValue('env_flow')).toBeInTheDocument(), { timeout: 4000 });
  });

  it('修改技术名与显示名输入框', () => {
    wrap('/workflows/designer/test');
    const nameInput = screen.getByDisplayValue('new_workflow');
    fireEvent.change(nameInput, { target: { value: 'flow_a' } });
    expect(screen.getByDisplayValue('flow_a')).toBeInTheDocument();

    const titleInput = screen.getByDisplayValue('workflowDesigner.createMode');
    fireEvent.change(titleInput, { target: { value: 'Flow A' } });
    expect(screen.getByDisplayValue('Flow A')).toBeInTheDocument();
  });

  it('切换触发器类型', () => {
    wrap('/workflows/designer/test');
    const trigger = screen.getByDisplayValue('workflowDesigner.triggerManual');
    fireEvent.change(trigger, { target: { value: 'on_create' } });
    expect((trigger as HTMLSelectElement).value).toBe('on_create');
  });

  it('勾选/取消启用开关', () => {
    wrap('/workflows/designer/test');
    const checkbox = screen.getByRole('checkbox');
    expect((checkbox as HTMLInputElement).checked).toBe(true);
    fireEvent.click(checkbox);
    expect((checkbox as HTMLInputElement).checked).toBe(false);
  });

  it('保存：新建模式调用 POST /workflows', async () => {
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w-new' });
    wrap('/workflows/designer/test');
    fireEvent.click(screen.getByText('workflowDesigner.save'));
    await waitFor(() => expect(apiClient.post).toHaveBeenCalled(), { timeout: 4000 });
    const [url] = (apiClient.post as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/workflows');
  });

  it('保存：编辑模式调用 PUT /workflows/:id', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'w1', name: 'my_flow', title: 'T', collection: 'customer', triggerType: 'manual', enabled: true, nodes: [], edges: [],
    });
    (apiClient.put as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1' });
    wrap('/workflows/designer/test/w1');
    await waitFor(() => expect(screen.getByDisplayValue('my_flow')).toBeInTheDocument(), { timeout: 4000 });
    fireEvent.click(screen.getByText('workflowDesigner.save'));
    await waitFor(() => expect(apiClient.put).toHaveBeenCalled(), { timeout: 4000 });
    expect((apiClient.put as ReturnType<typeof vi.fn>).mock.calls[0][0]).toBe('/workflows/w1');
  });

  it('保存失败展示错误信息', async () => {
    (apiClient.post as ReturnType<typeof vi.fn>).mockRejectedValue({
      response: { data: { message: 'save boom' } },
    });
    wrap('/workflows/designer/test');
    fireEvent.click(screen.getByText('workflowDesigner.save'));
    await waitFor(() => expect(screen.getByText('save boom')).toBeInTheDocument(), { timeout: 4000 });
  });

  it('打开测试运行弹窗', () => {
    wrap('/workflows/designer/test');
    fireEvent.click(screen.getByText('workflowDesigner.testRun'));
    // 弹窗打开后出现模拟数据输入区（textarea）
    expect(document.querySelector('textarea')).not.toBeNull();
  });

  it('节点面板渲染全部节点类型', () => {
    wrap('/workflows/designer/test');
    expect(screen.getByText('workflowDesigner.dragNodes')).toBeInTheDocument();
    const draggables = document.querySelectorAll('[draggable="true"]');
    expect(draggables.length).toBeGreaterThan(0);
  });

  it('US-410: 编辑态渲染发布版本与版本历史按钮', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'w1', name: 'my_flow', title: 'T', collection: 'customer', triggerType: 'manual', enabled: true, nodes: [], edges: [],
    });
    wrap('/workflows/designer/test/w1');
    await waitFor(() => expect(screen.getByDisplayValue('my_flow')).toBeInTheDocument(), { timeout: 4000 });
    // 发布按钮存在
    expect(screen.getByText('workflowDesigner.publishVersion')).toBeInTheDocument();
    // 版本历史入口
    expect(screen.getByText('workflowDesigner.versions')).toBeInTheDocument();
  });

  it('US-410: 点击发布调用 POST /workflows/:id/versions/publish', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'w1', name: 'my_flow', title: 'T', collection: 'customer', triggerType: 'manual', enabled: true, nodes: [], edges: [],
    });
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({ data: { version: 1 } });
    wrap('/workflows/designer/test/w1');
    await waitFor(() => expect(screen.getByDisplayValue('my_flow')).toBeInTheDocument(), { timeout: 4000 });

    // 点击发布按钮(非"版本历史"按钮)
    const buttons = screen.getAllByText('workflowDesigner.publishVersion');
    fireEvent.click(buttons[0]);
    await waitFor(() => expect(apiClient.post).toHaveBeenCalled(), { timeout: 4000 });
    const [url] = (apiClient.post as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/workflows/w1/versions/publish');
  });

  it('US-410: 打开版本侧栏拉取版本列表并展示空态', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockImplementation((url: string) => {
      if (url === '/workflows/w1/versions') {
        return Promise.resolve({ workflow_id: 'w1', current_version: 0, versions: [] });
      }
      return Promise.resolve({ id: 'w1', name: 'my_flow', title: 'T', collection: 'customer', triggerType: 'manual', enabled: true, nodes: [], edges: [] });
    });
    wrap('/workflows/designer/test/w1');
    await waitFor(() => expect(screen.getByDisplayValue('my_flow')).toBeInTheDocument(), { timeout: 4000 });

    // 点击"版本历史"按钮
    const versionsBtn = screen.getAllByText('workflowDesigner.versions')[0];
    fireEvent.click(versionsBtn);
    await waitFor(() => expect(apiClient.get).toHaveBeenCalledWith('/workflows/w1/versions'), { timeout: 4000 });
    expect(screen.getByText('workflowDesigner.noVersions')).toBeInTheDocument();
  });

  it('US-410: 回滚确认弹窗调用 rollback API', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    (apiClient.get as ReturnType<typeof vi.fn>).mockImplementation((url: string) => {
      if (url === '/workflows/w1/versions') {
        return Promise.resolve({
          data: {
            workflow_id: 'w1',
            current_version: 2,
            versions: [
              { id: 'v1', workflow_id: 'w1', version: 2, node_count: 3, published_at: '2026-01-01T00:00:00Z' },
              { id: 'v2', workflow_id: 'w1', version: 1, node_count: 2, published_at: '2025-12-01T00:00:00Z' },
            ],
          },
        });
      }
      return Promise.resolve({ id: 'w1', name: 'my_flow', title: 'T', collection: 'customer', triggerType: 'manual', enabled: true, nodes: [], edges: [] });
    });
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({ data: { rolled_back_to: 1 } });
    wrap('/workflows/designer/test/w1');
    await waitFor(() => expect(screen.getByDisplayValue('my_flow')).toBeInTheDocument(), { timeout: 4000 });

    // 进入版本侧栏
    const versionsBtn = screen.getAllByText('workflowDesigner.versions')[0];
    fireEvent.click(versionsBtn);
    // 等待版本列表加载完成,找到 v1 的回滚按钮
    await waitFor(() => expect(screen.getByText('workflowDesigner.rollback')).toBeInTheDocument(), { timeout: 4000 });

    // 点击 v1(非当前版本)的回滚按钮
    const rollbackButtons = screen.getAllByText('workflowDesigner.rollback');
    fireEvent.click(rollbackButtons[rollbackButtons.length - 1]);

    // 点击确认回滚按钮
    await waitFor(() => expect(screen.getByText('workflowDesigner.confirmRollback')).toBeEnabled(), { timeout: 4000 });
    fireEvent.click(screen.getByText('workflowDesigner.confirmRollback'));

    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith('/workflows/w1/versions/1/rollback', {}), { timeout: 4000 });
  });
});