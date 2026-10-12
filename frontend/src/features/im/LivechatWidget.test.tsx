import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { LivechatWidget } from './LivechatWidget';

// jsdom 未实现 scrollIntoView，组件挂载时会调用它
beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

vi.mock('@/api/client', () => ({
  default: { post: vi.fn() },
}));

import apiClient from '@/api/client';

const postMock = apiClient.post as unknown as ReturnType<typeof vi.fn>;

describe('LivechatWidget — PHASE98 R1 假回复清零', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  /**
   * 建立会话并展开窗口。
   * 注意：isOpen 默认为 false，只渲染入口气泡；消息区与错误提示都在窗口内部，
   * 必须先点开气泡才可见。
   */
  async function openWidget() {
    postMock.mockResolvedValueOnce({ code: 0, data: { sessionId: 's-1' } });
    render(<LivechatWidget />);
    fireEvent.click(await screen.findByLabelText('打开在线客服'));
    await waitFor(() => expect(screen.getByText(/请描述您的问题/)).toBeTruthy());
  }

  async function send(text: string) {
    fireEvent.change(screen.getByLabelText('消息输入框'), { target: { value: text } });
    fireEvent.click(screen.getByLabelText('发送消息'));
  }

  it('会话初始化后展示欢迎语，且不得出现已被删除的"工作时间"装饰文案', async () => {
    await openWidget();
    expect(screen.getByText(/请描述您的问题/)).toBeTruthy();
    // 该文案与任何坐席排班无关，属误导性装饰
    expect(screen.queryByText(/工作时间/)).toBeNull();
  });

  /**
   * 核心回归：发送后不得自动伪造客服回复。
   *
   * 原实现在 POST /livechat/message 成功后 setTimeout 1 秒插入一条
   * sender:'agent' 的"已收到您的消息，客服将尽快回复。"——而后端根本没有
   * agent→customer 回推能力。若把那段 setTimeout 加回来，本断言必须变红。
   */
  it('发送消息后不得自动出现伪造的客服回复', async () => {
    await openWidget();

    postMock.mockResolvedValueOnce({ code: 0, data: {} }); // /livechat/message
    await send('你好，我需要帮助');

    await waitFor(() => expect(screen.getByText('你好，我需要帮助')).toBeTruthy());

    // 等待超过原实现的 1000ms 延迟，确认没有凭空出现"客服回复"
    await new Promise((r) => setTimeout(r, 1500));
    expect(screen.queryByText(/已收到您的消息/)).toBeNull();

    // 消息应处于真实投递状态（已送达），而非被伪造回复顶替
    await waitFor(() => expect(screen.getByText(/已送达/)).toBeTruthy());
  });

  it('消息发送失败时对用户可见，且提供重试入口（不再静默丢弃）', async () => {
    await openWidget();

    postMock.mockRejectedValueOnce(new Error('network down')); // /livechat/message
    await send('发送失败的消息');

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByText(/消息发送失败/)).toBeTruthy();
    expect(screen.getByLabelText('重发该消息')).toBeTruthy();
  });

  it('会话初始化失败时显示错误，而不是静默无会话', async () => {
    postMock.mockRejectedValueOnce(new Error('init failed')); // /livechat/session
    render(<LivechatWidget />);
    fireEvent.click(await screen.findByLabelText('打开在线客服'));

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByText(/客服会话初始化失败/)).toBeTruthy();
  });

  it('四个图标按钮均有可访问名（PHASE98 R4）', async () => {
    await openWidget();
    expect(screen.getByLabelText('打开在线客服')).toBeTruthy();
    expect(screen.getByLabelText('发送消息')).toBeTruthy();
    expect(screen.getByLabelText('关闭聊天窗口')).toBeTruthy();
    expect(screen.getByLabelText('最小化聊天窗口')).toBeTruthy();
  });
});
