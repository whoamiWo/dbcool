import { Client, type StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useAuthStore } from '@/stores/auth';

let stompClient: Client | null = null;

/** 待订阅队列:连接就绪/重连后统一建立 */
const pendingSubs: Array<{ destination: string; cb: (body: string) => void }> = [];

/**
 * 全站复用单一 STOMP 连接。
 * 使用 SockJS 兼容后端 /ws/im 配置(token 走 query 参数)。
 *
 * 注意:判定用 `active` 而非 `connected` —— activate() 后 active 立即为 true,
 * 而 connected 要等握手完成;用 connected 会导致并发调用时重复 new Client(连接泄漏)。
 */
export function getStompClient(): Client {
  if (stompClient && stompClient.active) {
    return stompClient;
  }

  const token = localStorage.getItem('nocobase_access_token');

  stompClient = new Client({
    webSocketFactory: () => new SockJS(`/ws/im?token=${token}`),
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    reconnectDelay: 5000,
    onConnect: () => {
      // 连接就绪(含断线重连)→ 补建所有待订阅
      for (const s of pendingSubs) {
        stompClient?.subscribe(s.destination, (m) => s.cb(m.body));
      }
    },
    onStompError: (frame) => {
      // 只打 message 与 body,不打印 token
      console.error('[STOMP] error:', frame.headers?.message, frame.body);
    },
    onWebSocketError: (event) => {
      console.error('[STOMP] ws error:', event);
    },
    onWebSocketClose: () => {
      console.warn('[STOMP] closed, reconnecting...');
    },
  });

  stompClient.activate();
  return stompClient;
}

/**
 * 订阅频道主题。未连接时登记到 pendingSubs,连接就绪/重连后自动补建。
 * 订阅目标必须带租户段:/topic/t-<tenantId>.channel.<channelId>
 */
export function subscribeToChannel(
  channelId: string,
  onMessage: (payload: string) => void,
): () => void {
  const client = getStompClient();
  const user = useAuthStore.getState().user;
  const tenantId = user?.tenant_id ?? 'tenant_default';
  const destination = `/topic/t-${tenantId}.channel.${channelId}`;

  // 已连接则立即订阅;未连接则登记,等待 onConnect 补建
  let sub: StompSubscription | null = null;
  if (client.connected) {
    sub = client.subscribe(destination, (m) => onMessage(m.body));
  }
  const entry = { destination, cb: onMessage };
  pendingSubs.push(entry);

  return () => {
    sub?.unsubscribe();
    const i = pendingSubs.indexOf(entry);
    if (i >= 0) pendingSubs.splice(i, 1);
  };
}

/**
 * 订阅告警主题(任务 6:告警接入统一总线)。
 * destination:/topic/t-<tenantId>.alerts
 * 注意:当前告警数据源尚未接入生产数据源,收不到事件属预期。
 */
export function subscribeToAlerts(onAlert: (payload: string) => void): () => void {
  const client = getStompClient();
  const user = useAuthStore.getState().user;
  const tenantId = user?.tenant_id ?? 'tenant_default';
  const destination = `/topic/t-${tenantId}.alerts`;

  let sub: StompSubscription | null = null;
  if (client.connected) {
    sub = client.subscribe(destination, (m) => onAlert(m.body));
  }
  const entry = { destination, cb: onAlert };
  pendingSubs.push(entry);

  return () => {
    sub?.unsubscribe();
    const i = pendingSubs.indexOf(entry);
    if (i >= 0) pendingSubs.splice(i, 1);
  };
}

/**
 * 断开 STOMP 连接(登出时调用,避免旧 token 连接残留)。
 * 即使未连接也清引用与待订阅队列。
 */
export function disconnectStomp(): void {
  pendingSubs.length = 0;
  if (stompClient) {
    stompClient.deactivate();
    stompClient = null;
  }
}
