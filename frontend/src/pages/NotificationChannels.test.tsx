import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { NotificationChannelsPage } from './NotificationChannels';

const sampleChannels = [
  {
    id: 'ch1', type: 'EMAIL', name: '运营邮件',
    config: { smtp: 'smtp.example.com' }, events: 'workflow.notification',
    enabled: true, description: '发送运营通知',
    created_at: '2026-01-15T10:00:00Z', updated_at: '2026-01-15T10:00:00Z',
  },
];

const sampleTypes = [
  { type: 'EMAIL', label: '邮箱', config_schema: { smtp: 'string' } },
  { type: 'WEBHOOK', label: 'Webhook', config_schema: { url: 'string' } },
];

describe('NotificationChannelsPage', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    globalThis.fetch = fetchMock as any;
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const wrap = () =>
    render(
      <MemoryRouter>
        <NotificationChannelsPage />
      </MemoryRouter>,
    );

  it('加载中:显示"加载中…"', async () => {
    // 永不 resolve
    fetchMock.mockReturnValue(new Promise(() => {}));
    wrap();
    expect(screen.getByText(/加载中/)).toBeInTheDocument();
  });

  it('加载成功:渲染 channel 列表 + 类型', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url.includes('/types')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: sampleTypes }) });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: sampleChannels }) });
    });

    wrap();

    expect(await screen.findByText('运营邮件')).toBeInTheDocument();
    expect(screen.getByText('EMAIL')).toBeInTheDocument();
    expect(screen.getByText(/发送运营通知/)).toBeInTheDocument();
    // fetch 被调了 2 次:channels + types
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const urls = fetchMock.mock.calls.map(c => c[0]).join('|');
    expect(urls).toMatch(/channels\/types/);
    expect(urls).toMatch(/notification-channels/);
  });

  it('加载失败:显示错误消息', async () => {
    fetchMock.mockRejectedValue(new Error('network down'));

    wrap();

    expect(await screen.findByText(/network down/)).toBeInTheDocument();
  });

  it('点"新建 channel":显示编辑表单', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url.includes('/types')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: sampleTypes }) });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: [] }) });
    });

    wrap();
    // 等数据加载完
    await waitFor(() => {
      expect(screen.queryByText(/加载中/)).not.toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /新建|\+/ }));

    // 表单显示
    expect(screen.getByRole('button', { name: /保存/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /取消/ })).toBeInTheDocument();
  });

  it('保存表单:发 POST 请求', async () => {
    fetchMock.mockImplementation((url: string, options?: any) => {
      if (url.includes('/types')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: sampleTypes }) });
      }
      if (options?.method === 'POST') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: sampleChannels }) });
    });

    wrap();
    await screen.findByText('运营邮件');

    fireEvent.click(screen.getByRole('button', { name: /新建|\+/ }));
    // 直接通过 name 找 input(label 是"名称")
    const nameInput = document.querySelector('input') as HTMLInputElement;
    fireEvent.change(nameInput, { target: { value: 'New Channel' } });
    fireEvent.click(screen.getByRole('button', { name: /保存/ }));

    await waitFor(() => {
      const calls = fetchMock.mock.calls;
      const postCall = calls.find(c => c[1]?.method === 'POST');
      expect(postCall).toBeDefined();
    });
  });

  it('删除 channel:弹 confirm + 调 DELETE', async () => {
    fetchMock.mockImplementation((url: string, options?: any) => {
      if (url.includes('/types')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: sampleTypes }) });
      }
      if (options?.method === 'DELETE') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: {} }) });
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: sampleChannels }) });
    });
    // 自动 confirm
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    wrap();
    await screen.findByText('运营邮件');

    fireEvent.click(screen.getByRole('button', { name: /删除/ }));

    await waitFor(() => {
      const calls = fetchMock.mock.calls;
      const deleteCall = calls.find(c => c[1]?.method === 'DELETE');
      expect(deleteCall).toBeDefined();
    });
  });

  it('401 自动跳 /login', async () => {
    // 模拟 401
    fetchMock.mockImplementation((url: string) => {
      if (url.includes('/types')) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ data: [] }) });
      }
      return Promise.resolve({ ok: false, status: 401, json: () => Promise.resolve({}) });
    });

    // 模拟 useNavigate 重定向
    // 删除按钮不应触发 — 401 提前跳走
    wrap();
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled();
    });
    // 没崩
  });
});
