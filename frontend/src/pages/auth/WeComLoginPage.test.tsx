/**
 * PHASE 4.2 覆盖率补测：pages/auth/WeComLoginPage.tsx
 * 覆盖：初始渲染、成功跳转企微授权页、后端返回非 0 抛错、异常分支回调 onError。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { WeComLoginPage } from './WeComLoginPage';
import { wecomApi } from '@/api/integrations';

vi.mock('@/api/integrations', () => ({
  wecomApi: { getAuthUrl: vi.fn() },
}));

const mockedAuthUrl = wecomApi.getAuthUrl as unknown as ReturnType<typeof vi.fn>;

/** jsdom 的 location.href 赋值会触发 "Not implemented: navigation"，需可写桩。 */
function stubLocation() {
  const original = window.location;
  Object.defineProperty(window, 'location', {
    configurable: true,
    writable: true,
    value: { href: '', origin: 'http://localhost', protocol: 'http:', host: 'localhost' },
  });
  return () => {
    Object.defineProperty(window, 'location', { configurable: true, writable: true, value: original });
  };
}

let restore: () => void;

beforeEach(() => {
  vi.clearAllMocks();
  restore = stubLocation();
});

afterEach(() => {
  restore();
});

describe('WeComLoginPage', () => {
  it('渲染标题与登录按钮', () => {
    render(<WeComLoginPage />);
    expect(screen.getByText('企业微信登录')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /企业微信扫码登录/ })).toBeInTheDocument();
  });

  it('点击后获取 authUrl 并跳转', async () => {
    mockedAuthUrl.mockResolvedValue({
      code: 0,
      data: { authUrl: 'https://wecom/sso' },
    });
    render(<WeComLoginPage />);
    fireEvent.click(screen.getByRole('button', { name: /企业微信扫码登录/ }));
    await waitFor(() => expect(mockedAuthUrl).toHaveBeenCalled());
    await waitFor(() =>
      expect((window.location as unknown as { href: string }).href).toBe('https://wecom/sso'),
    );
  });

  it('后端返回非 0 时展示错误并回调 onError', async () => {
    mockedAuthUrl.mockResolvedValue({ code: 1, message: '未配置企微' });
    const onError = vi.fn();
    render(<WeComLoginPage onError={onError} />);
    fireEvent.click(screen.getByRole('button', { name: /企业微信扫码登录/ }));
    await waitFor(() => expect(screen.getByText('未配置企微')).toBeInTheDocument());
    expect(onError).toHaveBeenCalledWith('未配置企微');
  });

  it('code=0 但无 authUrl 时抛出默认错误', async () => {
    mockedAuthUrl.mockResolvedValue({ code: 0, data: {} });
    render(<WeComLoginPage />);
    fireEvent.click(screen.getByRole('button', { name: /企业微信扫码登录/ }));
    await waitFor(() => expect(screen.getByText('获取企微授权链接失败')).toBeInTheDocument());
  });

  it('接口抛异常时展示兜底错误', async () => {
    mockedAuthUrl.mockRejectedValue('boom');
    render(<WeComLoginPage />);
    fireEvent.click(screen.getByRole('button', { name: /企业微信扫码登录/ }));
    await waitFor(() => expect(screen.getByText('企业微信登录失败')).toBeInTheDocument());
  });
});
