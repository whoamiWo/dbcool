import { useCallback, useEffect, useRef, useState } from 'react';

interface HuddleMessage {
  type: 'joined' | 'left' | 'peer-joined' | 'peer-left' | 'error';
  roomId?: string;
  peerId?: string;
  msg?: string;
}

/**
 * Huddle WebRTC 信令钩子 — 管理单个房间的 WebSocket 信令连接。
 *
 * <p>媒体流通过 SFU（Janus/mediasoup）或 P2P WebRTC 处理，
 * 此 hook 仅负责信令（offer/answer/ICE candidate 的交换由上层组件完成）。
 */
export function useHuddle() {
  const [roomId, setRoomId] = useState<string | null>(null);
  const [peers, setPeers] = useState<Map<string, string>>(new Map());
  const wsRef = useRef<WebSocket | null>(null);
  const roomIdRef = useRef<string | null>(null);

  // 建立 WebSocket 连接
  useEffect(() => {
    const token = window.localStorage.getItem('nocobase_access_token');
    const ws = new WebSocket(
      `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws/huddle?token=${token}`
    );
    wsRef.current = ws;

    ws.onmessage = (event) => {
      let msg: HuddleMessage;
      try {
        msg = JSON.parse(event.data) as HuddleMessage;
      } catch {
        return;
      }

      switch (msg.type) {
        case 'joined':
          if (msg.roomId) {
            setRoomId(msg.roomId);
            roomIdRef.current = msg.roomId;
          }
          break;
        case 'left':
          setRoomId(null);
          roomIdRef.current = null;
          setPeers(new Map());
          break;
        case 'peer-joined': {
          const peerId = msg.peerId;
          if (peerId) {
            setPeers(prev => {
              const next = new Map(prev);
              next.set(peerId, peerId);
              return next;
            });
          }
          break;
        }
        case 'peer-left': {
          const peerId = msg.peerId;
          if (peerId) {
            setPeers(prev => {
              const next = new Map(prev);
              next.delete(peerId);
              return next;
            });
          }
          break;
        }
        case 'error':
          console.warn('[Huddle] 信令错误:', msg.msg);
          break;
      }
    };

    ws.onclose = () => {
      setRoomId(null);
      roomIdRef.current = null;
      setPeers(new Map());
    };

    return () => {
      ws.close();
      wsRef.current = null;
    };
  }, []);

  const joinHuddle = useCallback((rid: string) => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify({ type: 'join', roomId: rid }));
  }, []);
  const leaveHuddle = useCallback(() => {
    const ws = wsRef.current;
    const rid = roomIdRef.current;
    if (!ws || !rid) return;
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'leave', roomId: rid }));
    }
    setRoomId(null);
    roomIdRef.current = null;
    setPeers(new Map());
  }, []);

  /** 发送 offer/answer/ICE candidate 信令 */
  const sendSignal = useCallback((payload: Record<string, unknown>) => {
    const ws = wsRef.current;
    const rid = roomIdRef.current;
    if (!ws || !rid) return;
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ ...payload, roomId: rid }));
    }
  }, []);

  return { roomId, joinHuddle, leaveHuddle, sendSignal, peers };
}
