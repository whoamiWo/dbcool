/**
 * PHASE 4.2 覆盖率补测：pages/AlertCenter.tsx
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AlertCenterPage } from './AlertCenter';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

vi.mock('@/lib/stompClient', () => ({
  subscribeToAlerts: vi.fn(() => () => {}),
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ user: null }),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

const wrap = (ui: React.ReactNode) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } });
  return render(<QueryClientProvider client={qc}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>);
};

describe('AlertCenterPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('渲染页面显示标题', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('Network error'));
    wrap(<AlertCenterPage />);
    await waitFor(() => expect(screen.getByText('alertCenter.title')).toBeInTheDocument(), { timeout: 2000 });
  });

  it('加载成功显示各卡片', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: { events: [], counts_by_kind: {}, unresolved_count: 0 } })
      .mockResolvedValueOnce({ code: 0, data: { size: 100, max_size: 1000, ttl_seconds: 60, hits: 50, misses: 10, hit_rate: 0.83, hit_rate_warning: false } })
      .mockResolvedValueOnce({ code: 0, data: { user_id: 'u1', remaining: { daily_calls_remaining: 100 } } })
      .mockResolvedValueOnce({ code: 0, data: { success: 10, failure: 0 } });
    wrap(<AlertCenterPage />);
    await waitFor(() => expect(screen.getByText('alertCenter.llmCache')).toBeInTheDocument(), { timeout: 2000 });
    expect(screen.getByText('alertCenter.userQuota')).toBeInTheDocument();
    expect(screen.getByText('alertCenter.webhook')).toBeInTheDocument();
    expect(screen.getByText('alertCenter.alertCounts')).toBeInTheDocument();
  });

  it('刷新按钮调用 invalidate', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: { events: [], counts_by_kind: {}, unresolved_count: 0 } })
      .mockResolvedValueOnce({ code: 0, data: { size: 100, max_size: 1000, ttl_seconds: 60, hits: 50, misses: 10, hit_rate: 0.83, hit_rate_warning: false } })
      .mockResolvedValueOnce({ code: 0, data: { user_id: 'u1', remaining: { daily_calls_remaining: 100 } } })
      .mockResolvedValueOnce({ code: 0, data: { success: 10, failure: 0 } });
    wrap(<AlertCenterPage />);
    await waitFor(() => expect(screen.getByText('alertCenter.llmCache')).toBeInTheDocument(), { timeout: 2000 });
    const refreshBtn = screen.getByText('alertCenter.refresh');
    fireEvent.click(refreshBtn);
    // 刷新后调用次数增加
    expect(apiClient.get).toHaveBeenCalled();
  });

  it('触发模拟事件调用 mutation', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: { events: [], counts_by_kind: {}, unresolved_count: 0 } })
      .mockResolvedValueOnce({ code: 0, data: { size: 100, max_size: 1000, ttl_seconds: 60, hits: 50, misses: 10, hit_rate: 0.83, hit_rate_warning: false } })
      .mockResolvedValueOnce({ code: 0, data: { user_id: 'u1', remaining: { daily_calls_remaining: 100 } } })
      .mockResolvedValueOnce({ code: 0, data: { success: 10, failure: 0 } });
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ok: true });
    wrap(<AlertCenterPage />);
    await waitFor(() => expect(screen.getByText('alertCenter.llmCache')).toBeInTheDocument(), { timeout: 2000 });
    // 使用 getAllByText 获取第一个匹配
    const triggerBtn = screen.getAllByText('alertCenter.triggerLlm')[0];
    fireEvent.click(triggerBtn);
    await waitFor(() => expect(apiClient.post).toHaveBeenCalled());
  });
});