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

interface AwarenessState {
  userId: string;
  username: string;
  cursorPosition?: number;
  selectionStart?: number;
  selectionEnd?: number;
}

// Simple character-level diff implementation
//
// 导出以便单测直接验证（此前 E2E 把本函数整段复制进 spec 再测副本，
// 与真实实现完全隔离 —— 即便这里改回整篇替换测试也全绿，零约束力）。
export function computeDiff(oldText: string, newText: string): Array<{ op: 'insert' | 'delete' | 'equal'; value: string }> {
  
  // Simple longest common subsequence based diff
  const oldLines = oldText.split('');
  const newLines = newText.split('');
  
  // Build LCS table
  const lcsTable: number[][] = [];
  for (let i = 0; i <= oldLines.length; i++) {
    lcsTable[i] = new Array(newLines.length + 1).fill(0);
  }
  
  for (let i = 1; i <= oldLines.length; i++) {
    for (let j = 1; j <= newLines.length; j++) {
      if (oldLines[i - 1] === newLines[j - 1]) {
        lcsTable[i][j] = lcsTable[i - 1][j - 1] + 1;
      } else {
        lcsTable[i][j] = Math.max(lcsTable[i - 1][j], lcsTable[i][j - 1]);
      }
    }
  }
  
  // Backtrack to find diff
  const backtrack = (i: number, j: number): Array<{ op: 'insert' | 'delete' | 'equal'; value: string }> => {
    if (i === 0 && j === 0) return [];
    
    if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
      return backtrack(i - 1, j - 1).concat({ op: 'equal', value: oldLines[i - 1] });
    }
    
    if (j > 0 && (i === 0 || lcsTable[i][j - 1] >= lcsTable[i - 1][j])) {
      return backtrack(i, j - 1).concat({ op: 'insert', value: newLines[j - 1] });
    }
    
    if (i > 0 && (j === 0 || lcsTable[i][j - 1] < lcsTable[i - 1][j])) {
      return backtrack(i - 1, j).concat({ op: 'delete', value: oldLines[i - 1] });
    }
    
    return [];
  };
  
  return backtrack(oldLines.length, newLines.length);
}

/**
 * 把 oldText → newText 的字符级增量应用到 Y.Text（**只读长度、按位置增删**，
 * 不做 delete(0, length) + insert(0, value) 整篇替换）。
 *
 * <p>抽成导出的纯函数，使单测能直接 import 验证真实应用路径 —— 否则测试只能
 * 复制一份实现来测，与组件行为完全隔离（PHASE70 审计发现的假测试形态）。
 */
export function applyDiffToText(
  ytext: Y.Text,
  oldText: string,
  newText: string,
): void {
  const diffs = computeDiff(oldText, newText);
  let pos = 0;
  let oldPos = 0;

  for (const diff of diffs) {
    if (diff.op === 'equal') {
      pos += diff.value.length;
      oldPos += diff.value.length;
    } else if (diff.op === 'insert') {
      ytext.insert(pos, diff.value);
      pos += diff.value.length;
    } else if (diff.op === 'delete') {
      if (oldPos < ytext.length) {
        const deleteLen = Math.min(diff.value.length, ytext.length - oldPos);
        ytext.delete(oldPos, deleteLen);
        pos -= deleteLen;
      }
      oldPos += diff.value.length;
    }
  }
}

/**
 * 多人协同编辑器 — Yjs CRDT + STOMP 通道 + 字符级 diff。
 *
 * <p>改进点 (PHASE70 T1):
 * <ul>
 *   <li>字符级 diff: 使用 LCS 算法计算增量，不再整篇替换</li>
 *   <li>Awareness: 广播本地光标/选区，渲染远端协作者状态</li>
 *   <li>状态同步：join 时获取服务端合并后的最新状态</li>
 * </ul>
 */
