/**
 * Livechat 客服组件 — 内嵌工单系统对接
 *
 * 功能：
 * 1. 右下角悬浮入口气泡
 * 2. 展开为毛玻璃会话窗口
 * 3. 支持文字消息
 * 4. 会话结束自动转工单（调用后端 /api/tickets）
 */

import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { MessageCircle, X, Send, Minimize2, Maximize2 } from 'lucide-react';
import cl from 'clsx';
import apiClient from '@/api/client';

interface Message {
  id: string;
  text: string;
  sender: 'customer' | 'agent';
  timestamp: Date;
  type?: 'text' | 'image';
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
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // 初始化会话
  useEffect(() => {
    if (!enabled) return;
    initSession();
  }, [enabled]);

  const initSession = async () => {
    try {
      const resp = await apiClient.post<{ code: number; data: { sessionId: string } }>(
        '/livechat/session',
        {}
      );
      if (resp.code === 0 && resp.data?.sessionId) {
        setSessionId(resp.data.sessionId);
        setMessages([
          {
            id: 'welcome',
            text: '👋 您好！有什么可以帮到您？',
            sender: 'agent',
            timestamp: new Date(),
          },
        ]);
      }
    } catch {
      // 会话初始化失败，静默处理
    }
  };

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
    };

    setMessages((prev) => [...prev, newMessage]);
    setInputText('');
    setIsLoading(true);

    try {
      await apiClient.post('/livechat/message', {
        sessionId,
        message: inputText.trim(),
      });

      // 模拟客服回复（实际应由后端推送）
      setTimeout(() => {
        setMessages((prev) => [
          ...prev,
          {
            id: Date.now().toString(),
            text: '已收到您的消息，客服将尽快回复。',
            sender: 'agent',
            timestamp: new Date(),
          },
        ]);
      }, 1000);
    } catch {
      setMessages((prev) => prev.filter((m) => m.id !== newMessage.id));
    } finally {
      setIsLoading(false);
    }
  };

  const handleEndChat = async () => {
    if (!sessionId) return;

    try {
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
      // 结束会话失败，静默处理
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  if (!enabled) return null;

  return (
    <div className="fixed bottom-4 right-4 z-50">
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
            className="rounded-full p-4 shadow-xl hover:scale-110 active:scale-95 transition-transform"
            style={{ backgroundColor: primaryColor }}
          >
            <MessageCircle className="w-6 h-6 text-white" />
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
            className={cl(
              'glass-strong rounded-2xl shadow-2xl overflow-hidden',
              'border border-white/10',
              isMinimized ? 'w-80' : 'w-96 h-[600px]'
            )}
          >
            {/* 标题栏 */}
            <div
              className="flex items-center justify-between px-4 py-3"
              style={{ backgroundColor: primaryColor }}
            >
              <div className="flex items-center gap-2">
                <div className="w-2 h-2 bg-green-400 rounded-full animate-pulse" />
                <span className="text-white font-semibold text-sm">在线客服</span>
              </div>
              <div className="flex items-center gap-1">
                <button
                  onClick={() => setIsMinimized(!isMinimized)}
                  className="text-white/80 hover:text-white p-1 rounded hover:bg-white/10 transition"
                >
                  {isMinimized ? (
                    <Maximize2 className="w-4 h-4" />
                  ) : (
                    <Minimize2 className="w-4 h-4" />
                  )}
                </button>
                <button
                  onClick={() => setIsOpen(false)}
                  className="text-white/80 hover:text-white p-1 rounded hover:bg-white/10 transition"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>

            {/* 最小化时只显示标题 */}
            {isMinimized ? (
              <div className="p-4 text-center text-sm text-muted">
                <p className="text-white/60">点击展开聊天窗口</p>
              </div>
            ) : (
              <>
                {/* 消息列表 */}
                <div className="flex-1 overflow-y-auto p-4 space-y-3 min-h-[400px] max-h-[400px]">
                  {messages.length === 0 ? (
                    <div className="text-center text-muted py-8">
                      <p className="text-sm mb-2 text-white/80">👋 您好！有什么可以帮到您？</p>
                      <p className="text-xs text-white/50">工作时间：周一至周五 9:00-18:00</p>
                    </div>
                  ) : (
                    messages.map((msg) => (
                      <div
                        key={msg.id}
                        className={cl(
                          'flex',
                          msg.sender === 'customer' ? 'justify-end' : 'justify-start'
                        )}
                      >
                        <div
                          className={cl(
                            'max-w-[80%] rounded-2xl px-4 py-2',
                            msg.sender === 'customer'
                              ? 'bg-blue-500 text-white'
                              : 'bg-white/10 text-white'
                          )}
                        >
                          <p className="text-sm break-words">{msg.text}</p>
                          <p className="text-xs mt-1 opacity-60">
                            {msg.timestamp.toLocaleTimeString('zh-CN', {
                              hour: '2-digit',
                              minute: '2-digit',
                            })}
                          </p>
                        </div>
                      </div>
                    ))
                  )}
                  {isLoading && (
                    <div className="flex justify-start">
                      <div className="bg-white/10 rounded-2xl px-4 py-2">
                        <div className="flex gap-1">
                          <span className="w-2 h-2 bg-white rounded-full animate-bounce" />
                          <span className="w-2 h-2 bg-white rounded-full animate-bounce delay-100" />
                          <span className="w-2 h-2 bg-white rounded-full animate-bounce delay-200" />
                        </div>
                      </div>
                    </div>
                  )}
                  <div ref={messagesEndRef} />
                </div>

                {/* 输入区域 */}
                <div className="border-t border-white/10 p-3 bg-black/20">
                  <div className="flex items-center gap-2">
                    <textarea
                      value={inputText}
                      onChange={(e) => setInputText(e.target.value)}
                      onKeyPress={handleKeyPress}
                      placeholder="输入消息..."
                      className="flex-1 bg-transparent text-white placeholder-white/50 text-sm resize-none outline-none min-h-[40px] max-h-[120px]"
                      rows={1}
                      disabled={!sessionId}
                    />
                    <button
                      onClick={handleSendMessage}
                      disabled={!inputText.trim() || isLoading || !sessionId}
                      className="rounded-full p-2 disabled:opacity-50 disabled:cursor-not-allowed transition-opacity"
                      style={{ backgroundColor: primaryColor }}
                    >
                      <Send className="w-5 h-5 text-white" />
                    </button>
                  </div>
                  <div className="flex items-center justify-between mt-2">
                    <span className="text-xs text-white/40">按 Enter 发送</span>
                    <button
                      onClick={handleEndChat}
                      className="text-xs text-red-400 hover:text-red-300 transition"
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
