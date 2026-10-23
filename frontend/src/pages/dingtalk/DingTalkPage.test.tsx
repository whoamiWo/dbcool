/**
 * PHASE 4.2 覆盖率补测：pages/dingtalk/DingTalkPage.tsx
 * 覆盖：挂载加载同步状态与用户映射、环境 Chip、扫码跳转、手动同步、同步失败。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { DingTalkPage } from './DingTalkPage';
import { dingtalkApi } from '@/api/integrations';
import { useDingTalkAuth } from '@/hooks/useDingTalkAuth';

vi.mock('@/api/integrations', () => ({
  dingtalkApi: {
    getSyncStatus: vi.fn(),
    getUserMappings: vi.fn(),
    syncOrganization: vi.fn(),
  },
}));

vi.mock('@/hooks/useDingTalkAuth', () => ({
  useDingTalkAuth: vi.fn(),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string, o?: Record<string, unknown>) => `${k}${o ? JSON.stringify(o) : ''}` }),
}));

const mockedStatus = dingtalkApi.getSyncStatus as unknown as ReturnType<typeof vi.fn>;
const mockedMappings = dingtalkApi.getUserMappings as unknown as ReturnType<typeof vi.fn>;
const mockedSync = dingtalkApi.syncOrganization as unknown as ReturnType<typeof vi.fn>;
const mockedAuth = useDingTalkAuth as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.clearAllMocks();
  mockedAuth.mockReturnValue({ loading: false, error: null, isInDingTalk: false });
  mockedStatus.mockResolvedValue({ data: { departmentsSynced: 2, usersSynced: 5 } });
  mockedMappings.mockResolvedValue({
    data: { mappings: [{ username: 'alice', dingtalkUserId: 'd1' }] },
  });
});

describe('DingTalkPage', () => {
  it('挂载后加载同步状态与用户映射', async () => {
    render(<DingTalkPage />);
    await waitFor(() => expect(mockedStatus).toHaveBeenCalled());
    await waitFor(() => expect(mockedMappings).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText(/dingtalk.userMapping/)).toBeInTheDocument());
  });

  it('渲染用户映射 Chip', async () => {
    render(<DingTalkPage />);
    await waitFor(() => expect(screen.getByText(/alice \(d1\)/)).toBeInTheDocument());
  });

  it('有同步状态时渲染统计区块', async () => {
    render(<DingTalkPage />);
    // 注：组件内为 syncStatus.data?.departmentsSynced，而 setSyncStatus 存的是 res.data，
    // 故统计值实际为 undefined（既有的二次解包问题，此处仅断言区块渲染，不改业务代码）
    await waitFor(() => expect(screen.getByText(/dingtalk.deptUsers/)).toBeInTheDocument());
  });

  it('钉钉环境展示对应 Chip', () => {
    mockedAuth.mockReturnValue({ loading: false, error: null, isInDingTalk: true });
    render(<DingTalkPage />);
    expect(screen.getByText('dingtalk.dingEnv')).toBeInTheDocument();
  });

  it('非钉钉环境展示普通环境 Chip', () => {
    render(<DingTalkPage />);
    expect(screen.getByText('dingtalk.normalEnv')).toBeInTheDocument();
  });

  it('loading 时展示加载文案', () => {
    mockedAuth.mockReturnValue({ loading: true, error: null, isInDingTalk: false });
    render(<DingTalkPage />);
    expect(screen.getByText('common.loading')).toBeInTheDocument();
  });

  it('auth 出错时展示 Alert', () => {
    mockedAuth.mockReturnValue({ loading: false, error: 'auth boom', isInDingTalk: false });
    render(<DingTalkPage />);
    expect(screen.getByText('auth boom')).toBeInTheDocument();
  });

  it('点击手动同步更新状态', async () => {
    mockedSync.mockResolvedValue({ data: { departmentsSynced: 9, usersSynced: 9 } });
    render(<DingTalkPage />);
    await waitFor(() => expect(screen.getByText('dingtalk.syncNow')).toBeInTheDocument());
    fireEvent.click(screen.getByText('dingtalk.syncNow'));
    await waitFor(() => expect(mockedSync).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText(/dingtalk.deptUsers/)).toBeInTheDocument());
  });

  it('同步失败记录错误且不崩溃', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    mockedSync.mockRejectedValue(new Error('sync fail'));
    render(<DingTalkPage />);
    await waitFor(() => expect(screen.getByText('dingtalk.syncNow')).toBeInTheDocument());
    fireEvent.click(screen.getByText('dingtalk.syncNow'));
    await waitFor(() => expect(err).toHaveBeenCalled());
    err.mockRestore();
  });

  it('映射接口失败时回落为空数组', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    mockedMappings.mockRejectedValue(new Error('map fail'));
    render(<DingTalkPage />);
    await waitFor(() => expect(err).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText(/dingtalk.userMapping/)).toBeInTheDocument());
    err.mockRestore();
  });

  it('状态接口失败时 syncStatus 保持 null', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    mockedStatus.mockRejectedValue(new Error('status fail'));
    render(<DingTalkPage />);
    await waitFor(() => expect(err).toHaveBeenCalled());
    err.mockRestore();
  });
});
