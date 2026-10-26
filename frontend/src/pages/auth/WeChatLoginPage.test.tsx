/**
 * PHASE B B2 覆盖率补测：pages/auth/WeChatLoginPage.tsx
 * 覆盖：初始渲染、成功跳转微信授权页、后端返回非 0 抛错、异常分支回调 onError。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { WeChatLoginPage } from './WeChatLoginPage';
import { wechatApi } from '@/api/integrations';

vi.mock('@/api/integrations', () => ({
  wechatApi: { getAuthUrl: vi.fn() },
}));

const mockedAuthUrl = wechatApi.getAuthUrl as unknown as ReturnType<typeof vi.fn>;

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

describe('WeChatLoginPage', () => {
  it('渲染标题与登录按钮', () => {
    render(<WeChatLoginPage />);
    expect(screen.getByText('微信登录')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /微信扫码登录/ })).toBeInTheDocument();
  });

  it('点击后获取 authUrl 并跳转', async () => {
    mockedAuthUrl.mockResolvedValue({
      code: 0,
      data: { authUrl: 'https://open.weixin.qq.com/connect/oauth2/authorize' },
    });
    render(<WeChatLoginPage />);
    fireEvent.click(screen.getByRole('button', { name: /微信扫码登录/ }));
    await waitFor(() => expect(mockedAuthUrl).toHaveBeenCalled());
    await waitFor(() =>
      expect((window.location as unknown as { href: string }).href).toBe(
        'https://open.weixin.qq.com/connect/oauth2/authorize',
      ),
    );
  });

  it('后端返回非 0 时展示错误并回调 onError', async () => {
    mockedAuthUrl.mockResolvedValue({ code: 1, message: '未配置微信' });
    const onError = vi.fn();
    render(<WeChatLoginPage onError={onError} />);
    fireEvent.click(screen.getByRole('button', { name: /微信扫码登录/ }));
    await waitFor(() => expect(screen.getByText('未配置微信')).toBeInTheDocument());
    expect(onError).toHaveBeenCalledWith('未配置微信');
  });

  it('code=0 但无 authUrl 时抛出默认错误', async () => {
    mockedAuthUrl.mockResolvedValue({ code: 0, data: {} });
    render(<WeChatLoginPage />);
    fireEvent.click(screen.getByRole('button', { name: /微信扫码登录/ }));
    await waitFor(() => expect(screen.getByText('获取微信授权链接失败')).toBeInTheDocument());
  });

  it('接口抛异常时展示兜底错误', async () => {
    mockedAuthUrl.mockRejectedValue('boom');
    render(<WeChatLoginPage />);
    fireEvent.click(screen.getByRole('button', { name: /微信扫码登录/ }));
    await waitFor(() => expect(screen.getByText('微信登录失败')).toBeInTheDocument());
  });
});
