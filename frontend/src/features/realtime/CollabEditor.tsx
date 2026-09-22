import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import * as Y from 'yjs';
import {
  Alert, Avatar, Box, Button, Chip, CircularProgress, Stack, TextField, Typography,
} from '@mui/material';
import {
  sendCollabJoin, sendCollabLeave, sendCollabUpdate, subscribeToCollab,
} from '@/lib/stompClient';
import { useAuthStore } from '@/stores/auth';

/** Uint8Array → Base64(浏览器环境)。 */
function toBase64(bytes: Uint8Array): string {
  let bin = '';
  bytes.forEach((b) => { bin += String.fromCharCode(b); });
  return btoa(bin);
}

/** Base64 → Uint8Array。 */
function fromBase64(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) out[i] = bin.charCodeAt(i);
  return out;
}

interface Props {
  docId: string;
  title: string;
  initialContent?: string;
  onSave?: (content: string) => Promise<void> | void;
}

/**
 * 多人协同编辑器 — Yjs CRDT + STOMP 通道。
 *
 * <p>一致性由 Yjs 在客户端保证,服务端只做增量转发(不解析文档内容)。
 * 本地编辑以「整篇替换」写入 Y.Text 产生 update 并广播;收到远端 update 后
 * 应用并回写编辑区(段落级协同 —— 字符级合并需引入编辑器绑定,后续增强)。
 */
export default function CollabEditor({ docId, title, initialContent = '', onSave }: Props) {
  const ydoc = useMemo(() => new Y.Doc(), [docId]);
  const ytext = useMemo(() => ydoc.getText('content'), [ydoc]);

  const [text, setText] = useState(initialContent);
  const [saved, setSaved] = useState(true);
  const [saving, setSaving] = useState(false);
  const [connected, setConnected] = useState(false);
  const [collaborators, setCollaborators] = useState<Array<{ userId: string; username: string }>>([]);
  const [error, setError] = useState<string | null>(null);

  const me = useAuthStore((s) => s.user) as unknown as Record<string, unknown> | null;
  const myId = String(me?.id ?? me?.user_id ?? me?.userId ?? '');
  const myName = String(me?.username ?? me?.name ?? '我');

  // 远端更新回写时避免再次触发广播
  const applyingRemote = useRef(false);

  // 初始化文档内容
  useEffect(() => {
    if (ytext.length === 0 && initialContent) {
      ytext.insert(0, initialContent);
    }
    setText(ytext.toString());
    setSaved(true);
  }, [docId, ytext, initialContent]);

  // 本地 update → 广播(远端应用引起的更新不回播)
  useEffect(() => {
    const handler = (update: Uint8Array, origin: unknown) => {
      if (origin === 'remote' || applyingRemote.current) return;
      try {
        sendCollabUpdate(docId, toBase64(update));
        setSaved(false);
      } catch (e) {
        console.error('[collab] 广播增量失败:', e);
        setError('协同连接不可用,编辑仅在本地生效。');
      }
    };
    ydoc.on('update', handler);
    return () => ydoc.off('update', handler);
  }, [ydoc, docId]);

  // 订阅远端增量 + 加入/离开房间
  useEffect(() => {
    let unsub: (() => void) | null = null;
    try {
      unsub = subscribeToCollab(docId, (body) => {
        try {
          const msg = JSON.parse(body) as {
            userId?: string; update?: string; type?: string;
            username?: string; action?: string;
          };
          // 在线状态事件
          if (msg.type === 'presence') {
            const uid = msg.userId ?? '';
            if (!uid || uid === myId) return;
            setCollaborators((prev) => {
              const others = prev.filter((c) => c.userId !== uid);
              return msg.action === 'leave'
                ? others
                : [...others, { userId: uid, username: msg.username || uid.slice(0, 6) }];
            });
            return;
          }
          // 文档增量:忽略自己发出的
          if (msg.userId && msg.userId === myId) return;
          if (!msg.update) return;
          applyingRemote.current = true;
          Y.applyUpdate(ydoc, fromBase64(msg.update), 'remote');
          setText(ytext.toString());
          applyingRemote.current = false;
        } catch (e) {
          console.error('[collab] 处理远端消息失败:', e);
        }
      });
      sendCollabJoin(docId);
      setConnected(true);
    } catch (e) {
      console.error('[collab] 订阅失败:', e);
      setError('无法连接协同服务,请检查网络后重试。');
    }

    return () => {
      try { sendCollabLeave(docId); } catch { /* 离开失败忽略 */ }
      unsub?.();
    };
  }, [docId, ydoc, ytext, myId]);

  /** 本地编辑 → 整篇写入 Y.Text(产生 update 并广播)。 */
  const handleChange = useCallback((value: string) => {
    setText(value);
    ydoc.transact(() => {
      ytext.delete(0, ytext.length);
      ytext.insert(0, value);
    });
  }, [ydoc, ytext]);

  const handleSave = useCallback(async () => {
    if (!onSave) return;
    setSaving(true);
    setError(null);
    try {
      await onSave(ytext.toString());
      setSaved(true);
    } catch (e) {
      console.error('[collab] 保存失败:', e);
      setError('保存失败,请稍后重试。');
    } finally {
      setSaving(false);
    }
  }, [onSave, ytext]);

  return (
    <Box>
      <Stack direction="row" spacing={2} sx={{ mb: 2, alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography variant="h6" sx={{ fontWeight: 600 }}>{title}</Typography>
        <Chip
          size="small"
          label={connected ? '协同已连接' : '未连接'}
          color={connected ? 'success' : 'default'}
        />
        <Chip size="small" label={saved ? '已保存' : '未保存'} color={saved ? 'default' : 'warning'} />
        <Box sx={{ flex: 1 }} />
        {/* 在线协作者 */}
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
          <Avatar sx={{ width: 28, height: 28, bgcolor: 'var(--color-info)', fontSize: 12 }}>
            {myName.slice(0, 1)}
          </Avatar>
          {collaborators.map((c) => (
            <Avatar
              key={c.userId}
              title={c.username}
              sx={{ width: 28, height: 28, bgcolor: 'var(--color-warning)', fontSize: 12 }}
            >
              {c.username.slice(0, 1)}
            </Avatar>
          ))}
        </Stack>
        {onSave && (
          <Button variant="contained" size="small" onClick={handleSave} disabled={saving || saved}>
            {saving ? <CircularProgress size={16} /> : '保存'}
          </Button>
        )}
      </Stack>

      {error && <Alert severity="warning" sx={{ mb: 2 }}>{error}</Alert>}

      <TextField
        multiline
        fullWidth
        minRows={16}
        value={text}
        onChange={(e) => handleChange(e.target.value)}
        placeholder="在此协作编辑文档内容…"
        slotProps={{ htmlInput: { 'aria-label': '协同编辑区' } }}
      />

      <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
        多人同时编辑时以段落级同步;改动会实时广播给同一文档的其他成员。
      </Typography>
    </Box>
  );
}
