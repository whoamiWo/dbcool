/**
 * PHASE 4.2 覆盖率补测：pages/IntegrationsPage.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import IntegrationsPage from './IntegrationsPage';

vi.mock('../api/integrations', () => ({
  integrationApi: {
    getMarket: vi.fn(),
    install: vi.fn(),
    uninstall: vi.fn(),
  },
}));

import { integrationApi } from '../api/integrations';

const mockIntegrations = [
  { id: 'dingtalk', name: '钉钉', kind: 'builtin', installed: false, description: 'Desc' },
  { id: 'wecom', name: '企业微信', kind: 'plugin', installed: true, description: 'Desc2' },
];

beforeEach(() => {
  vi.clearAllMocks();
});

function renderPage() {
  return render(<IntegrationsPage />, { wrapper: MemoryRouter });
}

/** 按集成名称定位其卡片内的操作按钮 */
function buttonOf(name: string): HTMLElement {
  const heading = screen.getByText(name);
  let node: HTMLElement | null = heading;
  while (node && !node.querySelector('button')) node = node.parentElement;
  const btn = node?.querySelector('button');
  if (!btn) throw new Error(`button not found for ${name}`);
  return btn as HTMLElement;
}

describe('IntegrationsPage', () => {
  it('加载时显示进度指示器', async () => {
    (integrationApi.getMarket as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 0, data: {} });
    renderPage();
    expect(screen.getByRole('progressbar')).toBeInTheDocument();
  });

  it('加载成功后显示集成卡片', async () => {
    (integrationApi.getMarket as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { integrations: mockIntegrations },
    });
    renderPage();
    await waitFor(() => {
      expect(screen.getByText('钉钉')).toBeInTheDocument();
      expect(screen.getByText('企业微信')).toBeInTheDocument();
    });
  });

  it('点击安装按钮调用 install 并刷新', async () => {
    (integrationApi.getMarket as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { integrations: mockIntegrations },
    });
    (integrationApi.install as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 0 });
    renderPage();
    await waitFor(() => expect(screen.getByText('钉钉')).toBeInTheDocument());
    fireEvent.click(buttonOf('钉钉'));
    await waitFor(() => {
      expect(integrationApi.install).toHaveBeenCalledWith('dingtalk');
    });
  });

  it('安装返回非 0 code 时弹出提示', async () => {
    (integrationApi.getMarket as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { integrations: mockIntegrations },
    });
    (integrationApi.install as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 1, message: 'boom' });
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    renderPage();
    await waitFor(() => expect(screen.getByText('钉钉')).toBeInTheDocument());
    fireEvent.click(buttonOf('钉钉'));
    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('boom'));
  });

  it('卸载取消时不调用 uninstall', async () => {
    (integrationApi.getMarket as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { integrations: mockIntegrations },
    });
    renderPage();
    await waitFor(() => expect(screen.getByText('企业微信')).toBeInTheDocument());
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    fireEvent.click(buttonOf('企业微信'));
    expect(integrationApi.uninstall).not.toHaveBeenCalled();
  });

  it('确认卸载后调用 uninstall 并刷新', async () => {
    (integrationApi.getMarket as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { integrations: mockIntegrations },
    });
    (integrationApi.uninstall as ReturnType<typeof vi.fn>).mockResolvedValue({ code: 0 });
    renderPage();
    await waitFor(() => expect(screen.getByText('企业微信')).toBeInTheDocument());
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    fireEvent.click(buttonOf('企业微信'));
    await waitFor(() => {
      expect(integrationApi.uninstall).toHaveBeenCalledWith('wecom');
    });
  });

  it('卸载抛异常时弹出提示', async () => {
    (integrationApi.getMarket as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { integrations: mockIntegrations },
    });
    (integrationApi.uninstall as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('net down'));
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    renderPage();
    await waitFor(() => expect(screen.getByText('企业微信')).toBeInTheDocument());
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    fireEvent.click(buttonOf('企业微信'));
    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('net down'));
  });

  it('加载失败显示错误 Alert', async () => {
    (integrationApi.getMarket as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('network'));
    renderPage();
    await waitFor(() => {
      expect(screen.getByText('network')).toBeInTheDocument();
    });
  });

  it('空列表显示空状态提示', async () => {
    (integrationApi.getMarket as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 0,
      data: { integrations: [] },
    });
    renderPage();
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });
  });

  it('getMarket 返回非 0 code 时显示 message', async () => {
    (integrationApi.getMarket as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: 500,
      message: 'Server exploded',
    });
    renderPage();
    await waitFor(() => {
      expect(screen.getByText('Server exploded')).toBeInTheDocument();
    });
  });
});
