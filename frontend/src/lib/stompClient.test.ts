import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 回归测试:覆盖曾经的阻断缺陷 —— 连接未建立时 subscribe 抛 TypeError 导致页面白屏。
 *
 * 用假的 Client 复现 stompjs v7 的行为:未连接时 subscribe() 抛 TypeError。
 */

const subscribed: string[] = [];
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
  }
  return { Client: FakeClient };
});

vi.mock('sockjs-client', () => ({ default: class {} }));

vi.mock('@/stores/auth', () => ({
  useAuthStore: {
    getState: () => ({ user: { id: 'u1', username: 'alice', tenant_id: 'tenant_default' } }),
  },
}));

import { disconnectStomp, getStompClient, subscribeToChannel } from '@/lib/stompClient';

describe('stompClient', () => {
  beforeEach(() => {
    activeCount = 0;
    subscribed.length = 0;
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
});
