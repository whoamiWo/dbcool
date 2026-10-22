/**
 * PHASE 4.2 覆盖率补测：pages/AclEditor.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AclEditorPage } from './AclEditor';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

import apiClient from '@/api/client';

function renderView(roleId?: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[roleId ? `/admin/acl?roleId=${roleId}` : '/admin/acl']}>
        <Routes>
          <Route path="/admin/acl" element={<AclEditorPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('AclEditorPage', () => {
  it('无 roleId 参数时显示引导提示', () => {
    renderView();
    expect(screen.getByText(/选择一个角色/i)).toBeInTheDocument();
  });

  it('有 roleId 时显示角色名称', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce([{ id: 'r1', name: 'Admin' }])
      .mockResolvedValueOnce([]);

    renderView('r1');
    await waitFor(() => {
      expect(screen.getByText(/Admin/i)).toBeInTheDocument();
    });
  });

  it('创建 FIELD 类型策略调用 POST', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce([{ id: 'r1', name: 'Admin' }])
      .mockResolvedValueOnce([]);
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'p1' });

    renderView('r1');
    await waitFor(() => expect(screen.getByText(/Admin/i)).toBeInTheDocument());
    
    // 切换类型为 FIELD（默认已是）
    const addBtn = screen.getByRole('button', { name: /添加/i });
    fireEvent.click(addBtn);
    
    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith('/admin/acl', expect.objectContaining({
        roleId: 'r1',
        type: 'FIELD',
      }));
    });
  });

  it('创建 ROW 类型策略调用 POST 并传入 filters', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce([{ id: 'r1', name: 'Admin' }])
      .mockResolvedValueOnce([]);
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'p1' });

    renderView('r1');
    await waitFor(() => expect(screen.getByText(/Admin/i)).toBeInTheDocument());

    // 选择 ROW 类型（第二个 combobox 是类型选择器）
    const selects = screen.getAllByRole('combobox') as HTMLSelectElement[];
    fireEvent.change(selects[1], { target: { value: 'ROW' } });
    
    // 填写 filter
    const filterInputs = screen.getAllByRole('textbox');
    if (filterInputs[0]) fireEvent.change(filterInputs[0], { target: { value: 'dept' } });
    if (filterInputs[1]) fireEvent.change(filterInputs[1], { target: { value: 'sales' } });
    
    const addBtn = screen.getByRole('button', { name: /添加/i });
    fireEvent.click(addBtn);
    
    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith('/admin/acl', expect.objectContaining({
        type: 'ROW',
        config: expect.stringContaining('filters'),
      }));
    });
  });

  it('删除策略调用 DELETE', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce([{ id: 'r1', name: 'Admin' }])
      .mockResolvedValueOnce([
        { id: 'p1', type: 'FIELD', subject: 'customer', action: null, config_json: '{}', collection: 'orders' },
      ]);
    (apiClient.delete as ReturnType<typeof vi.fn>).mockResolvedValue({});

    renderView('r1');
    await waitFor(() => expect(screen.getByText(/Admin/i)).toBeInTheDocument());
    
    fireEvent.click(screen.getByRole('button', { name: /删/i }));
    
    await waitFor(() => {
      expect(apiClient.delete).toHaveBeenCalledWith('/admin/acl/p1');
    });
  });

  it('创建失败时显示错误提示', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce([{ id: 'r1', name: 'Admin' }])
      .mockResolvedValueOnce([]);
    (apiClient.post as ReturnType<typeof vi.fn>).mockRejectedValue({
      response: { data: { message: '权限不足' } },
    });

    renderView('r1');
    await waitFor(() => expect(screen.getByText(/Admin/i)).toBeInTheDocument());
    
    const addBtn = screen.getByRole('button', { name: /添加/i });
    fireEvent.click(addBtn);
    
    await waitFor(() => {
      expect(screen.getByText('权限不足')).toBeInTheDocument();
    });
  });
});
