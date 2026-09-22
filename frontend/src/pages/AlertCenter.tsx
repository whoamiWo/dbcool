import { useState, useEffect, useRef } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { subscribeToAlerts } from '@/lib/stompClient';

/**
 * R11 / R15 告警监控中心 — 运维可观测性页面.
 * Week 43 第五轮增强: WebSocket 实时推送.
 */

interface AlertEvent {
  id: string;
  kind: string;
  user_id: string | null;
  detail: Record<string, unknown>;
  timestamp: number;
  acked: boolean;
  acked_by: string | null;
  acked_at: number | null;
  resolved: boolean;
  resolved_at: number | null;
}

interface AlertsResp {
  events: AlertEvent[];
  counts_by_kind: Record<string, number>;
  unresolved_count: number;
}

interface CacheStats {
  size: number;
  max_size: number;
  ttl_seconds: number;
  hits: number;
  misses: number;
  hit_rate: number;
  hit_rate_warning: boolean;
}

interface QuotaResp {
  user_id: string;
  remaining: {
    daily_calls_remaining: number;
    daily_tokens_remaining: number;
    monthly_calls_remaining: number;
    monthly_tokens_remaining: number;
  };
}

interface WebhookStats {
  success: number;
  failure: number;
  enabled: number;
  configured: number;
  signing: number;
}

const KIND_COLORS: Record<string, string> = {
  rate_limit_exceeded: 'var(--color-warning)',
  quota_exceeded: 'var(--color-error)',
  cache_hit_rate_low: 'var(--color-secondary-500)',
};

const KIND_LABELS: Record<string, string> = {
  rate_limit_exceeded: '限流',
  quota_exceeded: '配额',
  cache_hit_rate_low: '低命中率',
};

function formatTime(ts: number): string {
  const d = new Date(ts * 1000);
  return d.toLocaleTimeString('zh-CN', { hour12: false });
}

function KindBadge({ kind }: { kind: string }) {
  const color = KIND_COLORS[kind] ?? 'var(--color-text-muted)';
  const label = KIND_LABELS[kind] ?? kind;
  return (
    <span
      style={{
        display: 'inline-block',
        padding: '2px 8px',
        background: color,
        color: 'var(--color-text-primary)',
        borderRadius: 4,
        fontSize: 12,
        fontWeight: 600,
      }}
    >
      {label}
    </span>
  );
}

function AlertRow({
  ev,
  onAck,
  onResolve,
  pending,
}: {
  ev: AlertEvent;
  onAck: (id: string) => void;
  onResolve: (id: string) => void;
  pending: boolean;
}) {
  const isResolved = ev.resolved;
  const isAcked = ev.acked;
  return (
    <tr style={{ borderBottom: '1px solid var(--color-bg-tertiary)', opacity: isResolved ? 0.5 : 1 }}>
      <td style={td}>
        {isResolved ? (
          <span style={statusBadgeStyle('var(--color-success)')}>✓ 已解决</span>
        ) : isAcked ? (
          <span
            style={statusBadgeStyle('var(--color-warning)')}
            title={ev.acked_by ? `by ${ev.acked_by}` : ''}
          >
            👁 已确认
          </span>
        ) : (
          <span style={statusBadgeStyle('var(--color-error)')}>🔥 新</span>
        )}
      </td>
      <td style={td}>{formatTime(ev.timestamp)}</td>
      <td style={td}>
        <KindBadge kind={ev.kind} />
      </td>
      <td style={td}>
        {ev.user_id ?? <span style={{ color: 'var(--color-text-muted)' }}>(系统)</span>}
      </td>
      <td style={{ ...td, fontFamily: 'monospace', fontSize: 12, color: 'var(--color-bg-secondary)' }}>
        {JSON.stringify(ev.detail)}
      </td>
      <td style={td}>
        {!isAcked && !isResolved && (
          <button
            onClick={() => onAck(ev.id)}
            disabled={pending}
            style={actionBtnStyle('var(--color-warning)')}
          >
            确认
          </button>
        )}
        {!isResolved && (
          <button
            onClick={() => onResolve(ev.id)}
            disabled={pending}
            style={{ ...actionBtnStyle('var(--color-success)'), marginLeft: 4 }}
          >
            解决
          </button>
        )}
      </td>
    </tr>
  );
}

function statusBadgeStyle(color: string): React.CSSProperties {
  return {
    padding: '2px 6px',
    background: color,
    color: 'var(--color-text-primary)',
    borderRadius: 4,
    fontSize: 11,
    fontWeight: 600,
    display: 'inline-block',
  };
}

