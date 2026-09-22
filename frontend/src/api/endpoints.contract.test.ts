import { describe, it, expect } from 'vitest';
import { Endpoints } from './endpoints';

/**
 * 前端端点契约测试 —— 与后端 @RequestMapping 映射一致（防前后端路径漂移）。
 *
 * <p>清单来源：审计时 grep 后端 Controller 的 @RequestMapping/@GetMapping/@PostMapping
 * 实测输出（附录 A）。任一端点被前端改动路径后必须同步后端 + 此测试。
 */
describe('endpoints.ts 与后端 @RequestMapping 契约', () => {
  it('搜索端点 /search（Java /api/search）', () => {
    expect(Endpoints.SEARCH).toBe('/search');
  });

  it('AI 对话 /ai/chat（Java AiController /api/ai + POST /chat）', () => {
    expect(Endpoints.AI_CHAT).toBe('/ai/chat');
  });

  it('AI 配额 /ai/quota（Java AiController /api/ai + GET /quota）', () => {
    expect(Endpoints.AI_QUOTA).toBe('/ai/quota');
  });

  it('AI 状态 /ai/status（Java AiController /api/ai + GET /status）', () => {
    expect(Endpoints.AI_STATUS).toBe('/ai/status');
  });

  it('AI 专用端点 /ai/wiki /ai/ask /ai/code（AgentChatPage 实际调用）', () => {
    expect(Endpoints.AI_WIKI).toBe('/ai/wiki');
    expect(Endpoints.AI_ASK).toBe('/ai/ask');
    expect(Endpoints.AI_CODE).toBe('/ai/code');
  });

  it('Livechat 四端点（Java TicketController /api/livechat/*）', () => {
    expect(Endpoints.LIVECHAT_SESSION).toBe('/api/livechat/session');
    expect(Endpoints.LIVECHAT_MESSAGE).toBe('/api/livechat/message');
    expect(Endpoints.LIVECHAT_CLOSE).toBe('/api/livechat/close');
    expect(Endpoints.LIVECHAT_TICKETS).toBe('/api/livechat/tickets');
  });

  it('Wiki 端点（Java WikiController /api/wiki/*）', () => {
    expect(Endpoints.WIKI_KB).toBe('/api/wiki/kb');
    expect(Endpoints.WIKI_PAGES).toBe('/api/wiki/pages');
    expect(Endpoints.WIKI_SEARCH).toBe('/api/wiki/search');
    expect(Endpoints.WIKI_CATEGORIES).toBe('/api/wiki/categories');
  });

  it('Wiki 反向链接路径模板', () => {
    expect(Endpoints.wikiBacklinks('page-123')).toBe('/api/wiki/pages/page-123/backlinks');
    expect(Endpoints.wikiBacklinks('abc')).toBe('/api/wiki/pages/abc/backlinks');
  });

  it('企业微信端点（Java WeComController /api/wecom/*）', () => {
    expect(Endpoints.WECOM_AUTH_URL).toBe('/api/wecom/auth-url');
    expect(Endpoints.WECOM_CALLBACK).toBe('/api/wecom/callback');
    expect(Endpoints.WECOM_SYNC_CONTACTS).toBe('/api/wecom/sync-contacts');
    expect(Endpoints.WECOM_MESSAGE_SEND).toBe('/api/wecom/message/send');
  });

  it('IM 端点（Java ImChannel/MessageController /api/im/*，相对 baseURL）', () => {
    // 注意：IM 端点相对 apiClient baseURL('/api')，故常量不含 /api 前缀
    expect(Endpoints.IM_CHANNELS).toBe('/im/channels');
    expect(Endpoints.IM_MESSAGES).toBe('/im/messages');
    expect(Endpoints.IM_UNREAD).toBe('/im/messages/unread');
  });

  it('所有常量非空字符串且以 / 开头', () => {
    const values = Object.values(Endpoints).filter((v) => typeof v === 'string') as string[];
    expect(values.length).toBeGreaterThan(0);
    for (const v of values) {
      expect(v).toMatch(/^\//);
    }
  });
});
