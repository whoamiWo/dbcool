/**
 * Livechat 客服组件 — 内嵌工单系统对接
 *
 * 功能：
 * 1. 右下角悬浮入口气泡
 * 2. 展开为毛玻璃会话窗口
 * 3. 支持文字消息
 * 4. 会话结束自动转工单（调用后端 /api/livechat/close）
 *
 * PHASE98 R1 变更说明：
 * - 删除了原 `:101-112` 的 `setTimeout` 假回复：它会在 1 秒后固定插入一条
 *   `sender:'agent'` 的"客服回复"，而后端根本没有 agent→customer 回推能力
 *   （见 TicketController：只有 session/message/close/tickets/notes，消息只入不出）。
 *   客户发 N 条就收到 N 条一模一样的"客服"回复，属欺骗性体验。
 * - 消息改为真实投递状态（sending → sent / failed），失败可见且可重试。
 * - 两处 `catch { 静默处理 }` 改为渲染错误提示。
 * - 欢迎语由 `sender:'agent'` 改为 `sender:'bot'`（不是真人客服，不得冒充），
 *   并删除"工作时间 9:00-18:00"——那是纯装饰文案，与任何坐席排班无关。
 * - **样式重写**：原文件全篇使用 Tailwind 原子类（fixed/w-96/h-[600px]/flex-1 等），
 *   但本项目未安装 Tailwind（package.json 无 tailwindcss/postcss/autoprefixer），
 *   这些类名运行时全部失效。现统一改为内联 style（仅保留项目自定义的 `.glass-strong`）。
 * - PHASE98 R4：补齐 4 个图标按钮的 aria-label（入口气泡/最小化/关闭/发送）。
 */

import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { MessageCircle, X, Send, Minimize2, Maximize2 } from 'lucide-react';
import apiClient from '@/api/client';

interface Message {
  id: string;
  text: string;
  /** customer=客户；agent=真人客服（仅后端回推时使用）；bot=自动应答（非人工） */
  sender: 'customer' | 'agent' | 'bot';
  timestamp: Date;
  type?: 'text' | 'image';
  /** 投递状态：sending=发送中、sent=后端已接收、failed=发送失败（可重试） */
  delivery?: 'sending' | 'sent' | 'failed';
}

interface LivechatWidgetProps {
  /** 是否启用 */
  enabled?: boolean;
  /** 主题颜色 */
  primaryColor?: string;
  /** 工单创建回调 */
  onTicketCreated?: (ticketId: string) => void;
}