function actionBtnStyle(color: string): React.CSSProperties {
  return {
    padding: '3px 10px',
    background: color,
    color: 'var(--color-text-primary)',
    border: 'none',
    borderRadius: 4,
    cursor: 'pointer',
    fontSize: 12,
  };
}

export function AlertCenterPage() {
  const qc = useQueryClient();
  const [refreshSec, setRefreshSec] = useState(5);
  const [showResolved, setShowResolved] = useState(true);
  const [ackBy, setAckBy] = useState('admin');
  const [wsConnected, setWsConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);

  // WebSocket 实时推送
  useEffect(() => {
    let stopped = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

    function connect() {
      if (stopped) return;
      const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      // 携带 user_id(后端按订阅路由推送);无 user_id 则降级为接收所有告警
      const url = `${proto}//${window.location.host}/api/ai/ws/alerts?user_id=${encodeURIComponent(ackBy)}`;
      try {
        const ws = new WebSocket(url);
        wsRef.current = ws;
        ws.onopen = () => setWsConnected(true);
        ws.onclose = () => {
          setWsConnected(false);
          // 自动重连(3s 后)
          reconnectTimer = setTimeout(connect, 3000);
        };
        ws.onerror = () => {
          ws.close();
        };
        ws.onmessage = (ev) => {
          try {
            const msg = JSON.parse(ev.data);
            if (msg.type === 'alert') {
              // 实时收到告警 → 失效缓存,React Query 自动 refetch
              qc.invalidateQueries({ queryKey: ['alerts'] });
              qc.invalidateQueries({ queryKey: ['ai', 'cache', 'stats'] });
            }
          } catch {
            // 忽略解析失败
          }
        };
      } catch {
        reconnectTimer = setTimeout(connect, 3000);
      }
    }
    connect();

    return () => {
      stopped = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, [qc, ackBy]);

  // 任务 6:订阅统一实时消息总线(Java 侧 STOMP /ws/im)。
  //
  // 采用「并存」而非「替换」:上方 Python 直连 WS 保留为过渡通道。因为当前
  // 告警数据实际来自 Python 侧(/ai/alerts/recent),Java AlertCollector 尚未
  // 接管,若直接移除原通道会丢失现有推送。待 Python 告警统一回推 Java 总线后,
  // 再删除上面的直连 WS effect 即可。
  //
  // 陷阱 2:Java 侧 collector.emit() 在生产代码暂无调用,故本通道当前可能
  // 收不到数据属预期现象,不是缺陷。
  useEffect(() => {
    const unsubscribe = subscribeToAlerts((payload) => {
      try {
        const msg = JSON.parse(payload) as Partial<AlertEvent>;
        // 总线直接投递告警事件本身(含 id/kind),不再有 {type:'alert'} 外层包装
        if (msg && (msg.kind || msg.id)) {
          qc.invalidateQueries({ queryKey: ['alerts'] });
          qc.invalidateQueries({ queryKey: ['ai', 'cache', 'stats'] });
        }
      } catch {
        // 忽略解析失败
      }
    });
    return unsubscribe;
  }, [qc]);

  const alertsQuery = useQuery({
    queryKey: ['alerts', 'recent', showResolved],
    queryFn: () =>
      apiClient.get<AlertsResp>(
        `/ai/alerts/recent?limit=50&include_resolved=${showResolved}`,
      ),
    refetchInterval: refreshSec * 1000,
  });
  const cacheQuery = useQuery({
    queryKey: ['ai', 'cache', 'stats'],
    queryFn: () => apiClient.get<CacheStats>('/ai/cache/stats'),
    refetchInterval: refreshSec * 1000,
  });
  const quotaQuery = useQuery({
    queryKey: ['ai', 'quota'],
    queryFn: () => apiClient.get<QuotaResp>('/ai/quota'),
    refetchInterval: refreshSec * 1000,
  });
  const webhookQuery = useQuery({
    queryKey: ['ai', 'webhook', 'stats'],
    queryFn: () => apiClient.get<WebhookStats>('/ai/webhook/stats'),
    refetchInterval: refreshSec * 1000,
  });

  const simulateMutation = useMutation({
    mutationFn: () =>
      apiClient.post<unknown>('/ai/chat', { model: 'demo', prompt: `demo-${Date.now()}` }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['alerts'] });
      qc.invalidateQueries({ queryKey: ['ai', 'cache', 'stats'] });
    },
  });

  const ackMutation = useMutation({
    mutationFn: (eventId: string) =>
      apiClient.post<{ ok: boolean }>(`/ai/alerts/${eventId}/ack`, { by: ackBy }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['alerts'] }),
  });

  const resolveMutation = useMutation({
    mutationFn: (eventId: string) =>
      apiClient.post<{ ok: boolean }>(`/ai/alerts/${eventId}/resolve`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['alerts'] }),
  });

  const cache = cacheQuery.data;
  const quota = quotaQuery.data?.remaining;
  const webhook = webhookQuery.data;
  const events = alertsQuery.data?.events ?? [];
  const counts = alertsQuery.data?.counts_by_kind ?? {};

  return (
    <div style={{ padding: 24, fontFamily: 'system-ui, sans-serif' }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 16,
        }}
      >
        <h1>🚨 告警监控中心</h1>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <span
            title={
              wsConnected ? 'WebSocket 已连接,实时推送' : 'WebSocket 未连接,降级为轮询'
            }
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              padding: '3px 8px',
              borderRadius: 4,
              fontSize: 12,
              fontWeight: 600,
              background: wsConnected ? 'rgba(16,185,129,0.2)' : 'rgba(239,68,68,0.2)',
              color: wsConnected ? 'var(--color-success)' : 'var(--color-error)',
            }}
          >
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: '50%',
                background: wsConnected ? 'var(--color-success)' : 'var(--color-error)',
              }}
            />
            {wsConnected ? 'WS 在线' : 'WS 离线'}
          </span>

          <label style={{ fontSize: 13, color: 'var(--color-text-muted)' }}>
            刷新:
            <select
              value={refreshSec}
              onChange={(e) => setRefreshSec(Number(e.target.value))}
              style={{ marginLeft: 6, padding: '4px 8px' }}
            >
              <option value={2}>2 秒</option>
              <option value={5}>5 秒</option>
              <option value={10}>10 秒</option>
              <option value={30}>30 秒</option>
            </select>
          </label>
          <button
            onClick={() => simulateMutation.mutate()}
            disabled={simulateMutation.isPending}
            style={{
              padding: '6px 12px',
              background: 'var(--color-info)',
              color: 'var(--color-text-primary)',
              border: 'none',
              borderRadius: 4,
              cursor: simulateMutation.isPending ? 'wait' : 'pointer',
            }}
          >
            {simulateMutation.isPending ? '发送中…' : '触发一次 LLM 调用'}
          </button>
        </div>
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
          gap: 12,
          marginBottom: 24,
        }}
      >
        <div style={cardStyle}>
          <h3 style={cardTitleStyle}>📦 LLM 缓存</h3>
          {cache ? (
            <>
              <div style={statLineStyle}>
                <span>命中率</span>
                <strong
                  style={{
                    color: cache.hit_rate_warning ? 'var(--color-error)' : 'var(--color-success)',
                    fontSize: 18,
                  }}
                >
                  {(cache.hit_rate * 100).toFixed(1)}%
                  {cache.hit_rate_warning && ' ⚠️'}
                </strong>
              </div>
              <div style={statLineStyle}>
                <span>命中 / 未命中</span>
                <span>
                  {cache.hits} / {cache.misses}
                </span>
              </div>
              <div style={statLineStyle}>
                <span>当前 / 上限</span>
                <span>
                  {cache.size} / {cache.max_size}
                </span>
              </div>
              <div style={statLineStyle}>
                <span>TTL</span>
                <span>{cache.ttl_seconds}s</span>
              </div>
            </>
          ) : (
            <div style={{ color: 'var(--color-text-muted)' }}>加载中…</div>
          )}
        </div>

        <div style={cardStyle}>
          <h3 style={cardTitleStyle}>📊 用户配额</h3>
          {quota ? (
            <>
              <div style={statLineStyle}>
                <span>每日调用剩余</span>
                <strong>{quota.daily_calls_remaining}</strong>
              </div>
              <div style={statLineStyle}>
                <span>每日 token 剩余</span>
                <strong>{quota.daily_tokens_remaining.toLocaleString()}</strong>
              </div>
              <div style={statLineStyle}>
                <span>每月调用剩余</span>
                <strong>{quota.monthly_calls_remaining.toLocaleString()}</strong>
              </div>
              <div style={statLineStyle}>
                <span>每月 token 剩余</span>
                <strong>{quota.monthly_tokens_remaining.toLocaleString()}</strong>
              </div>
            </>
          ) : (
            <div style={{ color: 'var(--color-text-muted)' }}>加载中…</div>
          )}
        </div>

        <div style={cardStyle}>
          <h3 style={cardTitleStyle}>📡 Webhook 推送</h3>
          {webhook ? (
            <>
              <div style={statLineStyle}>
                <span>配置</span>
                <span>{webhook.configured ? '✅ 已配置' : '❌ 未配置'}</span>
              </div>
              <div style={statLineStyle}>
                <span>签名</span>
                <span>{webhook.signing ? '✅ HMAC 启用' : '❌ 明文'}</span>
              </div>
              <div style={statLineStyle}>
                <span>推送成功</span>
                <strong style={{ color: 'var(--color-success)' }}>{webhook.success}</strong>
              </div>
              <div style={statLineStyle}>
                <span>推送失败</span>
                <strong
                  style={{ color: webhook.failure > 0 ? 'var(--color-error)' : 'var(--color-text-muted)' }}
                >
                  {webhook.failure}
                </strong>
              </div>
            </>
          ) : (
            <div style={{ color: 'var(--color-text-muted)' }}>加载中…</div>
          )}
        </div>

        <div style={cardStyle}>
          <h3 style={cardTitleStyle}>🔔 告警计数</h3>
          <div style={statLineStyle}>
            <span>限流告警</span>
            <strong style={{ color: 'var(--color-warning)' }}>{counts.rate_limit_exceeded ?? 0}</strong>
          </div>
          <div style={statLineStyle}>
            <span>配额告警</span>
            <strong style={{ color: 'var(--color-error)' }}>{counts.quota_exceeded ?? 0}</strong>
          </div>
          <div style={statLineStyle}>
            <span>低命中率</span>
            <strong style={{ color: 'var(--color-secondary-500)' }}>{counts.cache_hit_rate_low ?? 0}</strong>
          </div>
          <div style={statLineStyle}>
            <span>合计</span>
            <strong>{Object.values(counts).reduce((a, b) => a + b, 0)}</strong>
          </div>
        </div>
      </div>

      <div
        style={{
          background: 'var(--color-text-primary)',
          border: '1px solid var(--color-border-medium)',
          borderRadius: 8,
          padding: 16,
        }}
      >
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 12,
          }}
        >
          <h2 style={{ margin: 0 }}>
            📜 最近告警事件({events.length}
            {alertsQuery.data?.unresolved_count !== undefined && (
              <span style={{ color: 'var(--color-text-muted)', fontSize: 14, fontWeight: 'normal' }}>
                {' '}/ 未解决 {alertsQuery.data.unresolved_count}
              </span>
            )})
          </h2>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center', fontSize: 13 }}>
            <label style={{ color: 'var(--color-text-muted)' }}>
              确认人:
              <input
                value={ackBy}
                onChange={(e) => setAckBy(e.target.value)}
                style={{ marginLeft: 4, padding: '2px 6px', width: 100 }}
              />
            </label>
            <label style={{ color: 'var(--color-text-muted)', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={showResolved}
                onChange={(e) => setShowResolved(e.target.checked)}
                style={{ marginRight: 4 }}
              />
              显示已解决
            </label>
          </div>
        </div>
        {events.length === 0 ? (
          <div style={{ padding: 32, textAlign: 'center', color: 'var(--color-text-muted)' }}>
            ✅ 当前无告警。可点击右上角"触发一次 LLM 调用"产生新事件。
          </div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ background: 'var(--color-bg-tertiary)', borderBottom: '1px solid var(--color-border-medium)' }}>
                <th style={th}>状态</th>
                <th style={th}>时间</th>
                <th style={th}>类型</th>
                <th style={th}>用户</th>
                <th style={th}>详情</th>
                <th style={th}>操作</th>
              </tr>
            </thead>
            <tbody>
              {events
                .slice()
                .reverse()
                .map((ev) => (
                  <AlertRow
                    key={ev.id}
                    ev={ev}
                    onAck={ackMutation.mutate}
                    onResolve={resolveMutation.mutate}
                    pending={ackMutation.isPending || resolveMutation.isPending}
                  />
                ))}
            </tbody>
          </table>
        )}
      </div>

      <div
        style={{
          marginTop: 16,
          padding: 12,
          background: 'var(--color-bg-tertiary)',
          border: '1px solid var(--color-border-light)',
          borderRadius: 6,
          fontSize: 13,
          color: 'var(--color-info)',
        }}
      >
        💡 <strong>Week 43 R11 / R15:</strong>三层防护(限流 + 缓存 + 配额) →
        告警中心统一收集 → 可选 Webhook 推送(飞书 / Slack / 通用)。
        缓存命中率 &lt; 40% 时定时巡检任务会自动发出告警。
      </div>

      <SubscriptionPanel currentUser={ackBy} />
    </div>
  );
}

