import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { BillingPage } from './Billing';

const PLAN_FREE = {
  id: 'plan_free', name: '免费版', slug: 'free', description: '适合个人和小团队体验',
  priceMonthly: 0, priceYearly: 0, currency: 'CNY',
  apiCallLimit: 1000, storageLimit: 1073741824, seatsLimit: 3,
};
const PLAN_PRO = {
  id: 'plan_pro', name: '专业版', slug: 'pro', description: '适合成长型业务',
  priceMonthly: 99, priceYearly: 990, currency: 'CNY',
  apiCallLimit: 100000, storageLimit: 10737418240, seatsLimit: 10,
};
const INVOICE = {
  id: 'inv-1', invoiceNumber: 'INV-20260101-0001',
  periodStart: '2026-01-01', periodEnd: '2026-02-01',
  totalAmount: 99, currency: 'CNY', status: 'OPEN', dueDate: '2026-02-16',
};

let handlers: Record<string, unknown>;
let lastRequest: { url: string; method?: string; body?: string } | null = null;

function jsonResponse(data: unknown, ok = true, status = 200) {
  return { ok, status, json: async () => data, text: async () => JSON.stringify(data) } as Response;
}

function routeGet(url: string): unknown {
  if (url.startsWith('/api/billing/plans')) return [PLAN_FREE, PLAN_PRO];
  if (url.startsWith('/api/billing/subscription/')) return handlers.subscription ?? null;
  if (url.startsWith('/api/billing/invoices/')) return handlers.invoices ?? [];
  if (url.startsWith('/api/billing/payments/')) return handlers.payments ?? [];
  if (url.startsWith('/api/billing/payment-providers')) return handlers.providers ?? [];
  return null;
}

// useNavigate 必须返回稳定引用：每次渲染新建函数会让 useCallback 链抖动，
// 导致 useEffect 无限重跑（表现为测试挂起）。
const mocks = vi.hoisted(() => ({ navigate: vi.fn() }));

vi.mock('react-router-dom', () => ({
  useNavigate: () => mocks.navigate,
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: (selector: (s: { user: { tenant_id: string } }) => unknown) =>
    selector({ user: { tenant_id: 't1' } }),
}));

describe('BillingPage', () => {
  beforeEach(() => {
    handlers = { subscription: null, invoices: [], payments: [], providers: [] };
    lastRequest = null;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method && init.method !== 'GET') {
        lastRequest = { url, method: init.method, body: String(init.body ?? '') };
        if (url === '/api/billing/subscription') {
          return jsonResponse({ id: 'sub-1', tenantId: 't1', planTierId: 'plan_pro', status: 'ACTIVE' });
        }
        if (url === '/api/billing/payments') {
          return jsonResponse(handlers.paymentResult ?? {
            id: 'pay-1', status: 'SUCCEEDED', transactionId: 'tx-1',
          });
        }
        return jsonResponse({});
      }
      return jsonResponse(routeGet(url));
    }));
  });

  it('渲染套餐与价格', async () => {
    render(<BillingPage />);
    expect(await screen.findByText('专业版')).toBeTruthy();
    // 年付 ¥990 也含 "99"，用前缀精确匹配月付价
    expect(screen.getByText((_, el) => el?.textContent?.startsWith('¥99') === true)).toBeTruthy();
    expect(screen.getByText(/适合成长型业务/)).toBeTruthy();
    expect(screen.getByText(/10GB/)).toBeTruthy();
  });

  it('点击订阅 → POST /api/billing/subscription 带上 planTierId', async () => {
    render(<BillingPage />);
    const btn = await screen.findByLabelText('订阅 专业版');
    fireEvent.click(btn);
    await waitFor(() => expect(lastRequest?.url).toBe('/api/billing/subscription'));
    expect(lastRequest?.body).toContain('"planTierId":"plan_pro"');
    expect(lastRequest?.body).toContain('"tenantId":"t1"');
  });

  it('未配置支付网关 → 支付按钮禁用并给出提示', async () => {
    handlers = { ...handlers, invoices: [INVOICE], providers: [] };
    render(<BillingPage />);
    const pay = await screen.findByLabelText('支付 INV-20260101-0001');
    expect(pay.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText(/未配置任何支付网关/)).toBeTruthy();
  });

  it('支付成功 → 展示交易号', async () => {
    handlers = {
      subscription: null,
      invoices: [INVOICE],
      payments: [],
      providers: ['alipay'],
      paymentResult: { id: 'pay-1', status: 'SUCCEEDED', transactionId: 'tx-9' },
    };
    render(<BillingPage />);
    fireEvent.click(await screen.findByLabelText('支付 INV-20260101-0001'));
    fireEvent.click(screen.getByText('确认支付'));
    await waitFor(() => expect(lastRequest?.url).toBe('/api/billing/payments'));
    expect(await screen.findByText(/支付成功，交易号 tx-9/)).toBeTruthy();
  });

  it('manual 渠道支付 → 提示等待人工确认', async () => {
    handlers = {
      subscription: null,
      invoices: [INVOICE],
      payments: [],
      providers: ['manual'],
      paymentResult: { id: 'pay-1', status: 'PENDING' },
    };
    render(<BillingPage />);
    fireEvent.click(await screen.findByLabelText('支付 INV-20260101-0001'));
    expect(screen.getByText(/需管理员在后端确认收款/)).toBeTruthy();
    fireEvent.click(screen.getByText('确认支付'));
    expect(await screen.findByText(/等待人工确认收款/)).toBeTruthy();
  });
});