export const LivechatWidget: React.FC<LivechatWidgetProps> = ({
  enabled = true,
  primaryColor = 'var(--color-primary-500)',
  onTicketCreated,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [isMinimized, setIsMinimized] = useState(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputText, setInputText] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [sessionId, setSessionId] = useState<string | null>(null);
  /** PHASE98 R1: 错误不再静默吞掉，必须对用户可见 */
  const [error, setError] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const initSession = async () => {
    try {
      setError(null);
      const resp = await apiClient.post<{ code: number; data: { sessionId: string } }>(
        '/livechat/session',
        {}
      );
      if (resp.code === 0 && resp.data?.sessionId) {
        setSessionId(resp.data.sessionId);
        setMessages([
          {
            id: 'welcome',
            text: '👋 您好！请描述您的问题，我们会转达给客服团队。',
            // PHASE98 R1: 欢迎语不是真人客服发的，必须标为 bot
            sender: 'bot',
            timestamp: new Date(),
          },
        ]);
      }
    } catch {
      // PHASE98 R1: 原来是 `// 会话初始化失败，静默处理` —— 用户零感知
      setError('客服会话初始化失败，请检查网络后重试');
    }
  };

  // 初始化会话
   
  useEffect(() => {
    if (!enabled) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 安全：异步/条件/初始化模式，非连续渲染
    initSession();
  }, [enabled]);

  // 自动滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSendMessage = async () => {
    if (!inputText.trim() || !sessionId) return;

    const newMessage: Message = {
      id: Date.now().toString(),
      text: inputText.trim(),
      sender: 'customer',
      timestamp: new Date(),
      type: 'text',
      delivery: 'sending',
    };

    setMessages((prev) => [...prev, newMessage]);
    setInputText('');
    setIsLoading(true);
    setError(null);

    try {
      await apiClient.post('/livechat/message', {
        sessionId,
        message: newMessage.text,
      });
      // PHASE98 R1: 真实投递状态。后端确认接收后标记 sent，
      // 绝不再伪造 `sender:'agent'` 的客服回复。
      setMessages((prev) =>
        prev.map((m) => (m.id === newMessage.id ? { ...m, delivery: 'sent' as const } : m))
      );
    } catch {
      setMessages((prev) =>
        prev.map((m) => (m.id === newMessage.id ? { ...m, delivery: 'failed' as const } : m))
      );
      setError('消息发送失败，请重试');
    } finally {
      setIsLoading(false);
    }
  };

  /** 重发一条失败的消息 */
  const handleRetry = (msg: Message) => {
    setInputText(msg.text);
    setMessages((prev) => prev.filter((m) => m.id !== msg.id));
    setError(null);
  };

  const handleEndChat = async () => {
    if (!sessionId) return;

    try {
      setError(null);
      const resp = await apiClient.post<{ code: number; data: { ticketId: string } }>(
        '/livechat/close',
        { sessionId, message: '会话结束，自动转工单' }
      );

      if (resp.code === 0 && resp.data?.ticketId) {
        onTicketCreated?.(resp.data.ticketId);
      }

      setMessages([]);
      setSessionId(null);
      setIsOpen(false);
    } catch {
      // PHASE98 R1: 原来是 `// 结束会话失败，静默处理` —— 工单其实没建成，用户却以为成功了
      setError('结束会话失败（工单可能未创建），请重试');
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  if (!enabled) return null;

  const bubbleBase: React.CSSProperties = {
    maxWidth: '80%',
    borderRadius: 16,
    padding: '8px 16px',
  };

  return (
    <div style={{ position: 'fixed', bottom: 16, right: 16, zIndex: 50 }}>
      <AnimatePresence>
        {!isOpen ? (
          // 入口气泡
          <motion.button
            key="entry"
            initial={{ scale: 0, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0, opacity: 0 }}
            transition={{ type: 'spring', damping: 15 }}
            onClick={() => setIsOpen(true)}
            aria-label="打开在线客服"
            style={{
              borderRadius: '50%',
              padding: 16,
              boxShadow: '0 10px 25px rgba(0,0,0,0.3)',
              backgroundColor: primaryColor,
              cursor: 'pointer',
              border: 'none',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <MessageCircle size={24} color="#fff" />
          </motion.button>
        ) : (
          // 聊天窗口
          <motion.div
            key="window"
            initial={{ y: 100, opacity: 0, scale: 0.9 }}
            animate={{
              y: 0,
              opacity: 1,
              scale: isMinimized ? 0.95 : 1,
            }}
            exit={{ y: 100, opacity: 0, scale: 0.9 }}
            transition={{ type: 'spring', damping: 20 }}
            className="glass-strong"
            style={{
              borderRadius: 16,
              boxShadow: '0 25px 50px rgba(0,0,0,0.4)',
              overflow: 'hidden',
              border: '1px solid rgba(255,255,255,0.1)',
              width: isMinimized ? 320 : 384,
              height: isMinimized ? undefined : 600,
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            {/* 标题栏 */}
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '12px 16px',
                backgroundColor: primaryColor,
                flexShrink: 0,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <div
                  style={{
                    width: 8,
                    height: 8,
                    borderRadius: '50%',
                    backgroundColor: '#4ade80',
                  }}
                />
                <span style={{ color: '#fff', fontWeight: 600, fontSize: 14 }}>在线客服</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <button
                  onClick={() => setIsMinimized(!isMinimized)}
                  aria-label={isMinimized ? '展开聊天窗口' : '最小化聊天窗口'}
                  style={{
                    color: 'rgba(255,255,255,0.8)',
                    background: 'transparent',
                    border: 'none',
                    padding: 4,
                    borderRadius: 4,
                    cursor: 'pointer',
                    display: 'flex',
                  }}
                >
                  {isMinimized ? <Maximize2 size={16} /> : <Minimize2 size={16} />}
                </button>
                <button
                  onClick={() => setIsOpen(false)}
                  aria-label="关闭聊天窗口"
                  style={{
                    color: 'rgba(255,255,255,0.8)',
                    background: 'transparent',
                    border: 'none',
                    padding: 4,
                    borderRadius: 4,
                    cursor: 'pointer',
                    display: 'flex',
                  }}
                >
                  <X size={16} />
                </button>
              </div>
            </div>

            {/* PHASE98 R1: 错误提示区（原来错误被静默吞掉） */}
            {error && (
              <div
                role="alert"
                style={{
                  padding: '8px 16px',
                  backgroundColor: 'rgba(239,68,68,0.15)',
                  color: '#fca5a5',
                  fontSize: 13,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 8,
                  flexShrink: 0,
                }}
              >
                <span>{error}</span>
                <button
                  onClick={() => setError(null)}
                  aria-label="关闭错误提示"
                  style={{
                    background: 'transparent',
                    border: 'none',
                    color: '#fca5a5',
                    cursor: 'pointer',
                    fontSize: 14,
                    lineHeight: 1,
                  }}
                >
                  ×
                </button>
              </div>
            )}

            {/* 最小化时只显示标题 */}
            {isMinimized ? (
              <div style={{ padding: 16, textAlign: 'center', fontSize: 14 }}>
                <span style={{ color: 'rgba(255,255,255,0.6)' }}>点击展开聊天窗口</span>
              </div>
            ) : (
              <>
                {/* 消息列表 */}
                <div
                  style={{
                    flex: 1,
                    overflowY: 'auto',
                    padding: 16,
                    minHeight: 400,
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 12,
                  }}
                >
                  {messages.length === 0 ? (
                    <div style={{ textAlign: 'center', padding: '32px 0' }}>
                      <p style={{ fontSize: 14, marginBottom: 8, color: 'rgba(255,255,255,0.8)' }}>
                        👋 您好！请描述您的问题，我们会转达给客服团队。
                      </p>
                    </div>
                  ) : (
                    messages.map((msg) => (
                      <div
                        key={msg.id}
                        style={{
                          display: 'flex',
                          justifyContent: msg.sender === 'customer' ? 'flex-end' : 'flex-start',
                        }}
                      >
                        <div
                          style={{
                            ...bubbleBase,
                            backgroundColor:
                              msg.sender === 'customer' ? '#3b82f6' : 'rgba(255,255,255,0.1)',
                            color: msg.sender === 'customer' ? '#fff' : '#fff',
                            opacity: msg.delivery === 'failed' ? 0.6 : 1,
                          }}
                        >
                          <p style={{ fontSize: 14, wordBreak: 'break-word', margin: 0 }}>
                            {msg.text}
                          </p>
                          <p
                            style={{
                              fontSize: 12,
                              marginTop: 4,
                              opacity: 0.6,
                              display: 'flex',
                              alignItems: 'center',
                              gap: 6,
                            }}
                          >
                            <span>
                              {msg.timestamp.toLocaleTimeString('zh-CN', {
                                hour: '2-digit',
                                minute: '2-digit',
                              })}
                            </span>
                            {/* PHASE98 R1: 真实投递状态，不再伪造客服回复 */}
                            {msg.sender === 'customer' && msg.delivery === 'sending' && (
                              <span>发送中…</span>
                            )}
                            {msg.sender === 'customer' && msg.delivery === 'sent' && (
                              <span>· 已送达</span>
                            )}
                            {msg.sender === 'customer' && msg.delivery === 'failed' && (
                              <button
                                onClick={() => handleRetry(msg)}
                                aria-label="重发该消息"
                                style={{
                                  background: 'transparent',
                                  border: 'none',
                                  color: '#fca5a5',
                                  cursor: 'pointer',
                                  fontSize: 12,
                                  padding: 0,
                                  textDecoration: 'underline',
                                }}
                              >
                                · 发送失败，点击重试
                              </button>
                            )}
                          </p>
                        </div>
                      </div>
                    ))
                  )}
                  {isLoading && (
                    <div style={{ display: 'flex', justifyContent: 'flex-start' }}>
                      <div
                        style={{
                          ...bubbleBase,
                          backgroundColor: 'rgba(255,255,255,0.1)',
                          display: 'flex',
                          gap: 4,
                        }}
                      >
                        <span
                          style={{
                            width: 6,
                            height: 6,
                            borderRadius: '50%',
                            backgroundColor: '#fff',
                          }}
                        />
                        <span
                          style={{
                            width: 6,
                            height: 6,
                            borderRadius: '50%',
                            backgroundColor: 'rgba(255,255,255,0.6)',
                          }}
                        />
                        <span
                          style={{
                            width: 6,
                            height: 6,
                            borderRadius: '50%',
                            backgroundColor: 'rgba(255,255,255,0.3)',
                          }}
                        />
                      </div>
                    </div>
                  )}
                  <div ref={messagesEndRef} />
                </div>

                {/* 输入区域 */}
                <div
                  style={{
                    borderTop: '1px solid rgba(255,255,255,0.1)',
                    padding: 12,
                    backgroundColor: 'rgba(0,0,0,0.2)',
                    flexShrink: 0,
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <textarea
                      value={inputText}
                      onChange={(e) => setInputText(e.target.value)}
                      onKeyPress={handleKeyPress}
                      placeholder="输入消息..."
                      aria-label="消息输入框"
                      style={{
                        flex: 1,
                        background: 'transparent',
                        color: '#fff',
                        fontSize: 14,
                        resize: 'none',
                        outline: 'none',
                        minHeight: 40,
                        maxHeight: 120,
                        border: 'none',
                      }}
                      rows={1}
                      disabled={!sessionId}
                    />
                    <button
                      onClick={handleSendMessage}
                      disabled={!inputText.trim() || isLoading || !sessionId}
                      aria-label="发送消息"
                      style={{
                        borderRadius: '50%',
                        padding: 8,
                        backgroundColor: primaryColor,
                        border: 'none',
                        cursor:
                          !inputText.trim() || isLoading || !sessionId
                            ? 'not-allowed'
                            : 'pointer',
                        opacity: !inputText.trim() || isLoading || !sessionId ? 0.5 : 1,
                        display: 'flex',
                      }}
                    >
                      <Send size={20} color="#fff" />
                    </button>
                  </div>
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      marginTop: 8,
                    }}
                  >
                    <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.4)' }}>
                      按 Enter 发送
                    </span>
                    <button
                      onClick={handleEndChat}
                      style={{
                        fontSize: 12,
                        color: '#f87171',
                        background: 'transparent',
                        border: 'none',
                        cursor: 'pointer',
                      }}
                    >
                      结束会话
                    </button>
                  </div>
                </div>
              </>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
};

export default LivechatWidget;