const cardStyle: React.CSSProperties = {
  background: 'var(--color-text-primary)',
  border: '1px solid var(--color-border-medium)',
  borderRadius: 8,
  padding: 16,
};
const cardTitleStyle: React.CSSProperties = { margin: '0 0 12px', fontSize: 14, color: 'var(--color-bg-secondary)' };
const statLineStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  padding: '4px 0',
  fontSize: 13,
  color: 'var(--color-text-muted)',
};
const th: React.CSSProperties = { textAlign: 'left', padding: '8px 12px', fontWeight: 600, color: 'var(--color-bg-secondary)' };
const td: React.CSSProperties = { padding: '8px 12px', verticalAlign: 'top' };


const ALL_KINDS = [
  'rate_limit_exceeded',
  'quota_exceeded',
  'cache_hit_rate_low',
];

function SubscriptionPanel({ currentUser }: { currentUser: string }) {
  const qc = useQueryClient();
  const [newKind, setNewKind] = useState(ALL_KINDS[0]);

  const subsQuery = useQuery({
    queryKey: ['subscriptions', currentUser],
    queryFn: () =>
      apiClient.get<{ user_id: string; kinds: string[] }>(
        `/ai/alerts/subscriptions/${encodeURIComponent(currentUser)}`,
      ),
  });

  const subscribeMutation = useMutation({
    mutationFn: (kind: string) =>
      apiClient.post<{ ok: boolean; created: boolean }>(
        '/ai/alerts/subscriptions',
        { user_id: currentUser, kind },
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['subscriptions', currentUser] }),
  });

  const unsubscribeMutation = useMutation({
    mutationFn: (kind: string) =>
      apiClient.delete<{ ok: boolean }>(
        `/ai/alerts/subscriptions?user_id=${encodeURIComponent(currentUser)}&kind=${encodeURIComponent(kind)}`,
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['subscriptions', currentUser] }),
  });

  const subscribed = subsQuery.data?.kinds ?? [];
  const available = ALL_KINDS.filter((k) => !subscribed.includes(k));

  return (
    <div
      style={{
        marginTop: 16,
        background: 'var(--color-text-primary)',
        border: '1px solid var(--color-border-medium)',
        borderRadius: 8,
        padding: 16,
      }}
    >
      <h3 style={{ marginTop: 0 }}>📮 我的告警订阅(用户: {currentUser})</h3>
      <p style={{ color: 'var(--color-text-muted)', fontSize: 13, margin: '0 0 12px' }}>
        订阅后,系统会优先把关心的告警通过 Webhook 推送给你。未订阅的事件仍会写入告警中心,只是不主动推送。
      </p>

      <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        <select
          value={newKind}
          onChange={(e) => setNewKind(e.target.value)}
          style={{ padding: '4px 8px', flex: 1 }}
          disabled={available.length === 0}
        >
          {available.map((k) => (
            <option key={k} value={k}>
              {k}
            </option>
          ))}
        </select>
        <button
          onClick={() => subscribeMutation.mutate(newKind)}
          disabled={subscribeMutation.isPending || available.length === 0}
          style={{
            padding: '4px 12px',
            background: 'var(--color-info)',
            color: 'var(--color-text-primary)',
            border: 'none',
            borderRadius: 4,
            cursor: subscribeMutation.isPending ? 'wait' : 'pointer',
          }}
        >
          + 订阅
        </button>
      </div>

      {subscribed.length === 0 ? (
        <div style={{ padding: 12, color: 'var(--color-text-muted)', fontSize: 13 }}>
          暂未订阅任何告警类型。
        </div>
      ) : (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {subscribed.map((kind) => (
            <span
              key={kind}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                padding: '4px 10px',
                background: 'rgba(59,130,246,0.1)',
                color: 'var(--color-info)',
                borderRadius: 12,
                fontSize: 13,
              }}
            >
              {kind}
              <button
                onClick={() => unsubscribeMutation.mutate(kind)}
                disabled={unsubscribeMutation.isPending}
                style={{
                  background: 'transparent',
                  border: 'none',
                  color: 'var(--color-info)',
                  cursor: 'pointer',
                  padding: 0,
                  fontSize: 14,
                  fontWeight: 700,
                }}
                title="取消订阅"
              >
                ×
              </button>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
