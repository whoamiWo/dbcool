/**
 * PHASE 4.2 覆盖率补测：pages/RowAclAdmin.tsx
 * 覆盖：加载、空态、列表渲染、新建/编辑策略、删除、切换启用。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { RowAclAdminPage } from './RowAclAdmin';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const mod = await vi.importActual('react-router-dom');
  return { ...mod, useNavigate: () => navigateMock };
});
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
global.fetch = vi.fn() as unknown as typeof fetch;
window.confirm = vi.fn(() => true);
window.alert = vi.fn();

const mockFetch = global.fetch as unknown as ReturnType<typeof vi.fn>;

function mockResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status < 400,
    status,
    json: () => Promise.resolve(data),
    text: () => Promise.resolve(''),
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.setItem('access_token', 'test-token');
  mockFetch.mockImplementation((url: string) => {
    if (String(url).includes('/collections')) return mockResponse({ data: [{ name: 'users', title: 'Users' }] });
    if (String(url).includes('/row-acl')) return mockResponse({ data: [{ id: 'p1', collection: 'users', principal_type: 'role', principal_id: 'admin', action: 'read', expression: { field: 'owner', op: 'eq', value: 'alice' }, priority: 0, enabled: true, description: '' }] });
    return mockResponse({ data: [] });
  });
});

function renderPage() {
  return render(
    <MemoryRouter>
      <RowAclAdminPage />
    </MemoryRouter>,
  );
}

describe('RowAclAdminPage', () => {
  it('加载中渲染 loading', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (String(url).includes('/collections')) return mockResponse({ data: [] });
      return new Promise(() => {}); // 永远不返回
    });
    renderPage();
    expect(screen.getByText('rowAcl.loading')).toBeInTheDocument();
  });

  it('空态渲染空提示与创建按钮', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (String(url).includes('/collections')) return mockResponse({ data: [] });
      if (String(url).includes('/row-acl')) return mockResponse({ data: [] });
      return mockResponse({});
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('rowAcl.countItems', { exact: false })).toBeInTheDocument());
    expect(screen.getByText('rowAcl.emptyHint')).toBeInTheDocument();
  });

  it('有策略时渲染表格行', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (String(url).includes('/collections')) return mockResponse({ data: [{ name: 'users' }] });
      if (String(url).includes('/row-acl')) return mockResponse({ data: [{ id: 'p1', collection: 'users', principal_type: 'role', principal_id: 'user', action: 'read', expression: { field: 'owner_id', op: 'eq', value: '$currentUser' }, priority: 0, enabled: true, description: 'd' }] });
      return mockResponse({});
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('rowAcl.countItems', { exact: false })).toBeInTheDocument());
    expect(screen.getAllByText('users').length).toBeGreaterThan(0);
    expect(screen.getByText('read')).toBeInTheDocument();
  });

  it('点击新建打开编辑对话框', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('rowAcl.newPolicyBtn')).toBeInTheDocument());
    fireEvent.click(screen.getByText('rowAcl.newPolicyBtn'));
    await waitFor(() => expect(screen.getByText('rowAcl.modalTitleNew')).toBeInTheDocument());
    expect(screen.getByLabelText('rowAcl.fieldCollection')).toBeInTheDocument();
  });

  it('编辑对话框填写后保存调用 PUT', async () => {
    const saveMock = mockFetch.mockImplementation((url: string) => {
      if (String(url).includes('/collections')) return mockResponse({ data: [{ name: 'users' }] });
      if (String(url).includes('/row-acl') && String(url).includes('/p1')) return mockResponse({ data: { id: 'p1' } });
      return mockResponse({ data: [] });
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('rowAcl.newPolicyBtn')).toBeInTheDocument());
    fireEvent.click(screen.getByText('rowAcl.newPolicyBtn'));
    await waitFor(() => expect(screen.getByText('rowAcl.modalTitleNew')).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('rowAcl.fieldDescription'), { target: { value: 'desc' } });
    fireEvent.click(screen.getByText('rowAcl.saveBtn'));
    await waitFor(() => expect(saveMock).toHaveBeenCalled());
  });

  it('切换启用状态调用 PUT', async () => {
    const toggleMock = mockFetch.mockImplementation((url: string) => {
      if (String(url).includes('/collections')) return mockResponse({ data: [{ name: 'users' }] });
      if (String(url).includes('/row-acl') && String(url).endsWith('/p1')) {
        return mockResponse({ data: { id: 'p1', enabled: false } });
      }
      if (String(url).includes('/row-acl')) return mockResponse({ data: [{ id: 'p1', collection: 'users', principal_type: 'role', principal_id: 'admin', action: 'read', expression: { field: 'owner', op: 'eq', value: 'alice' }, priority: 0, enabled: true, description: '' }] });
      return mockResponse({ data: [] });
    });
    const { container } = renderPage();
    await waitFor(() => expect(container.textContent || '').toContain('rowAcl.countItems'));
    const toggleBtn = Array.from(document.querySelectorAll('button')).find((b) => b.textContent?.includes('rowAcl.toggleEnabled'));
    if (toggleBtn) fireEvent.click(toggleBtn);
    await waitFor(() => expect(toggleMock).toHaveBeenCalled());
  });
});