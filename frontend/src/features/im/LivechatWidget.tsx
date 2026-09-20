/**
 * Livechat 客服组件 - Rocket.Chat Livechat 对接
 * 
 * 功能：
 * 1. 右下角悬浮入口气泡
 * 2. 展开为毛玻璃会话窗口
 * 3. 支持文字/图片消息
 * 4. 自动转工单（当会话结束时）
 */

import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { MessageCircle, X, Send, Paperclip, Minimize2, Maximize2 } from 'lucide-react';
import axios from 'axios';
import cl from 'clsx';

interface Message {
  id: string;
  text: string;
  sender: 'customer' | 'agent';
  timestamp: Date;
  type?: 'text' | 'image';
}

interface LivechatWidgetProps {
  /** Rocket.Chat Livechat URL */
  livechatUrl?: string;
  /** 部门 ID（可选） */
  departmentId?: string;
  /** 客户信息 */
  customerInfo?: {
    name: string;
    email: string;
    token: string;
  };
  /** 是否启用 */
  enabled?: boolean;
  /** 主题颜色 */
  primaryColor?: string;
  /** 回调函数 */
  onTicketCreated?: (ticketId: string) => void;
}

export const LivechatWidget: React.FC<LivechatWidgetProps> = ({
  livechatUrl = 'http://localhost:3000',
  departmentId,
  customerInfo,
  enabled = true,
  primaryColor = '#6366F1',
  onTicketCreated,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [isMinimized, setIsMinimized] = useState(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputText, setInputText] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [roomId, setRoomId] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // 初始化 Livechat
  useEffect(() => {
    if (!enabled || !customerInfo) return;

    const initLivechat = async () => {
      try {
        // 创建或获取房间
        const response = await axios.post(`${livechatUrl}/api/v1/livechat/room`, {
          ...customerInfo,
          ...(departmentId && { department: departmentId }),
        });

        setRoomId(response.data.room._id);
        
        // 加载历史消息
        const messagesResp = await axios.get(
          `${livechatUrl}/api/v1/livechat/messages.history/${response.data.room._id}`,
          {
            params: {
              limit: 50,
            },
          }
        );

        const history: Message[] = messagesResp.data.messages.map((msg: any) => ({
          id: msg._id,
          text: msg.msg || '',
          sender: msg.u.username === 'livechat-agent' ? 'agent' : 'customer',
          timestamp: new Date(msg.ts),
          type: msg.attachments?.length ? 'image' : 'text',
        }));

        setMessages(history.reverse());
      } catch (error) {
        console.error('[Livechat] 初始化失败:', error);
      }
    };

    initLivechat();
  }, [enabled, customerInfo, livechatUrl, departmentId]);

  // 自动滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSendMessage = async () => {
    if (!inputText.trim() || !roomId || !customerInfo) return;

    const newMessage: Message = {
      id: Date.now().toString(),
      text: inputText.trim(),
      sender: 'customer',
      timestamp: new Date(),
      type: 'text',
    };

    setMessages(prev => [...prev, newMessage]);
    setInputText('');
    setIsLoading(true);

    try {
      await axios.post(`${livechatUrl}/api/v1/livechat/message`, {
        ...customerInfo,
        roomId,
        message: {
          msg: inputText.trim(),
        },
      });
    } catch (error) {
      console.error('[Livechat] 发送消息失败:', error);
      setMessages(prev => prev.filter(m => m.id !== newMessage.id));
    } finally {
      setIsLoading(false);
    }
  };

  const handleEndChat = async () => {
    if (!roomId || !customerInfo) return;

    try {
      // 结束会话并创建工单
      const response = await axios.post(`${livechatUrl}/api/v1/livechat/close`, {
        ...customerInfo,
        roomId,
      });

      if (onTicketCreated && response.data.ticketId) {
        onTicketCreated(response.data.ticketId);
      }

      alert('会话已结束，已为您创建工单');
      setMessages([]);
      setRoomId(null);
    } catch (error) {
      console.error('[Livechat] 结束会话失败:', error);
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
            className="glass-button-primary rounded-full p-4 shadow-xl hover:scale-110 active:scale-95"
            style={{ backgroundColor: primaryColor }}
          >
            <MessageCircle className="w-6 h-6" />
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
              'glass-card rounded-2xl shadow-2xl overflow-hidden',
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
                点击展开聊天窗口
              </div>
            ) : (
              <>
                {/* 消息列表 */}
                <div className="flex-1 overflow-y-auto p-4 space-y-3 min-h-[400px] max-h-[400px]">
                  {messages.length === 0 ? (
                    <div className="text-center text-muted py-8">
                      <p className="text-sm mb-2">👋 您好！有什么可以帮到您？</p>
                      <p className="text-xs">工作时间：周一至周五 9:00-18:00</p>
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
                              : 'bg-gray-700 text-white'
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
                      <div className="bg-gray-700 rounded-2xl px-4 py-2">
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
                <div className="border-t border-white/10 p-3 bg-gray-800/50">
                  <div className="flex items-center gap-2">
                    <button className="text-gray-400 hover:text-white p-1 rounded hover:bg-white/10 transition">
                      <Paperclip className="w-5 h-5" />
                    </button>
                    <textarea
                      value={inputText}
                      onChange={(e) => setInputText(e.target.value)}
                      onKeyPress={handleKeyPress}
                      placeholder="输入消息..."
                      className="flex-1 bg-transparent text-white placeholder-gray-400 text-sm resize-none outline-none min-h-[40px] max-h-[120px]"
                      rows={1}
                      disabled={!roomId}
                    />
                    <button
                      onClick={handleSendMessage}
                      disabled={!inputText.trim() || isLoading || !roomId}
                      className="glass-button-primary p-2 rounded-full disabled:opacity-50 disabled:cursor-not-allowed"
                      style={{ backgroundColor: primaryColor }}
                    >
                      <Send className="w-5 h-5" />
                    </button>
                  </div>
                  <div className="flex items-center justify-between mt-2">
                    <button className="text-xs text-gray-400 hover:text-white transition">
                      📎 上传截图
                    </button>
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
