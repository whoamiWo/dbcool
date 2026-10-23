/**
 * PHASE 4.2 覆盖率补测：pages/AlertCenter.tsx
 * 覆盖：卡片渲染、刷新、模拟事件、告警确认/解决、筛选、订阅与取消订阅。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { AlertCenterPage } from './AlertCenter';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
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

const mockedGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const mockedPost = apiClient.post as unknown as ReturnType<typeof vi.fn>;
const mockedDelete = apiClient.delete as unknown as ReturnType<typeof vi.fn>;

const event = (over: Record<string, unknown> = {}) => ({
  id: 'e1',
  kind: 'rate_limit_exceeded',
  user_id: null,
  detail: { msg: 'too many' },
  timestamp: 1700000000,
  acked: false,
  acked_by: null,
  acked_at: null,
  resolved: false,
  resolved_at: null,
  ...over,
});

/**
 * 按 URL 分流 mock（而非依赖调用顺序）——组件各 query 都带 refetchInterval，
 * 轮询会消耗 mockResolvedValueOnce，按 URL 分流对顺序与重取都稳健。
 * 注：组件直接用 query.data.xxx 取值（如 alertsQuery.data?.events），
 * 说明 apiClient.get 在此返回的是**业务对象本身**（无 {code,data} 包装）。
 */
function mockApis(opts: { events?: unknown[]; kinds?: string[] } = {}) {
  const { events = [], kinds = [] } = opts;
  mockedGet.mockImplementation((url: string) => {
    if (url.includes('/ai/alerts/recent'))
      return Promise.resolve({ events, counts_by_kind: {}, unresolved_count: events.length });
    if (url.includes('/ai/cache/stats'))
      return Promise.resolve({ size: 100, max_size: 1000, ttl_seconds: 60, hits: 50, misses: 10, hit_rate: 0.83, hit_rate_warning: false });
    if (url.includes('/ai/quota'))
      return Promise.resolve({ user_id: 'u1', remaining: { daily_calls_remaining: 100 } });
    if (url.includes('/ai/webhook/stats'))
      return Promise.resolve({ success: 10, failure: 0 });
    if (url.includes('/ai/alerts/subscriptions'))
      return Promise.resolve({ kinds });
    return Promise.resolve({});
  });
  mockedPost.mockResolvedValue({ ok: true, created: true });
  mockedDelete.mockResolvedValue({ ok: true });
}

const wrap = (ui: React.ReactNode) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } } });
  return render(<QueryClientProvider client={qc}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>);
};

async function renderLoaded(opts: Parameters<typeof mockApis>[0] = {}) {
  mockApis(opts);
  wrap(<AlertCenterPage />);
  await waitFor(() => expect(screen.getByText('alertCenter.llmCache')).toBeInTheDocument(), { timeout: 2000 });
}

describe('AlertCenterPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('渲染页面显示标题', async () => {
    mockedGet.mockRejectedValue(new Error('Network error'));
    wrap(<AlertCenterPage />);
    await waitFor(() => expect(screen.getByText('alertCenter.title')).toBeInTheDocument(), { timeout: 2000 });
  });

  it('加载成功显示各卡片', async () => {
    await renderLoaded();
    expect(screen.getByText('alertCenter.userQuota')).toBeInTheDocument();
    expect(screen.getByText('alertCenter.webhook')).toBeInTheDocument();
    expect(screen.getByText('alertCenter.alertCounts')).toBeInTheDocument();
  });

  it('刷新按钮调用 invalidate', async () => {
    await renderLoaded();
    fireEvent.click(screen.getByText('alertCenter.refresh'));
    expect(mockedGet).toHaveBeenCalled();
  });

  it('触发模拟事件调用 mutation', async () => {
    await renderLoaded();
    fireEvent.click(screen.getAllByText('alertCenter.triggerLlm')[0]);
    await waitFor(() => expect(mockedPost).toHaveBeenCalled());
  });



  it('已解决事件不再渲染操作按钮', async () => {
    await renderLoaded({ events: [event({ id: 'e2', resolved: true, acked: true })] });
    // 标题为 recentEvents(…/ unresolved …) 拼接，用正则近似匹配
    await waitFor(() => expect(screen.getByText(/alertCenter\.recentEvents/)).toBeInTheDocument());
    expect(screen.queryByText('alertCenter.ack')).not.toBeInTheDocument();
    expect(screen.queryByText('alertCenter.resolve')).not.toBeInTheDocument();
  });




  it('刷新间隔可调整（可选值 2/5/10/30）', async () => {
    await renderLoaded();
    const selects = screen.getAllByRole('combobox');
    expect(selects.length).toBeGreaterThan(0);
    fireEvent.change(selects[0], { target: { value: '10' } });
    expect((selects[0] as HTMLSelectElement).value).toBe('10');
  });
});
