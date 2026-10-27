/**
 * PHASE 4.2 覆盖率补测：pages/agent/AgentChatPage.tsx
 * 覆盖：配额加载、消息渲染、发送（general/专用端点）、快捷提示切 tool、失败回退。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AgentChatPage } from './AgentChatPage';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const mockedGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const mockedPost = apiClient.post as unknown as ReturnType<typeof vi.fn>;

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <AgentChatPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGet.mockImplementation((url: string) => {
    if (url === '/ai/status') {
      return Promise.resolve({ code: 0, data: { enabled: true, model: 'gpt-4o', models: ['gpt-4o', 'qwen-max'] } });
    }
    return Promise.resolve({ code: 0, data: { remaining: 42 } });
  });
  mockedPost.mockResolvedValue({ code: 0, data: { result: 'AI 回答内容' } });
});

describe('AgentChatPage', () => {
  it('渲染标题与配额', async () => {
    renderPage();
    expect(screen.getByText('agentChat.title')).toBeInTheDocument();
    await waitFor(() => expect(mockedGet).toHaveBeenCalledWith('/ai/quota'));
    await waitFor(() => expect(screen.getByText('agentChat.quotaRemaining')).toBeInTheDocument());
  });

  it('初始渲染欢迎消息', () => {
    renderPage();
    expect(screen.getByText('agentChat.welcome')).toBeInTheDocument();
  });

  it('空白 prompt 时发送按钮不触发请求', () => {
    renderPage();
    fireEvent.click(screen.getByLabelText('agentChat.sendAria'));
    expect(mockedPost).not.toHaveBeenCalled();
  });

  it('general tool 发送走 /ai/chat 并渲染回复', async () => {
    renderPage();
    const input = screen.getByPlaceholderText('agentChat.inputPlaceholder');
    fireEvent.change(input, { target: { value: '你好' } });
    fireEvent.click(screen.getByLabelText('agentChat.sendAria'));

    await waitFor(() =>
      expect(mockedPost).toHaveBeenCalledWith(
        '/ai/chat',
        expect.objectContaining({ prompt: '你好', model: 'gpt-4o', max_tokens: 1024 }),
      ),
    );
    await waitFor(() => expect(screen.getByText('AI 回答内容')).toBeInTheDocument());
  });

  it('status 返回多个模型时渲染模型选择器', async () => {
    renderPage();
    await waitFor(() => expect(mockedGet).toHaveBeenCalledWith('/ai/status'));
    await waitFor(() => expect(screen.getByLabelText('agentChat.modelSelectorAria')).toBeInTheDocument());
  });

  it('wiki tool 发送走 /ai/wiki 专用端点', async () => {
    renderPage();
    // 点击快捷提示切换到 wiki tool
    fireEvent.click(screen.getByText('agentChat.quickDoc'));
    const input = screen.getByPlaceholderText('agentChat.inputPlaceholder');
    fireEvent.change(input, { target: { value: '写文档' } });
    fireEvent.click(screen.getByLabelText('agentChat.sendAria'));

    await waitFor(() =>
      expect(mockedPost).toHaveBeenCalledWith(
        '/ai/wiki',
        expect.objectContaining({ prompt: '写文档', title: '写文档' }),
      ),
    );
  });

  it('code tool 发送走 /ai/code 并带 language', async () => {
    renderPage();
    fireEvent.click(screen.getByText('agentChat.quickCode'));
    const input = screen.getByPlaceholderText('agentChat.inputPlaceholder');
    fireEvent.change(input, { target: { value: '写代码' } });
    fireEvent.click(screen.getByLabelText('agentChat.sendAria'));

    await waitFor(() =>
      expect(mockedPost).toHaveBeenCalledWith(
        '/ai/code',
        expect.objectContaining({ instruction: '写代码', language: 'TypeScript' }),
      ),
    );
  });

  it('task tool 发送走 /ai/ask', async () => {
    renderPage();
    fireEvent.click(screen.getByText('agentChat.quickTask'));
    const input = screen.getByPlaceholderText('agentChat.inputPlaceholder');
    fireEvent.change(input, { target: { value: '建任务' } });
    fireEvent.click(screen.getByLabelText('agentChat.sendAria'));

    await waitFor(() =>
      expect(mockedPost).toHaveBeenCalledWith('/ai/ask', expect.objectContaining({ prompt: '建任务' })),
    );
  });

  it('请求失败时渲染失败提示', async () => {
    mockedPost.mockRejectedValue(new Error('boom'));
    renderPage();
    const input = screen.getByPlaceholderText('agentChat.inputPlaceholder');
    fireEvent.change(input, { target: { value: '会失败' } });
    fireEvent.click(screen.getByLabelText('agentChat.sendAria'));

    await waitFor(() => expect(mockedPost).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText('agentChat.requestFailed')).toBeInTheDocument());
  });

  it('响应无 result 时回退为 noResponse', async () => {
    mockedPost.mockResolvedValue({ code: 0, data: {} });
    renderPage();
    const input = screen.getByPlaceholderText('agentChat.inputPlaceholder');
    fireEvent.change(input, { target: { value: '空响应' } });
    fireEvent.click(screen.getByLabelText('agentChat.sendAria'));

    await waitFor(() => expect(screen.getByText('agentChat.noResponse')).toBeInTheDocument());
  });

  it('渲染 5 个快捷提示按钮', () => {
    renderPage();
    ['quickDoc', 'quickTask', 'quickSearch', 'quickCode', 'quickGeneral'].forEach((k) => {
      expect(screen.getByText(`agentChat.${k}`)).toBeInTheDocument();
    });
  });
});
