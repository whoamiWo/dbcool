import { useState, useRef, useEffect, useCallback } from 'react';
import { Box, IconButton, Typography, Chip, Tooltip } from '@mui/material';
import {
  Mic as MicIcon,
  MicOff as MicOffIcon,
  Videocam as VideoIcon,
  VideocamOff as VideoOffIcon,
  CallEnd as HangupIcon,
  VolumeUp as VolumeIcon,
} from '@mui/icons-material';

interface HuddlePanelProps {
  roomId: string | null;
  onLeave: () => void;
  peers: Array<{ id: string; name: string }>;
}

/** 通过 WebSocket 发送信令消息 */
function sendWs(ws: WebSocket | null, msg: Record<string, unknown>): void {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  ws.send(JSON.stringify(msg));
}

export function HuddlePanel({ roomId, onLeave, peers }: HuddlePanelProps) {
  const [localStream, setLocalStream] = useState<MediaStream | null>(null);
  const [isMuted, setIsMuted] = useState(false);
  const [isVideoOff, setIsVideoOff] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [volume, setVolume] = useState(100);

  // WebRTC 状态
  const peerConnections = useRef<Map<string, RTCPeerConnection>>(new Map());
  const localVideoRef = useRef<HTMLVideoElement>(null);
  const peerVideoRefs = useRef<Map<string, HTMLVideoElement>>(new Map());
  const wsRef = useRef<WebSocket | null>(null);

  /** 发送信令消息的便捷方法 */
  const sendSignal = useCallback((payload: Record<string, unknown>) => {
    sendWs(wsRef.current, payload);
  }, []);

  /** 初始化本地媒体流（复用已有流，避免重复弹权限框） */
  const initStream = useCallback(async () => {
    if (localStream) return;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: true,
        video: !isVideoOff,
      });
      setLocalStream(stream);
      if (localVideoRef.current) {
        localVideoRef.current.srcObject = stream;
      }
      // 将本地音频轨道添加到所有活跃 PeerConnection
      stream.getAudioTracks().forEach(track => {
        peerConnections.current.forEach(pc => pc.addTrack(track, stream));
      });
    } catch (e) {
      console.warn('[Huddle] 无法获取媒体流:', e);
      setError('无法访问麦克风/摄像头，请检查浏览器权限');
    }
  }, [isVideoOff, localStream]);

  /** 创建或获取指定 peerId 的 RTCPeerConnection */
  const getOrCreatePeerConnection = useCallback((peerId: string): RTCPeerConnection => {
    const existing = peerConnections.current.get(peerId);
    if (existing) return existing;

    const pc = new RTCPeerConnection({
      iceServers: [
        { urls: import.meta.env.VITE_ICE_URL || 'stun:stun.l.google.com:19302' },
      ],
    });

    // 收到远端流 → 渲染到对应 <video>
    pc.ontrack = (event) => {
      const videoEl = peerVideoRefs.current.get(peerId);
      if (videoEl) {
        videoEl.srcObject = event.streams[0];
      }
    };

    // ICE candidate 中继
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        sendSignal({ type: 'candidate', peerId, payload: event.candidate });
      }
    };

    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'disconnected' || pc.connectionState === 'failed') {
        peerConnections.current.delete(peerId);
      }
    };

    // 添加本地轨道
    if (localStream) {
      localStream.getTracks().forEach(track => pc.addTrack(track, localStream));
    }

    peerConnections.current.set(peerId, pc);
    return pc;
  }, [localStream, sendSignal]);

  // ── 建立 WebSocket 连接，监听信令消息 ────────────────────────────────────
  useEffect(() => {
    const token = window.localStorage.getItem('nocobase_access_token');
    const ws = new WebSocket(
      `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws/huddle?token=${token}`
    );
    wsRef.current = ws;

    ws.onmessage = (event) => {
      let msg: Record<string, unknown>;
      try {
        msg = JSON.parse(event.data);
      } catch {
        return;
      }

      switch (msg.type) {
        case 'joined':
          setError(null);
          break;
        case 'peer-joined': {
          const peerId = String(msg.peerId ?? '');
          if (!peerId) return;
          setError(null);
          // 已有 peer 加入 → 发起 offer
          const pc = getOrCreatePeerConnection(peerId);
          pc.createOffer()
            .then(offer => pc.setLocalDescription(offer))
            .then(() => sendSignal({ type: 'offer', peerId, payload: pc.localDescription }))
            .catch(err => console.error('[Huddle] offer 创建失败:', err));
          break;
        }
        case 'offer': {
          const peerId = String(msg.from ?? '');
          if (!peerId) return;
          setError(null);
          const pc = getOrCreatePeerConnection(peerId);
          const desc = new RTCSessionDescription(msg.payload as RTCSessionDescriptionInit);
          pc.setRemoteDescription(desc)
            .then(() => pc.createAnswer())
            .then(answer => pc.setLocalDescription(answer))
            .then(() => sendSignal({ type: 'answer', peerId: msg.from, payload: pc.localDescription }))
            .catch(err => console.error('[Huddle] answer 创建失败:', err));
          break;
        }
        case 'answer': {
          const peerId = String(msg.from ?? '');
          if (!peerId) return;
          const pc = peerConnections.current.get(peerId);
          if (!pc) return;
          const desc = new RTCSessionDescription(msg.payload as RTCSessionDescriptionInit);
          pc.setRemoteDescription(desc).catch(err =>
            console.error('[Huddle] setRemoteAnswer 失败:', err)
          );
          break;
        }
        case 'candidate': {
          const peerId = String(msg.from ?? '');
          if (!peerId) return;
          const pc = peerConnections.current.get(peerId);
          if (!pc) return;
          const candidate = msg.payload as RTCIceCandidate;
          pc.addIceCandidate(new RTCIceCandidate(candidate)).catch(err =>
            console.error('[Huddle] addIceCandidate 失败:', err)
          );
          break;
        }
        case 'error':
          setError(String(msg.msg ?? '信令错误'));
          break;
        case 'left':
        case 'peer-left': {
          const leftPeerId = String(
            (msg.type === 'peer-left' ? msg.peerId : msg.roomId) ?? ''
          );
          const pc = peerConnections.current.get(leftPeerId);
          if (pc) { pc.close(); peerConnections.current.delete(leftPeerId); }
          break;
        }
        default:
          break;
      }
    };

    ws.onclose = () => {
      wsRef.current = null;
      peerConnections.current.forEach(pc => pc.close());
      peerConnections.current.clear();
    };

    ws.onerror = () => setError('WebSocket 连接失败，请检查网络连接');

    return () => {
      ws.close();
      wsRef.current = null;
      peerConnections.current.forEach(pc => pc.close());
      peerConnections.current.clear();
    };
  // 仅初始化一次
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── 监听 roomId 变化：加入/离开房间 ────────────────────────────────────
  useEffect(() => {
    if (!roomId) return;
    // 加入房间
    sendSignal({ type: 'join', roomId });
    // 媒体流初始化（延迟确保 WS 已建立）
    const timer = setTimeout(() => { initStream(); }, 500);
    return () => clearTimeout(timer);
  // sendSignal/initStream 是稳定的 useCallback，roomId 变化时重新执行
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roomId]);

  const toggleMute = () => {
    if (!localStream) return;
    localStream.getAudioTracks().forEach(t => (t.enabled = !isMuted));
    setIsMuted(!isMuted);
  };

  const toggleVideo = async () => {
    const nextOff = !isVideoOff;
    setIsVideoOff(nextOff);
    if (localStream) {
      localStream.getVideoTracks().forEach(t => (t.enabled = !nextOff));
    } else {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: !nextOff });
        setLocalStream(stream);
        if (localVideoRef.current) localVideoRef.current.srcObject = stream;
        peerConnections.current.forEach(pc => {
          stream.getTracks().forEach(track => pc.addTrack(track, stream));
        });
      } catch (e) {
        console.warn('[Huddle] 视频流初始化失败:', e);
        setIsVideoOff(true);
      }
    }
  };

  if (!roomId) return null;

  const activePeers = peers.length > 0
    ? [...peers, { id: 'self', name: '我' }]
    : [{ id: 'self', name: '我' }];

  return (
    <Box
      className="glass-strong"
      sx={{
        position: 'fixed',
        bottom: 24,
        right: 24,
        width: 380,
        borderRadius: 3,
        zIndex: 100,
        padding: 2,
        border: '1px solid rgba(255,255,255,0.12)',
        backdropFilter: 'blur(16px)',
        boxShadow: '0 8px 32px rgba(0,0,0,0.5)',
      }}
    >
      {/* 头部 */}
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Box
            sx={{
              width: 10,
              height: 10,
              borderRadius: '50%',
              bgcolor: error ? 'var(--color-error)' : 'var(--color-success)',
              animation: 'pulse 2s infinite',
            }}
          />
          <Typography variant="subtitle2" sx={{ fontWeight: 600, color: 'var(--color-text-primary)' }}>
            Huddle 通话
          </Typography>
          <Chip label={roomId} size="small" sx={{ fontSize: 10, height: 18 }} />
        </Box>
        <Typography variant="caption" sx={{ color: 'var(--color-text-muted)' }}>
          {activePeers.length - 1} 位参与者
        </Typography>
      </Box>

      {/* 错误提示 */}
      {error && (
        <Box
          sx={{
            mb: 1.5,
            p: 1,
            borderRadius: 1,
            bgcolor: 'rgba(239,68,68,0.15)',
            border: '1px solid rgba(239,68,68,0.3)',
            color: 'var(--color-error)',
            fontSize: 12,
          }}
        >
          {error}
        </Box>
      )}

      {/* 参与者网格 */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(120px, 1fr))',
          gap: 1,
          mb: 2,
          minHeight: 160,
        }}
      >
        {activePeers.map((peer) => (
          <Box
            key={peer.id}
            sx={{
              position: 'relative',
              borderRadius: 2,
              overflow: 'hidden',
              background: 'var(--color-bg-tertiary)',
              aspectRatio: '4/3',
              border: peer.id === 'self'
                ? '2px solid var(--color-primary-500)'
                : '1px solid var(--color-border-light)',
            }}
          >
            {peer.id === 'self' ? (
              <video
                ref={localVideoRef}
                autoPlay
                playsInline
                muted
                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
              />
            ) : (
              <video
                ref={(el) => { if (el) peerVideoRefs.current.set(peer.id, el); }}
                autoPlay
                playsInline
                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
              />
            )}
            <Box
              sx={{
                position: 'absolute',
                bottom: 0,
                left: 0,
                right: 0,
                padding: '4px 8px',
                background: 'linear-gradient(transparent, rgba(0,0,0,0.7))',
                color: 'var(--color-text-primary)',
                fontSize: 12,
              }}
            >
              {peer.name}
              {peer.id === 'self' && isMuted && <MicOffIcon sx={{ fontSize: 14, ml: 0.5 }} />}
              {peer.id === 'self' && isVideoOff && <VideoOffIcon sx={{ fontSize: 14, ml: 0.5 }} />}
            </Box>
          </Box>
        ))}
      </Box>

      {/* 控制面板 */}
      <Box sx={{ display: 'flex', justifyContent: 'center', gap: 1 }}>
        <Tooltip title={isMuted ? '取消静音' : '静音'}>
          <IconButton
            onClick={toggleMute}
            sx={{
              bgcolor: isMuted ? 'var(--color-error)' : 'var(--color-bg-tertiary)',
              color: 'var(--color-text-primary)',
              '&:hover': { bgcolor: isMuted ? 'var(--color-error)' : 'rgba(255,255,255,0.1)' },
            }}
          >
            {isMuted ? <MicOffIcon /> : <MicIcon />}
          </IconButton>
        </Tooltip>
        <Tooltip title={isVideoOff ? '开启摄像头' : '关闭摄像头'}>
          <IconButton
            onClick={toggleVideo}
            sx={{
              bgcolor: isVideoOff ? 'var(--color-bg-tertiary)' : 'var(--color-primary-500)',
              color: 'var(--color-text-primary)',
              '&:hover': { bgcolor: isVideoOff ? 'rgba(255,255,255,0.1)' : 'var(--color-primary-600)' },
            }}
          >
            {isVideoOff ? <VideoOffIcon /> : <VideoIcon />}
          </IconButton>
        </Tooltip>
        <Tooltip title="挂断">
          <IconButton
            onClick={onLeave}
            sx={{ bgcolor: 'var(--color-error)', color: 'var(--color-text-primary)', '&:hover': { bgcolor: 'var(--color-error)' } }}
          >
            <HangupIcon />
          </IconButton>
        </Tooltip>
      </Box>

      {/* 音量指示器 */}
      <Box sx={{ mt: 1.5, display: 'flex', alignItems: 'center', gap: 1 }}>
        <VolumeIcon sx={{ fontSize: 16, color: 'var(--color-text-muted)' }} />
        <input
          type="range"
          min="0"
          max="100"
          value={volume}
          onChange={(e) => setVolume(Number(e.target.value))}
          style={{ flex: 1, height: 4, accentColor: 'var(--color-primary-500)' }}
        />
        <Typography variant="caption" sx={{ color: 'var(--color-text-muted)', minWidth: 32 }}>
          {volume}%
        </Typography>
      </Box>
    </Box>
  );
}