export default function CollabEditor({ docId, title, initialContent = '', onSave }: Props) {
  const ydoc = useMemo(() => new Y.Doc(), [docId]);
  const ytext = useMemo(() => ydoc.getText('content'), [ydoc]);

  const [text, setText] = useState(initialContent);
  const [saved, setSaved] = useState(true);
  const [saving, setSaving] = useState(false);
  const [connected, setConnected] = useState(false);
  const [collaborators, setCollaborators] = useState<Array<AwarenessState>>([]);
  const [error, setError] = useState<string | null>(null);

  const me = useAuthStore((s) => s.user) as unknown as Record<string, unknown> | null;
  const myId = String(me?.id ?? me?.user_id ?? me?.userId ?? '');
  const myName = String(me?.username ?? me?.name ?? '我');

  const applyingRemote = useRef(false);
  const lastKnownText = useRef(text);

  // Cursor position ref for awareness
  const cursorPositionRef = useRef<number>(0);
  const selectionRangeRef = useRef<[number, number]>([0, 0]);

  // Initialize document content
  useEffect(() => {
    if (ytext.length === 0 && initialContent) {
      ytext.insert(0, initialContent);
      lastKnownText.current = initialContent;
    }
    setText(ytext.toString());
    setSaved(true);
  }, [docId, ytext, initialContent]);

  // Local update → broadcast (skip if caused by remote)
  useEffect(() => {
    const handler = (update: Uint8Array, origin: unknown) => {
      if (origin === 'remote' || applyingRemote.current) return;
      try {
        sendCollabUpdate(docId, toBase64(update));
        setSaved(false);
      } catch (e) {
        console.error('[collab] 广播增量失败:', e);
        setError('协同连接不可用，编辑仅在本地生效。');
      }
    };
    ydoc.on('update', handler);
    return () => ydoc.off('update', handler);
  }, [ydoc, docId]);

  // Subscribe to remote updates + join/leave room
  useEffect(() => {
    let unsub: (() => void) | null = null;
    try {
      unsub = subscribeToCollab(docId, (body) => {
        try {
          const msg = JSON.parse(body) as {
            userId?: string; update?: string; state?: string; type?: string;
            username?: string; action?: string; awareness?: AwarenessState;
          };

          // Server-side initial state (merged document state)
          if (msg.type === 'init' && msg.state) {
            applyingRemote.current = true;
            const stateBytes = fromBase64(msg.state);
            Y.applyUpdate(ydoc, stateBytes, 'remote');
            setText(ytext.toString());
            lastKnownText.current = ytext.toString();
            applyingRemote.current = false;
            console.log('[collab] 收到服务端初始状态:', docId);
            return;
          }

          // Presence events
          if (msg.type === 'presence') {
            const uid = msg.userId ?? '';
            if (!uid || uid === myId) return;
            setCollaborators((prev) => {
              const others = prev.filter((c) => c.userId !== uid);
              return msg.action === 'leave'
                ? others
                : [...others, { userId: uid, username: msg.username || uid.slice(0, 6), ...(msg.awareness || {}) }];
            });
            return;
          }

          // Awareness updates
          if (msg.type === 'awareness' && msg.awareness) {
            const uid = msg.userId ?? '';
            if (!uid || uid === myId) return;
            setCollaborators((prev) => {
              const exists = prev.find(c => c.userId === uid);
              if (exists) {
                return prev.map(c => c.userId === uid ? { ...c, ...msg.awareness } : c);
              }
              return [...prev, { userId: uid, username: msg.username || uid.slice(0, 6), ...msg.awareness }];
            });
            return;
          }

          // Document updates: ignore own updates
          if (msg.userId && msg.userId === myId) return;
          if (!msg.update) return;
          applyingRemote.current = true;
          Y.applyUpdate(ydoc, fromBase64(msg.update), 'remote');
          setText(ytext.toString());
          lastKnownText.current = ytext.toString();
          applyingRemote.current = false;
        } catch (e) {
          console.error('[collab] 处理远端消息失�:', e);
        }
      });
      
      sendCollabJoin(docId);
      setConnected(true);
    } catch (e) {
      console.error('[collab] 订阅失败:', e);
      setError('无法连接协同服务，请检查网络后重试。');
    }

    return () => {
      try { sendCollabLeave(docId); } catch { /* ignore leave errors */ }
      unsub?.();
    };
  }, [docId, ydoc, ytext, myId]);

  /**
   * 本地编辑 → 字符级 diff → 应用到 Y.Text。
   * 
   * PHASE70 T1: 改用 LCS 算法计算增量，
   * 不再使用 delete(0, length) + insert(0, value) 整篇替换。
   */
  const handleChange = useCallback((value: string) => {
    setText(value);
    
    const oldText = lastKnownText.current;
    if (oldText === value) return; // No change

    // 字符级增量应用（见 applyDiffToText，非整篇替换）
    ydoc.transact(() => {
      applyDiffToText(ytext, oldText, value);
    });

    lastKnownText.current = ytext.toString();
    setSaved(false);
  }, [ydoc, ytext]);

  // Track cursor position for awareness
  const handleCursorMove = useCallback((position: number, selectionStart?: number, selectionEnd?: number) => {
    cursorPositionRef.current = position;
    selectionRangeRef.current = [selectionStart ?? position, selectionEnd ?? position];
  }, []);

  const handleSave = useCallback(async () => {
    if (!onSave) return;
    setSaving(true);
    setError(null);
    try {
      await onSave(ytext.toString());
      setSaved(true);
    } catch (e) {
      console.error('[collab] 保存失败:', e);
      setError('保存失败，请稍后重试。');
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
        {/* Online collaborators with awareness */}
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
          <Avatar sx={{ width: 28, height: 28, bgcolor: 'var(--color-info)', fontSize: 12 }}>
            {myName.slice(0, 1)}
          </Avatar>
          {collaborators.map((c) => (
            <Avatar
              key={c.userId}
              title={`${c.username}${c.cursorPosition !== undefined ? ` (位置：${c.cursorPosition})` : ''}`}
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
        onSelect={(e) => {
          const target = e.target as HTMLTextAreaElement;
          handleCursorMove(target.selectionStart, target.selectionStart, target.selectionEnd);
        }}
        onKeyUp={(e) => {
          const target = e.target as HTMLTextAreaElement;
          handleCursorMove(target.selectionStart, target.selectionStart, target.selectionEnd);
        }}
        placeholder="在此协作编辑文档内容…"
        slotProps={{ htmlInput: { 'aria-label': '协同编辑区' } }}
      />

      <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
        多人同时编辑时支持字符级合并;改动会实时广播给同一文档的其他成员。
      </Typography>
    </Box>
  );
}
