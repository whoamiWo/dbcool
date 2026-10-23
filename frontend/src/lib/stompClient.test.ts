import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 回归测试:覆盖曾经的阻断缺陷 —— 连接未建立时 subscribe 抛 TypeError 导致页面白屏。
 *
 * 用假的 Client 复现 stompjs v7 的行为:未连接时 subscribe() 抛 TypeError。
 * 同时覆盖各订阅变体、publish 发送、以及连接回调补建/错误处理路径。
 */

const subscribed: string[] = [];
const published: Array<{ destination: string; body: string }> = [];
let activeCount = 0;

vi.mock('@stomp/stompjs', () => {
  class FakeClient {
    active = false;
    connected = false;
    opts: any;
    constructor(opts: any) {
      this.opts = opts;
    }
    activate() {
      this.active = true;
      activeCount++;
    }
    deactivate() {
      this.active = false;
      this.connected = false;
    }
    subscribe(dest: string) {
      // 复现 stompjs v7 _checkConnection() 的行为
      if (!this.connected) {
        throw new TypeError('There is no underlying STOMP connection');
      }
      subscribed.push(dest);
      return { unsubscribe: () => {} };
    }
    publish(msg: { destination: string; body: string }) {
      published.push(msg);
    }
  }
  return { Client: FakeClient };
});

vi.mock('sockjs-client', () => ({ default: class {} }));

const mockUser: { id: string; username: string; tenant_id: string } | null = {
  id: 'u1',
  username: 'alice',
  tenant_id: 'tenant_default',
};

vi.mock('@/stores/auth', () => ({
  useAuthStore: {
    getState: () => ({ user: mockUser }),
  },
}));

import {
  disconnectStomp,
  getStompClient,
  subscribeToAlerts,
  subscribeToChannel,
  subscribeToCollab,
  sendCollabAwareness,
  sendCollabJoin,
  sendCollabLeave,
  sendCollabUpdate,
} from '@/lib/stompClient';

/** 令假连接进入已连接态并触发 onConnect（模拟握手完成/断线重连） */
function connectAndFire() {
  const c = getStompClient();
  (c as unknown as { connected: boolean }).connected = true;
  (c as any).opts.onConnect();
}

describe('stompClient', () => {
  beforeEach(() => {
    activeCount = 0;
    subscribed.length = 0;
    published.length = 0;
    localStorage.setItem('nocobase_access_token', 'test-token');
    disconnectStomp();
  });

  it('未连接时订阅不抛错(历史阻断缺陷)', () => {
    // 修复前:activate() 异步未就绪即 subscribe,直接抛 TypeError 使页面白屏
    expect(() => subscribeToChannel('c1', () => {})).not.toThrow();
  });

  it('订阅目标必须带租户段', () => {
    const client = getStompClient();
    // 手动置为已连接,令 subscribe 真正执行以校验 destination
    (client as unknown as { connected: boolean }).connected = true;
    subscribeToChannel('c1', () => {});
    expect(subscribed).toContain('/topic/t-tenant_default.channel.c1');
  });

  it('重复获取复用同一连接,不重复 activate', () => {
    getStompClient();
    getStompClient();
    expect(activeCount).toBe(1);
  });

  it('disconnectStomp 清理后可重新订阅', () => {
    subscribeToChannel('c1', () => {});
    disconnectStomp();
    // 清理后应能重新建立,不残留旧状态
    expect(() => subscribeToChannel('c1', () => {})).not.toThrow();
    expect(activeCount).toBe(2);
  });

  it('subscribeToAlerts 订阅告警主题', () => {
    const client = getStompClient();
    (client as unknown as { connected: boolean }).connected = true;
    subscribeToAlerts(() => {});
    expect(subscribed).toContain('/topic/t-tenant_default.alerts');
  });

  it('subscribeToCollab 订阅协同文档主题', () => {
    const client = getStompClient();
    (client as unknown as { connected: boolean }).connected = true;
    subscribeToCollab('doc-9', () => {});
    expect(subscribed).toContain('/topic/t-tenant_default.collab.doc-9');
  });

  it('onConnect 时补建 pendingSubs 中待订阅的目标', () => {
    // 未连接时先登记
    subscribeToChannel('c-pending', () => {});
    expect(subscribed).not.toContain('/topic/t-tenant_default.channel.c-pending');
    // 连接建立后应自动补建
    connectAndFire();
    expect(subscribed).toContain('/topic/t-tenant_default.channel.c-pending');
  });

  it('sendCollabUpdate 发布增量到 /app/collab/update', () => {
    sendCollabUpdate('doc-1', 'BASE64UPDATE');
    expect(published).toContainEqual({
      destination: '/app/collab/update',
      body: JSON.stringify({ docId: 'doc-1', update: 'BASE64UPDATE' }),
    });
  });

  it('sendCollabJoin / Leave 发布房间进出', () => {
    sendCollabJoin('doc-1');
    sendCollabLeave('doc-1');
    expect(published).toContainEqual({
      destination: '/app/collab/join',
      body: JSON.stringify({ docId: 'doc-1' }),
    });
    expect(published).toContainEqual({
      destination: '/app/collab/leave',
      body: JSON.stringify({ docId: 'doc-1' }),
    });
  });

  it('sendCollabAwareness 发布光标/选区', () => {
    sendCollabAwareness('doc-1', 10, 2, 8);
    expect(published).toContainEqual({
      destination: '/app/collab/awareness',
      body: JSON.stringify({ docId: 'doc-1', cursorPosition: 10, selectionStart: 2, selectionEnd: 8 }),
    });
  });

  it('onStompError / onWebSocketError / onWebSocketClose 回调均不抛错', () => {
    const client = getStompClient();
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(() =>
      (client as any).opts.onStompError({ headers: { message: 'boom' }, body: 'detail' }),
    ).not.toThrow();
    expect(() => (client as any).opts.onWebSocketError(new Event('error'))).not.toThrow();
    expect(() => (client as any).opts.onWebSocketClose()).not.toThrow();
    expect(errSpy).toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalled();
    errSpy.mockRestore();
    warnSpy.mockRestore();
  });

  it('取消订阅后不再保留待订阅项（重连不重复补建）', () => {
    const client = getStompClient();
    (client as unknown as { connected: boolean }).connected = true;
    const unsub = subscribeToChannel('c-gone', () => {});
    unsub();
    subscribed.length = 0;
    // 重新触发 onConnect,不应再补建已取消的目标
    (client as any).opts.onConnect();
    expect(subscribed).not.toContain('/topic/t-tenant_default.channel.c-gone');
  });
});
