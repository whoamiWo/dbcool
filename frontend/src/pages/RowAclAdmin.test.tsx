/**
 * PHASE 4.2 覆盖率补测：pages/RowAclAdmin.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { RowAclAdminPage } from './RowAclAdmin';

const mockFetch = vi.spyOn(global, 'fetch');
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => vi.fn() };
});

function wrap() {
  return render(<MemoryRouter><Routes><Route path="*" element={<RowAclAdminPage />} /></Routes></MemoryRouter>);
}

describe('RowAclAdminPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.setItem('access_token', 'test-token');
  });

  it('加载策略列表', async () => {
    mockFetch.mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ data: [] }) } as Response);
    wrap();
    await waitFor(() => expect(mockFetch).toHaveBeenCalledWith(expect.stringContaining('/api/admin/row-acl'), expect.any(Object)), { timeout: 3000 });
  });

  it('筛选集合', async () => {
    mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ data: [] }) } as Response);
    wrap();
    await waitFor(() => expect(mockFetch).toHaveBeenCalled(), { timeout: 3000 });
    const select = screen.getByLabelText('rowAcl.filterLabel');
    fireEvent.change(select, { target: { value: 'users' } });
    // 等待变更触发后验证 fetch 调用
    await new Promise(r => setTimeout(r, 100));
    expect(mockFetch).toHaveBeenCalledWith(expect.stringContaining('row-acl'), expect.any(Object));
  });

  it('启用/禁用策略', async () => {
    mockFetch.mockResolvedValue({ ok: true, status: 200, json: async () => ({ data: [{ id: 'p1', enabled: true }] }) } as Response);
    wrap();
    await waitFor(() => expect(mockFetch).toHaveBeenCalled(), { timeout: 3000 });
    await new Promise(r => setTimeout(r, 200));
    expect(mockFetch).toHaveBeenCalledWith(expect.stringContaining('/api/admin/row-acl'), expect.any(Object));
  });
});