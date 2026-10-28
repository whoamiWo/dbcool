import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/auth';

interface PlanTier {
  id: string;
  name: string;
  slug: string;
  description: string;
  priceMonthly: number;
  priceYearly: number;
  currency: string;
  apiCallLimit: number;
  storageLimit: number;
  seatsLimit: number;
  features?: string;
}

interface Subscription {
  id: string;
  tenantId: string;
  planTierId: string;
  status: string;
  currentPeriodStart: string;
  currentPeriodEnd: string;
  cancelAtPeriodEnd: boolean;
  paymentMethod?: string;
}

interface Invoice {
  id: string;
  invoiceNumber: string;
  periodStart: string;
  periodEnd: string;
  totalAmount: number;
  currency: string;
  status: string;
  dueDate?: string;
}

interface Payment {
  id: string;
  invoiceId?: string;
  amount: number;
  paymentMethod: string;
  status: string;
  transactionId?: string;
  failureReason?: string;
  paidAt?: string;
}

const STATUS_LABEL: Record<string, string> = {
  ACTIVE: '生效中',
  TRIALING: '试用中',
  PAST_DUE: '逾期',
  CANCELED: '已取消',
  EXPIRED: '已过期',
  DRAFT: '草稿',
  OPEN: '待支付',
  PAID: '已支付',
  UNCOLLECTIBLE: '无法收回',
  VOID: '已作废',
  PENDING: '待确认',
  SUCCEEDED: '成功',
  FAILED: '失败',
  REFUNDED: '已退款',
};

