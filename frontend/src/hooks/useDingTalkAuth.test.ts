/**
 * PHASE 4.2 覆盖率补测：hooks/useDingTalkAuth.ts
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSearchParams } from 'react-router-dom';
import { useDingTalkAuth } from './useDingTalkAuth';

vi.mock('react-router-dom', () => ({
  useSearchParams: vi.fn(),
}));

vi.mock('@/services/dingtalk', () => ({
  handleDingTalkCallback: vi.fn(),
  handleDingTalkLogin: vi.fn(),
  isInDingTalk: vi.fn(() => false),
}));

import { handleDingTalkCallback, handleDingTalkLogin, isInDingTalk } from '@/services/dingtalk';

beforeEach(() => {
  vi.clearAllMocks();
  (useSearchParams as ReturnType<typeof vi.fn>).mockReturnValue([
    { get: () => null },
    vi.fn(),
  ]);
});

describe('useDingTalkAuth', () => {
  it('初始状态 loading=false, error=null, isInDingTalk=false', () => {
    (isInDingTalk as ReturnType<typeof vi.fn>).mockReturnValue(false);
    const { result } = renderHook(() => useDingTalkAuth());
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
    expect(result.current.isInDingTalk).toBe(false);
  });

  it('login 成功后 loading 重置为 false', async () => {
    (handleDingTalkLogin as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    const { result } = renderHook(() => useDingTalkAuth());
    await act(async () => {
      await result.current.login();
    });
    expect(handleDingTalkLogin).toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('login 失败时设置错误信息', async () => {
    (handleDingTalkLogin as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('login fail'));
    const { result } = renderHook(() => useDingTalkAuth());
    await act(async () => {
      await result.current.login();
    });
    expect(result.current.error).toBe('login fail');
    expect(result.current.loading).toBe(false);
  });

  it('handleCallback 无 code 时不执行', async () => {
    (useSearchParams as ReturnType<typeof vi.fn>).mockReturnValue([
      { get: () => null },
      vi.fn(),
    ]);
    const { result } = renderHook(() => useDingTalkAuth());
    await act(async () => {
      await result.current.handleCallback();
    });
    expect(handleDingTalkCallback).not.toHaveBeenCalled();
  });

  it('handleCallback 有 code 时调用服务并处理错误', async () => {
    const mockGet = vi.fn().mockReturnValue('code123');
    (useSearchParams as ReturnType<typeof vi.fn>).mockReturnValue([
      { get: mockGet },
      vi.fn(),
    ]);
    (handleDingTalkCallback as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('callback fail'));
    const { result } = renderHook(() => useDingTalkAuth());
    await act(async () => {
      await result.current.handleCallback();
    });
    expect(handleDingTalkCallback).toHaveBeenCalledWith('code123');
    expect(result.current.error).toBe('callback fail');
    expect(result.current.loading).toBe(false);
  });

  it('handleCallback 非 Error 异常时显示默认文案', async () => {
    const mockGet = vi.fn().mockReturnValue('code123');
    (useSearchParams as ReturnType<typeof vi.fn>).mockReturnValue([
      { get: mockGet },
      vi.fn(),
    ]);
    (handleDingTalkCallback as ReturnType<typeof vi.fn>).mockRejectedValue('string error');
    const { result } = renderHook(() => useDingTalkAuth());
    await act(async () => {
      await result.current.handleCallback();
    });
    expect(result.current.error).toBe('登录回调处理失败');
  });

  it('login 非 Error 异常时显示默认文案', async () => {
    (handleDingTalkLogin as ReturnType<typeof vi.fn>).mockRejectedValue('string error');
    const { result } = renderHook(() => useDingTalkAuth());
    await act(async () => {
      await result.current.login();
    });
    expect(result.current.error).toBe('钉钉登录失败');
  });
});
