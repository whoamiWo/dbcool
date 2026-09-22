import { useEffect, useRef, useState } from "react";
import {
  addReaction,
  removeReaction,
  getReactions,
  type ImMessage,
  type MessageReaction,
} from "./api";
const EMOJIS = ["👍", "❤️", "😂", "🎉", "😮", "🙏"];
interface MessageListProps {
  currentUserId: string;
  messages: ImMessage[];
  onMessageClick?: (message: ImMessage) => void;
  canLoadMore?: boolean;
  isLoadingMore?: boolean;
  onLoadMore?: () => void;
  onEditMessage?: (messageId: string, content: string) => void;
  onDeleteMessage?: (messageId: string) => void;
  pinnedMessageIds?: Set<string>;
  onBurnExpired?: (messageId: string) => void;
}
export function MessageList({
  currentUserId,
  messages,
  onMessageClick,
  canLoadMore,
  isLoadingMore,
  onLoadMore,
  onEditMessage,
  onDeleteMessage,
  pinnedMessageIds,
  onBurnExpired,
}: MessageListProps) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editContent, setEditContent] = useState("");
  const [pickerFor, setPickerFor] = useState<string | null>(null);
  const [reactionsByMsg, setReactionsByMsg] = useState<
    Record<string, MessageReaction[]>
  >({});
  const loadReactions = async (messageId: string) => {
    try {
      const res = await getReactions(messageId);
      setReactionsByMsg((prev) => ({ ...prev, [messageId]: res.data }));
    } catch (e) {
      console.error("加载表情失败", e);
    }
  };
  const ensureReactions = (messageId: string) => {
    if (!reactionsByMsg[messageId]) loadReactions(messageId);
  };
  const toggleReaction = (messageId: string, emoji: string, userId: string) => {
    const current = reactionsByMsg[messageId] ?? [];
    const mine = current.some((r) => r.emoji === emoji && r.userId === userId);
    if (mine) {
      removeReaction(messageId, emoji)
        .then(() => loadReactions(messageId))
        .catch((e) => console.error("取消表情失败", e));
    } else {
      addReaction(messageId, emoji)
        .then(() => loadReactions(messageId))
        .catch((e) => console.error("添加表情失败", e));
    }
  };
  const renderContent = (content: string, isBurned: boolean) => {
    if (isBurned) {
      return <span style={{ color: 'var(--color-text-muted)', fontStyle: 'italic' }}>该消息已焚毁</span>;
    }
    const parts = content.split(/(@[A-Za-z0-9_\-]+)/);
    return (
      <span>
        {parts.map((part, i) =>
          part.startsWith('@') ? (
            <span
              key={i}
              style={{
                background: 'rgba(99, 102, 241, 0.2)',
                color: 'var(--color-primary-300)',
                padding: '1px 6px',
                borderRadius: 10,
                fontWeight: 500,
              }}
            >
              {part}
            </span>
          ) : (
            <span key={i}>{part}</span>
          ),
        )}
      </span>
    );
  };

  const [burnRemaining, setBurnRemaining] = useState<Record<string, number>>({});
  const burnTimersRef = useRef<Map<string, number>>(new Map());

  useEffect(() => {
    const now = Date.now();
    const active = new Map<string, number>();
    for (const m of messages) {
      if (m.expiresAt) {
        const ms = new Date(m.expiresAt).getTime() - now;
        if (ms > 0) active.set(m.id, ms);
      }
    }
    setBurnRemaining((prev) => {
      void prev;
      const next: Record<string, number> = {};
      for (const [id, ms] of active) next[id] = ms;
      return next;
    });
    for (const [id, t] of burnTimersRef.current) {
      if (!active.has(id)) {
        window.clearTimeout(t);
        burnTimersRef.current.delete(id);
      }
    }
    for (const [id, ms] of active) {
      if (burnTimersRef.current.has(id)) continue;
      const t = window.setTimeout(() => {
        onBurnExpired?.(id);
        burnTimersRef.current.delete(id);
        setBurnRemaining((p) => { const n = { ...p }; delete n[id]; return n; });
      }, ms);
      burnTimersRef.current.set(id, t);
    }
    return () => {
      for (const t of burnTimersRef.current.values()) window.clearTimeout(t);
      burnTimersRef.current.clear();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages, onBurnExpired]);

  const formatBurnTime = (ms: number) => {
    const s = Math.max(0, Math.ceil(ms / 1000));
    if (s < 60) return `${s}秒`;
    const m = Math.floor(s / 60);
    const r = s % 60;
    return `${m}分${r}秒`;
  };

  const formatTime = (time: string) => {
    const d = new Date(time);
    return d.toLocaleString("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  };
  const groupReactions = (list: MessageReaction[]) => {
    const map = new Map<string, number>();
    for (const r of list) {
      map.set(r.emoji, (map.get(r.emoji) ?? 0) + 1);
    }
    return [...map.entries()];
  };
  return (
    <div
      style={{ flex: 1, overflowY: "auto", padding: 16, background: 'rgba(15, 23, 42, 0.3)' }}
    >
      {canLoadMore && (
        <div style={{ textAlign: "center", margin: "8px 0" }}>
          <button
            onClick={onLoadMore}
            disabled={isLoadingMore}
            className="glass-button"
            style={{ padding: "6px 16px", fontSize: 12, opacity: isLoadingMore ? 0.6 : 1 }}
          >
            {isLoadingMore ? "加载中..." : "加载更早消息"}
          </button>
        </div>
      )}
      {messages.map((msg, index) => {
        const isSelf = msg.senderId === currentUserId;
        const isDeleted = !!msg.deletedAt;
        const isReply = !!msg.parentId;
        const isEditing = editingId === msg.id;
        const prevMessage = messages[index - 1];
        const showAvatar =
          !prevMessage || prevMessage.senderId !== msg.senderId;
        return (
          <div
            key={msg.id}
            style={{
              margin: "8px 0",
              display: "flex",
              flexDirection: "column",
              position: "relative",
            }}
          >
            {pinnedMessageIds?.has(msg.id) && (
              <div
                style={{
                  fontSize: 11,
                  color: 'var(--color-warning)',
                  padding: '2px 8px',
                  marginBottom: 4,
                  background: 'var(--color-warning)',
                  borderRadius: 'var(--radius-sm)',
                  width: 'fit-content',
                  border: '1px solid var(--color-warning)',
                }}
              >
                📌 已置顶
              </div>
            )}
            <div style={{ display: "flex", gap: 8, alignItems: "flex-start" }}>
              {showAvatar && (
                <div
                  style={{
                    width: 32,
                    height: 32,
                    borderRadius: "50%",
                    background: isSelf ? 'var(--color-primary-500)' : 'var(--color-bg-tertiary)',
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    color: "white",
                    fontSize: 12,
                    fontWeight: 600,
                    flexShrink: 0,
                    boxShadow: 'var(--shadow-sm)',
                  }}
                >
                  {msg.senderId.substring(0, 2).toUpperCase()}
                </div>
              )}
              <div style={{ flex: 1, minWidth: 0 }}>
                {!showAvatar && <div style={{ height: 20 }} />}
                <div
                  onClick={() => onMessageClick?.(msg)}
                  className={`message-bubble ${isSelf ? 'message-bubble-self' : ''} ${isReply ? 'message-bubble-replied' : ''}`}
                  style={{
                    maxWidth: "fit-content",
                    marginLeft: isSelf ? "auto" : 0,
                  }}
                >
                  <div
                    style={{ fontSize: 11, color: 'var(--color-text-muted)', marginBottom: 4 }}
                  >
                    用户 {msg.senderId.substring(0, 8)}
                    {isReply && (
                      <span style={{ marginLeft: 8, color: 'var(--color-warning)' }}>
                        回复
                      </span>
                    )}
                  </div>
                  {isEditing && !isDeleted ? (
                    <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
                      <input
                        value={editContent}
                        onChange={(e) => setEditContent(e.target.value)}
                        className="input-glass"
                        style={{ flex: 1, padding: "4px 8px", fontSize: 13, minWidth: 160 }}
                      />
                      <button
                        onClick={() => {
                          onEditMessage?.(msg.id, editContent);
                          setEditingId(null);
                        }}
                        className="glass-button-primary"
                        style={{ padding: "4px 8px", fontSize: 12 }}
                      >
                        保存
                      </button>
                      <button
                        onClick={() => setEditingId(null)}
                        className="glass-button"
                        style={{ padding: "4px 8px", fontSize: 12 }}
                      >
                        取消
                      </button>
                    </div>
                  ) : (
                    <div style={{ fontSize: 14, color: isDeleted ? 'var(--color-text-muted)' : 'var(--color-text-primary)' }}>
                      {renderContent(isDeleted ? "该消息已删除" : msg.content, !!msg.expiresAt && burnRemaining[msg.id] === 0)}
                      {msg.expiresAt && burnRemaining[msg.id] !== undefined && burnRemaining[msg.id] > 0 && (
                        <span style={{ marginLeft: 8, fontSize: 11, color: 'var(--color-error)' }}>
                          🔥 {formatBurnTime(burnRemaining[msg.id])}
                        </span>
                      )}
                    </div>
                  )}
                </div>
                {reactionsByMsg[msg.id] &&
                  reactionsByMsg[msg.id].length > 0 && (
                    <div style={{ display: "flex", flexWrap: "wrap", gap: 4, marginTop: 4 }}>
                      {groupReactions(reactionsByMsg[msg.id]).map(
                        ([emoji, count]) => (
                          <button
                            key={emoji}
                            onClick={() =>
                              toggleReaction(msg.id, emoji, currentUserId)
                            }
                            className="glass-button"
                            style={{ padding: "1px 8px", fontSize: 12 }}
                          >
                            {emoji} {count}
                          </button>
                        ),
                      )}
                    </div>
                  )}
                {!isDeleted && (
                  <div style={{ display: "flex", gap: 4, marginTop: 2, alignItems: "center" }}>
                    <button
                      onClick={() => {
                        ensureReactions(msg.id);
                        setPickerFor(pickerFor === msg.id ? null : msg.id);
                      }}
                      style={{
                        border: "none",
                        background: "transparent",
                        cursor: "pointer",
                        fontSize: 12,
                        color: 'var(--color-text-muted)',
                        padding: 0,
                      }}
                      title="加表情"
                    >
                      😊
                    </button>
                    {isSelf && (
                      <>
                        <button
                          onClick={() => {
                            setEditingId(msg.id);
                            setEditContent(msg.content);
                            ensureReactions(msg.id);
                          }}
                          style={{
                            border: "none",
                            background: "transparent",
                            cursor: "pointer",
                            fontSize: 12,
                            color: 'var(--color-text-muted)',
                            padding: 0,
                          }}
                          title="编辑"
                        >
                          编辑
                        </button>
                        <button
                          onClick={() => {
                            if (window.confirm("确定删除该消息?")) {
                              onDeleteMessage?.(msg.id);
                            }
                          }}
                          style={{
                            border: "none",
                            background: "transparent",
                            cursor: "pointer",
                            fontSize: 12,
                            color: 'var(--color-error)',
                            padding: 0,
                          }}
                          title="删除"
                        >
                          删除
                        </button>
                      </>
                    )}
                    {pickerFor === msg.id && (
                      <div style={{ display: "flex", gap: 2, marginLeft: 4 }}>
                        {EMOJIS.map((emoji) => (
                          <button
                            key={emoji}
                            onClick={() => {
                              toggleReaction(msg.id, emoji, currentUserId);
                              setPickerFor(null);
                            }}
                            className="glass-button"
                            style={{ padding: "0 6px", fontSize: 14 }}
                          >
                            {emoji}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                )}
                <div style={{ fontSize: 10, color: 'var(--color-text-muted)', marginTop: 2 }}>
                  {formatTime(msg.createdAt)}
                </div>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