function formatBytes(bytes: number): string {
  if (!bytes) return '不限';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = bytes;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v < 10 ? 1 : 0)}${units[i]}`;
}

export function BillingPage() {
  const nav = useNavigate();
  const tenantId = useAuthStore((s) => s.user?.tenant_id ?? '');

  const [plans, setPlans] = useState<PlanTier[]>([]);
  const [subscription, setSubscription] = useState<Subscription | null>(null);
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [payments, setPayments] = useState<Payment[]>([]);
  const [providers, setProviders] = useState<string[]>([]);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [paying, setPaying] = useState<Invoice | null>(null);
  const [provider, setProvider] = useState('');
  /** 后端 billing.enabled=false（默认）时 /api/billing/* 全部 404 */
  const [disabled, setDisabled] = useState(false);

  const token = localStorage.getItem('access_token') || '';

  const call = useCallback(async (path: string, init?: RequestInit) => {
    const r = await fetch(path, {
      ...init,
      headers: { Authorization: `Bearer ${token}`, ...(init?.headers || {}) },
    });
    if (r.status === 401) {
      nav('/login');
      return null;
    }
    return r;
  }, [token, nav]);

  const loadAll = useCallback(async () => {
    if (!tenantId) return;
    setLoading(true);
    setError(null);
    try {
      const [p, s, i, pay, prov] = await Promise.all([
        call('/api/billing/plans'),
        call(`/api/billing/subscription/${tenantId}`),
        call(`/api/billing/invoices/${tenantId}`),
        call(`/api/billing/payments/${tenantId}`),
        call('/api/billing/payment-providers'),
      ]);
      if (p?.ok) setPlans(await p.json());
      else if (p && p.status === 404) setDisabled(true);
      if (s) setSubscription(s.ok ? await s.json() : null);
      if (i?.ok) setInvoices(await i.json());
      if (pay?.ok) setPayments(await pay.json());
      if (prov?.ok) setProviders(await prov.json());
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [tenantId, call]);

  useEffect(() => {
    loadAll().catch((err) => console.error('load billing failed:', err));
  }, [loadAll]);

  async function subscribe(planId: string, trial: boolean) {
    const r = await call('/api/billing/subscription', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantId, planTierId: planId, paymentMethod: 'manual', trial }),
    });
    if (!r) return;
    setNotice(r.ok ? '订阅已创建' : await r.text());
    await loadAll();
  }

  async function cancelSubscription() {
    if (!subscription) return;
    const r = await call(`/api/billing/subscription/${subscription.id}/cancel`, { method: 'POST' });
    if (!r) return;
    setNotice(r.ok ? '已设置周期末取消' : await r.text());
    await loadAll();
  }

  async function submitPayment() {
    if (!paying) return;
    const r = await call('/api/billing/payments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        tenantId,
        invoiceId: paying.id,
        amount: paying.totalAmount,
        paymentMethod: provider,
      }),
    });
    if (!r) return;
    if (r.ok) {
      const p: Payment = await r.json();
      setNotice(p.status === 'SUCCEEDED'
        ? `支付成功，交易号 ${p.transactionId ?? '-'}`
        : p.status === 'PENDING'
          ? '已受理，等待人工确认收款'
          : `支付失败：${p.failureReason ?? '未知原因'}`);
    } else {
      setNotice(await r.text());
    }
    setPaying(null);
    await loadAll();
  }

  return (
    <div style={{ padding: 24, maxWidth: 1200 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <h2 style={{ margin: 0 }}>账单与订阅</h2>
        {subscription && (
          <button onClick={cancelSubscription} disabled={subscription.cancelAtPeriodEnd} style={btnGhost}
            aria-label="取消订阅">
            {subscription.cancelAtPeriodEnd ? '将在周期末取消' : '取消订阅'}
          </button>
        )}
      </div>

      <div style={{ marginBottom: 16, color: 'var(--color-text-muted)', fontSize: 13 }}>
        当前租户：{tenantId || '未知'} · 选择套餐并支付账单，未配置支付网关的渠道不会被受理。
      </div>

      {error && <div style={{ color: 'var(--color-error)', fontSize: 13, marginBottom: 8 }}>{error}</div>}
      {notice && <div style={{ color: 'var(--color-success)', fontSize: 13, marginBottom: 8 }}>{notice}</div>}
      {loading && <div style={{ fontSize: 13, color: 'var(--color-text-muted)' }}>加载中...</div>}

      {disabled && (
        <div style={{ ...card, marginBottom: 12, color: 'var(--color-warning)', fontSize: 13 }}>
          计费能力未启用：后端 <code>billing.enabled=false</code>（个人自用默认关闭）。
          需要时设置 <code>BILLING_ENABLED=true</code> 并重启后端。
        </div>
      )}

      {subscription && (
        <section style={card}>
          <h3 style={{ margin: '0 0 8px 0', fontSize: 15 }}>当前订阅</h3>
          <div style={{ fontSize: 13 }}>
            <div>状态：<strong>{STATUS_LABEL[subscription.status] || subscription.status}</strong></div>
            <div>套餐：{plans.find((p) => p.id === subscription.planTierId)?.name || subscription.planTierId}</div>
            <div>周期：{subscription.currentPeriodStart} ~ {subscription.currentPeriodEnd}</div>
          </div>
        </section>
      )}

      <h3 style={{ fontSize: 15, margin: '20px 0 8px' }}>套餐</h3>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 12 }}>
        {plans.map((p) => (
          <div key={p.id} style={card}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
              <strong>{p.name}</strong>
              <span style={{ fontSize: 13, color: 'var(--color-text-muted)' }}>{p.currency}</span>
            </div>
            <div style={{ fontSize: 22, margin: '6px 0' }}>
              ¥{p.priceMonthly}<span style={{ fontSize: 12, color: 'var(--color-text-muted)' }}> /月</span>
            </div>
            <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginBottom: 8 }}>年付 ¥{p.priceYearly}</div>
            <div style={{ fontSize: 12, marginBottom: 8 }}>{p.description}</div>
            <ul style={{ fontSize: 12, paddingLeft: 16, margin: '0 0 12px' }}>
              <li>API 调用：{p.apiCallLimit ? `${p.apiCallLimit} 次/月` : '不限'}</li>
              <li>存储：{formatBytes(p.storageLimit)}</li>
              <li>席位：{p.seatsLimit || '不限'}</li>
            </ul>
            <button
              onClick={() => subscribe(p.id, false)}
              disabled={!!subscription}
              style={{ ...btnPrimary, opacity: subscription ? 0.5 : 1 }}
              aria-label={`订阅 ${p.name}`}
            >
              订阅
            </button>
            <button
              onClick={() => subscribe(p.id, true)}
              disabled={!!subscription}
              style={{ ...btnGhost, marginTop: 8, width: '100%', opacity: subscription ? 0.5 : 1 }}
              aria-label={`试用 ${p.name}`}
            >
              免费试用
            </button>
          </div>
        ))}
      </div>

      <h3 style={{ fontSize: 15, margin: '20px 0 8px' }}>账单</h3>
      <table style={{ width: '100%', borderCollapse: 'collapse', background: 'var(--color-text-primary)' }}>
        <thead style={{ background: 'var(--color-bg-tertiary)' }}>
          <tr>
            <th style={th}>编号</th>
            <th style={th}>周期</th>
            <th style={th}>金额</th>
            <th style={th}>状态</th>
            <th style={th}>到期日</th>
            <th style={th}>操作</th>
          </tr>
        </thead>
        <tbody>
          {invoices.map((inv) => (
            <tr key={inv.id} style={{ borderTop: '1px solid var(--color-border-medium)' }}>
              <td style={td}>{inv.invoiceNumber}</td>
              <td style={td}>{inv.periodStart} ~ {inv.periodEnd}</td>
              <td style={td}>{inv.currency} {inv.totalAmount}</td>
              <td style={td}>{STATUS_LABEL[inv.status] || inv.status}</td>
              <td style={td}>{inv.dueDate || '-'}</td>
              <td style={td}>
                {(inv.status === 'OPEN' || inv.status === 'DRAFT') && (
                  <button
                    onClick={() => { setPaying(inv); setProvider(providers[0] || ''); }}
                    disabled={providers.length === 0}
                    style={btnPrimary}
                    aria-label={`支付 ${inv.invoiceNumber}`}
                  >
                    支付
                  </button>
                )}
              </td>
            </tr>
          ))}
          {invoices.length === 0 && (
            <tr>
              <td colSpan={6} style={{ ...td, textAlign: 'center', color: 'var(--color-text-muted)', padding: 20 }}>
                暂无账单
              </td>
            </tr>
          )}
        </tbody>
      </table>
      {providers.length === 0 && !disabled && (
        <div style={{ fontSize: 12, color: 'var(--color-warning)', marginTop: 8 }}>
          当前未配置任何支付网关（alipay/wechat/stripe），请先在后端配置 nocobase.billing.payment.gateways。
        </div>
      )}

      <h3 style={{ fontSize: 15, margin: '20px 0 8px' }}>支付记录</h3>
      <table style={{ width: '100%', borderCollapse: 'collapse', background: 'var(--color-text-primary)' }}>
        <thead style={{ background: 'var(--color-bg-tertiary)' }}>
          <tr>
            <th style={th}>金额</th>
            <th style={th}>渠道</th>
            <th style={th}>状态</th>
            <th style={th}>交易号</th>
            <th style={th}>说明</th>
          </tr>
        </thead>
        <tbody>
          {payments.map((p) => (
            <tr key={p.id} style={{ borderTop: '1px solid var(--color-border-medium)' }}>
              <td style={td}>{p.amount}</td>
              <td style={td}>{p.paymentMethod}</td>
              <td style={td}>{STATUS_LABEL[p.status] || p.status}</td>
              <td style={td}>{p.transactionId || '-'}</td>
              <td style={td}>{p.failureReason || (p.paidAt ? `支付于 ${p.paidAt}` : '-')}</td>
            </tr>
          ))}
          {payments.length === 0 && (
            <tr>
              <td colSpan={5} style={{ ...td, textAlign: 'center', color: 'var(--color-text-muted)', padding: 20 }}>
                暂无支付记录
              </td>
            </tr>
          )}
        </tbody>
      </table>

      {paying && (
        <div role="presentation" style={modalOverlay} onClick={() => setPaying(null)}>
          <div role="presentation" style={modalCard} onClick={(e) => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 12px' }}>支付账单 {paying.invoiceNumber}</h3>
            <div style={{ fontSize: 13, marginBottom: 12 }}>
              应付金额：{paying.currency} {paying.totalAmount}
            </div>
            <label style={{ display: 'block', marginBottom: 12 }}>
              <div style={{ fontSize: 12, color: 'var(--color-text-muted)', marginBottom: 4 }}>支付方式</div>
              <select value={provider} onChange={(e) => setProvider(e.target.value)} style={input}>
                {providers.map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
            </label>
            {provider === 'manual' && (
              <div style={{ fontSize: 12, color: 'var(--color-warning)', marginBottom: 12 }}>
                线下支付：提交后状态为「待确认」，需管理员在后端确认收款。
              </div>
            )}
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => setPaying(null)} style={btnGhost}>取消</button>
              <button onClick={submitPayment} disabled={!provider} style={btnPrimary}>确认支付</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

const th: React.CSSProperties = { padding: '8px 12px', textAlign: 'left', fontSize: 13, fontWeight: 600 };
const td: React.CSSProperties = { padding: '8px 12px', fontSize: 13 };
const card: React.CSSProperties = {
  background: 'var(--color-text-primary)', borderRadius: 8, padding: 16,
  boxShadow: '0 1px 3px var(--color-bg-primary)',
};
const input: React.CSSProperties = { width: '100%', padding: '6px 10px', border: '1px solid var(--color-border-medium)', borderRadius: 4 };
const btnPrimary: React.CSSProperties = { background: 'var(--color-primary-500)', color: '#fff', border: 'none', padding: '6px 14px', borderRadius: 4, cursor: 'pointer', fontSize: 12 };
const btnGhost: React.CSSProperties = { background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border-medium)', padding: '6px 14px', borderRadius: 4, cursor: 'pointer', fontSize: 12 };
const modalOverlay: React.CSSProperties = { position: 'fixed', inset: 0, background: 'var(--color-bg-primary)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 };
const modalCard: React.CSSProperties = { background: 'var(--color-text-primary)', padding: 24, borderRadius: 8, width: 420 };
