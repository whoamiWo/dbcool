import { useState, useRef, useEffect } from 'react';
import { useComments } from './useComments';

interface CommentsPanelProps {
  collectionName: string;
  recordId: string;
}

export function CommentsPanel({ collectionName, recordId }: CommentsPanelProps) {
  const { comments, isLoading, createComment, editComment, removeComment, isCreating, isDeleting } =
    useComments({ collectionName, recordId });
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editContent, setEditContent] = useState('');
  const [draft, setDraft] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // 自动调整文本域高度
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 120)}px`;
    }
  }, [draft]);

  const formatTime = (iso: string) => {
    const d = new Date(iso);
    return d.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const handleSubmit = async () => {
    if (!draft.trim()) return;
    try {
      await createComment({
        content: draft.trim(),
        contentType: 'TEXT',
      });
      setDraft('');
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
    } catch (err) {
      console.error('[Comments] 创建失败:', err);
    }
  };

  const handleEditStart = (c: any) => {
    setEditingId(c.id);
    setEditContent(c.content);
  };

  const handleEditSave = async (commentId: string) => {
    if (!editContent.trim()) return;
    try {
      await editComment(commentId, {
        content: editContent.trim(),
        contentType: 'TEXT',
      });
      setEditingId(null);
      setEditContent('');
    } catch (err) {
      console.error('[Comments] 编辑失败:', err);
    }
  };

  const handleDelete = async (commentId: string) => {
    try {
      await removeComment(commentId);
    } catch (err) {
      console.error('[Comments] 删除失败:', err);
    }
  };

  const renderComment = (c: any, depth = 0) => {
    if (c.deletedAt) {
      return (
        <div key={c.id} style={{ marginLeft: depth * 24, padding: '8px 12px', background: 'rgba(255,255,255,0.03)', borderRadius: 8, marginBottom: 8 }}>
          <div style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>
            该评论已被删除 · {formatTime(c.createdAt)}
          </div>
        </div>
      );
    }

    const isEditing = editingId === c.id;

    return (
      <div key={c.id} style={{ marginLeft: depth * 24, marginBottom: 12 }}>
        <div
          className="glass-card"
          style={{
            padding: 12,
            borderRadius: 8,
            background: 'rgba(255,255,255,0.05)',
            backdropFilter: 'blur(10px)',
            border: '1px solid rgba(255,255,255,0.08)',
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
            <span style={{ fontSize: 12, fontWeight: 500, color: 'var(--color-text-primary)' }}>
              {c.authorName || '匿名用户'}
            </span>
            <div style={{ display: 'flex', gap: 8 }}>
              <span style={{ fontSize: 11, color: 'var(--color-text-muted)' }}>{formatTime(c.createdAt)}</span>
              {!isEditing && (
                <>
                  <button
                    onClick={() => handleEditStart(c)}
                    disabled={isEditing || isEditing}
                    style={{
                      border: 'none',
                      background: 'transparent',
                      cursor: 'pointer',
                      fontSize: 11,
                      color: 'var(--color-text-muted)',
                      padding: '2px 6px',
                      borderRadius: 4,
                    }}
                    onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.color = 'var(--color-text-primary)'; }}
                    onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.color = 'var(--color-text-muted)'; }}
                  >
                    编辑
                  </button>
                  <button
                    onClick={() => handleDelete(c.id)}
                    disabled={isDeleting}
                    style={{
                      border: 'none',
                      background: 'transparent',
                      cursor: 'pointer',
                      fontSize: 11,
                      color: '#ff6b6b',
                      padding: '2px 6px',
                      borderRadius: 4,
                    }}
                    onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.color = '#ff4757'; }}
                    onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.color = '#ff6b6b'; }}
                  >
                    删除
                  </button>
                </>
              )}
            </div>
          </div>

          {isEditing ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <textarea
                value={editContent}
                onChange={(e) => setEditContent(e.target.value)}
                rows={3}
                style={{
                  width: '100%',
                  padding: 8,
                  borderRadius: 6,
                  border: '1px solid rgba(255,255,255,0.1)',
                  background: 'rgba(255,255,255,0.05)',
                  color: 'var(--color-text-primary)',
                  fontSize: 13,
                  fontFamily: 'inherit',
                  resize: 'vertical',
                  boxSizing: 'border-box',
                }}
              />
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                <button
                  onClick={() => setEditingId(null)}
                  style={{
                    border: 'none',
                    background: 'rgba(255,255,255,0.1)',
                    cursor: 'pointer',
                    fontSize: 12,
                    color: 'var(--color-text-primary)',
                    padding: '6px 12px',
                    borderRadius: 6,
                  }}
                >
                  取消
                </button>
                <button
                  onClick={() => handleEditSave(c.id)}
                  disabled={!editContent.trim() || isEditing}
                  style={{
                    border: 'none',
                    background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
                    cursor: 'pointer',
                    fontSize: 12,
                    color: '#fff',
                    padding: '6px 12px',
                    borderRadius: 6,
                    opacity: !editContent.trim() || isEditing ? 0.5 : 1,
                  }}
                >
                  保存
                </button>
              </div>
            </div>
          ) : (
            <div style={{ fontSize: 13, color: 'var(--color-text-primary)', lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>
              {c.content}
            </div>
          )}
        </div>

        {/* 子回复 */}
        {c.replies && c.replies.length > 0 && (
          <div style={{ marginTop: 8 }}>
            {c.replies.map((r: any) => renderComment(r, depth + 1))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div
      style={{
        background: 'var(--color-bg-secondary)',
        backdropFilter: 'blur(20px)',
        borderRadius: 12,
        border: '1px solid rgba(255,255,255,0.08)',
        padding: 16,
        marginTop: 24,
      }}
    >
      <h3 style={{ fontSize: 16, fontWeight: 600, color: 'var(--color-text-primary)', margin: '0 0 16px 0' }}>
        评论
      </h3>

      {/* 输入框 */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 16 }}>
        <textarea
          ref={textareaRef}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="写下你的评论..."
          rows={2}
          style={{
            width: '100%',
            padding: 12,
            borderRadius: 8,
            border: '1px solid rgba(255,255,255,0.1)',
            background: 'rgba(255,255,255,0.05)',
            color: 'var(--color-text-primary)',
            fontSize: 13,
            fontFamily: 'inherit',
            resize: 'vertical',
            boxSizing: 'border-box',
            outline: 'none',
            transition: 'border-color 0.2s',
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSubmit();
            }
          }}
        />
        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <button
            onClick={handleSubmit}
            disabled={!draft.trim() || isCreating}
            style={{
              border: 'none',
              background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
              cursor: 'pointer',
              fontSize: 13,
              fontWeight: 500,
              color: '#fff',
              padding: '8px 20px',
              borderRadius: 8,
              opacity: !draft.trim() || isCreating ? 0.5 : 1,
              boxShadow: '0 4px 12px rgba(102,126,234,0.3)',
              transition: 'transform 0.15s, box-shadow 0.15s',
            }}
            onMouseEnter={(e) => {
              (e.currentTarget as HTMLButtonElement).style.transform = 'translateY(-1px)';
              (e.currentTarget as HTMLButtonElement).style.boxShadow = '0 6px 16px rgba(102,126,234,0.4)';
            }}
            onMouseLeave={(e) => {
              (e.currentTarget as HTMLButtonElement).style.transform = 'translateY(0)';
              (e.currentTarget as HTMLButtonElement).style.boxShadow = '0 4px 12px rgba(102,126,234,0.3)';
            }}
          >
            {isCreating ? '发送中...' : '发表评论'}
          </button>
        </div>
      </div>

      {/* 评论列表 */}
      {isLoading ? (
        <div style={{ textAlign: 'center', padding: 24, color: 'var(--color-text-muted)', fontSize: 13 }}>
          加载中...
        </div>
      ) : comments.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 24, color: 'var(--color-text-muted)', fontSize: 13 }}>
          暂无评论，快来抢沙发吧～
        </div>
      ) : (
        <div>
          {comments.map((c) => renderComment(c))}
        </div>
      )}
    </div>
  );
}
