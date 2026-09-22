/**
 * 前端端点常量表 —— 防前后端路径漂移。
 *
 * <p>所有模块调用 apiClient 时应引用此常量表（而非散落的字符串字面量）。
 * 路径变更时必须同步更新此表 + 对应契约测试 + 后端 @RequestMapping。
 *
 * <p>注意：
 * - apiClient baseURL = '/api'，故 '/search' 实际请求 '/api/search'
 * - 部分模块（integrations.ts）已写完整 '/api/...' 前缀，保持各自写法勿统一改写
 */

export const Endpoints = {
  /** 全局搜索（Java 侧 /api/search） */
  SEARCH: '/search',

  /** AI Copilot */
  AI_CHAT: '/ai/chat',
  AI_QUOTA: '/ai/quota',
  AI_STATUS: '/ai/status',

  /** Livechat 客服转工单（Java 侧 /api/livechat/*，TicketController） */
  LIVECHAT_SESSION: '/api/livechat/session',
  LIVECHAT_MESSAGE: '/api/livechat/message',
  LIVECHAT_CLOSE: '/api/livechat/close',
  LIVECHAT_TICKETS: '/api/livechat/tickets',

  /** Wiki 知识库 */
  WIKI_KB: '/api/wiki/kb',
  WIKI_PAGES: '/api/wiki/pages',
  WIKI_SEARCH: '/api/wiki/search',
  WIKI_CATEGORIES: '/api/wiki/categories',
  /** 反向链接（路径模板） */
  wikiBacklinks: (pageId: string) => `/api/wiki/pages/${pageId}/backlinks`,

  /** 企业微信（Java 侧 /api/wecom/*，WeComController） */
  WECOM_AUTH_URL: '/api/wecom/auth-url',
  WECOM_CALLBACK: '/api/wecom/callback',
  WECOM_SYNC_CONTACTS: '/api/wecom/sync-contacts',
  WECOM_MESSAGE_SEND: '/api/wecom/message/send',

  /** IM 即时消息（Java 侧 /api/im/*） */
  IM_CHANNELS: '/im/channels',
  IM_MESSAGES: '/im/messages',
  IM_UNREAD: '/im/messages/unread',
} as const;
