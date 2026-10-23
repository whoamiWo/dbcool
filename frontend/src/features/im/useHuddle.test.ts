/**
 * PHASE 4.2 覆盖率补测：features/im/useHuddle.ts
 *
 * 该 hook 只做 WebRTC **信令**（不碰媒体流）：建立 /ws/huddle WS 连接，
 * 按服务端消息维护 roomId 与 peers，并暴露 join/leave/sendSignal。
 * 测试用可控 WebSocket 桩驱动各分支。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useHuddle } from './useHuddle';

class MockWS {
  static instances: MockWS[] = [];
  static OPEN = 1;
  static CLOSED = 3;
  readyState = MockWS.OPEN;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  sent: string[] = [];
  closed = false;

  constructor(public url: string) {
    MockWS.instances.push(this);
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.closed = true;
  }
}

beforeEach(() => {
  MockWS.instances = [];
  vi.stubGlobal('WebSocket', MockWS);
  localStorage.setItem('nocobase_access_token', 'tok');
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** 让 hook 进入"已加入房间"状态（roomId 由服务端 joined 消息驱动）。 */
function joinRoom(ws: MockWS, rid = 'room1') {
  act(() => {
    ws.onmessage?.({ data: JSON.stringify({ type: 'joined', roomId: rid }) });
  });
}

describe('useHuddle', () => {
  it('初始状态：无房间、无 peer', () => {
    const { result } = renderHook(() => useHuddle());
    expect(result.current.roomId).toBeNull();
    expect(result.current.peers.size).toBe(0);
  });

  it('挂载后建立带 token 的 /ws/huddle 连接', () => {
    renderHook(() => useHuddle());
    const ws = MockWS.instances[0];
    expect(ws).toBeDefined();
    expect(ws.url).toContain('/ws/huddle');
    expect(ws.url).toContain('token=tok');
  });

  it('joined 消息设置 roomId', async () => {
    const { result } = renderHook(() => useHuddle());
    joinRoom(MockWS.instances[0], 'r1');
    await waitFor(() => expect(result.current.roomId).toBe('r1'));
  });

  it('peer-joined / peer-left 维护 peers', async () => {
    const { result } = renderHook(() => useHuddle());
    const ws = MockWS.instances[0];
    act(() => {
      ws.onmessage?.({ data: JSON.stringify({ type: 'peer-joined', peerId: 'p1' }) });
    });
    await waitFor(() => expect(result.current.peers.has('p1')).toBe(true));
    act(() => {
      ws.onmessage?.({ data: JSON.stringify({ type: 'peer-left', peerId: 'p1' }) });
    });
    await waitFor(() => expect(result.current.peers.has('p1')).toBe(false));
  });

  it('left 消息清空房间与 peers', async () => {
    const { result } = renderHook(() => useHuddle());
    const ws = MockWS.instances[0];
    joinRoom(ws);
    act(() => {
      ws.onmessage?.({ data: JSON.stringify({ type: 'peer-joined', peerId: 'p1' }) });
    });
    await waitFor(() => expect(result.current.roomId).toBe('room1'));
    act(() => {
      ws.onmessage?.({ data: JSON.stringify({ type: 'left' }) });
    });
    await waitFor(() => {
      expect(result.current.roomId).toBeNull();
      expect(result.current.peers.size).toBe(0);
    });
  });

  it('error 消息走 warn 分支且不抛错', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderHook(() => useHuddle());
    act(() => {
      MockWS.instances[0].onmessage?.({ data: JSON.stringify({ type: 'error', msg: 'bad' }) });
    });
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('非法 JSON 消息被忽略', async () => {
    const { result } = renderHook(() => useHuddle());
    act(() => {
      MockWS.instances[0].onmessage?.({ data: 'not-json' });
    });
    await waitFor(() => expect(result.current.roomId).toBeNull());
  });

  it('onclose 清空房间与 peers', async () => {
    const { result } = renderHook(() => useHuddle());
    const ws = MockWS.instances[0];
    joinRoom(ws);
    await waitFor(() => expect(result.current.roomId).toBe('room1'));
    act(() => ws.onclose?.());
    await waitFor(() => expect(result.current.roomId).toBeNull());
  });

  it('joinHuddle 发送 join 信令', async () => {
    const { result } = renderHook(() => useHuddle());
    const ws = MockWS.instances[0];
    act(() => result.current.joinHuddle('rX'));
    await waitFor(() => expect(ws.sent.some(s => s.includes('"join"'))).toBe(true));
    expect(ws.sent.some(s => s.includes('rX'))).toBe(true);
  });

  it('leaveHuddle 在已加入时发送 leave 并清空', async () => {
    const { result } = renderHook(() => useHuddle());
    const ws = MockWS.instances[0];
    joinRoom(ws, 'r2');
    await waitFor(() => expect(result.current.roomId).toBe('r2'));
    act(() => result.current.leaveHuddle());
    await waitFor(() => expect(result.current.roomId).toBeNull());
    expect(ws.sent.some(s => s.includes('"leave"'))).toBe(true);
  });

  it('未加入房间时 leaveHuddle / sendSignal 为空操作', () => {
    const { result } = renderHook(() => useHuddle());
    const ws = MockWS.instances[0];
    act(() => {
      result.current.leaveHuddle();
      result.current.sendSignal({ a: 1 });
    });
    expect(ws.sent).toEqual([]);
  });

  it('已加入后 sendSignal 附带 roomId 发送', async () => {
    const { result } = renderHook(() => useHuddle());
    const ws = MockWS.instances[0];
    joinRoom(ws, 'r3');
    await waitFor(() => expect(result.current.roomId).toBe('r3'));
    act(() => result.current.sendSignal({ offer: 'sdp' }));
    await waitFor(() => expect(ws.sent.some(s => s.includes('offer'))).toBe(true));
    expect(ws.sent.some(s => s.includes('r3'))).toBe(true);
  });

  it('卸载时关闭 WS', () => {
    const { unmount } = renderHook(() => useHuddle());
    unmount();
    expect(MockWS.instances[0].closed).toBe(true);
  });
});
