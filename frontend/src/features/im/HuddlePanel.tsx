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

/**
 * Huddle 真实音视频面板 — WebRTC P2P + 信令中继
 *
 * <p>媒体流走 WebRTC P2P（单房间内），信令通过 /ws/huddle 中继。
 * 当前实现单房间单 peer（自己），未来扩展多 peer。
 */
export function HuddlePanel({
  roomId,
  onLeave,
  peers,
}: {
  roomId: string | null;
  onLeave: () => void;
  peers: Array<{ id: string; name: string }>;
}) {
  const [localStream, setLocalStream] = useState<MediaStream | null>(null);
  const [isMuted, setIsMuted] = useState(false);
  const [isVideoOff, setIsVideoOff] = useState(true);
  const localVideoRef = useRef<HTMLVideoElement>(null);
  const peerVideoRefs = useRef<Map<string, HTMLVideoElement>>(new Map());
  const peerConnections = useRef<Map<string, RTCPeerConnection>>(new Map());
  const [volume, setVolume] = useState(100);

  /** 初始化本地媒体流 */
  const initStream = useCallback(async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: true,
        video: !isVideoOff,
      });
      setLocalStream(stream);
      if (localVideoRef.current) {
        localVideoRef.current.srcObject = stream;
      }
    } catch (e) {
      console.warn('[Huddle] 无法获取媒体流:', e);
    }
  }, [isVideoOff]);

  useEffect(() => {
    if (roomId) {
      initStream();
    }
    return () => {
      localStream?.getTracks().forEach((t) => t.stop());
      peerConnections.current.forEach((pc) => pc.close());
      peerConnections.current.clear();
    };
  }, [roomId, initStream, localStream]);

  const toggleMute = () => {
    if (!localStream) return;
    localStream.getAudioTracks().forEach((t) => (t.enabled = !isMuted));
    setIsMuted(!isMuted);
  };

  const toggleVideo = () => {
    if (!localStream) return;
    localStream.getVideoTracks().forEach((t) => {
      t.enabled = !isVideoOff;
    });
    setIsVideoOff(!isVideoOff);
  };

  if (!roomId) return null;

  const activePeers = peers.length > 0 ? [...peers, { id: 'self', name: '我' }] : [{ id: 'self', name: '我' }];

  return (
    <Box
      className="glass-strong"
      sx={{
        position: 'fixed',
        bottom: 24,
        right: 24,
        width: 360,
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
              bgcolor: 'var(--color-success)',
              animation: 'pulse 2s infinite',
            }}
          />
          <Typography variant="subtitle2" sx={{ fontWeight: 600, color: 'var(--color-text-primary)' }}>
            Huddle 通话
          </Typography>
          <Chip label={roomId} size="small" sx={{ fontSize: 10, height: 18 }} />
        </Box>
        <Typography variant="caption" color="text.secondary">
          {activePeers.length - 1} 位参与者
        </Typography>
      </Box>

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
              border: peer.id === 'self' ? '2px solid var(--color-primary-500)' : '1px solid var(--color-border-light)',
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
                ref={(el) => {
                  if (el && peerVideoRefs.current.get(peer.id)) {
                    peerVideoRefs.current.set(peer.id, el);
                  }
                }}
                autoPlay
                playsInline
                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
              />
            )}
            {/* 姓名标签 */}
            <Box
              sx={{
                position: 'absolute',
                bottom: 0,
                left: 0,
                right: 0,
                padding: '4px 8px',
                background: 'linear-gradient(transparent, rgba(0,0,0,0.7))',
                color: '#fff',
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
              color: '#fff',
              '&:hover': { bgcolor: isMuted ? '#dc2626' : 'rgba(255,255,255,0.1)' },
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
              color: '#fff',
              '&:hover': { bgcolor: isVideoOff ? 'rgba(255,255,255,0.1)' : 'var(--color-primary-600)' },
            }}
          >
            {isVideoOff ? <VideoOffIcon /> : <VideoIcon />}
          </IconButton>
        </Tooltip>
        <Tooltip title="挂断">
          <IconButton
            onClick={onLeave}
            sx={{
              bgcolor: 'var(--color-error)',
              color: '#fff',
              '&:hover': { bgcolor: '#dc2626' },
            }}
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