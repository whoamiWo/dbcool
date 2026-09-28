import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiAssistantPanel } from './AiAssistantPanel';

vi.mock('@/api/wiki', () => ({
  wikiApi: {
    askQuestion: vi.fn(),
    generateOutline: vi.fn(),
  },
}));

describe('AiAssistantPanel', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    vi.clearAllMocks();
  });

  const renderPanel = () =>
    render(
      <QueryClientProvider client={qc}>
        <AiAssistantPanel
          pageId={1}
          pageTitle="测试页面"
          pageContent="# Hello\n这是测试内容"
        />
      </QueryClientProvider>,
    );

  it('展开后显示 Tab 区域', async () => {
    renderPanel();
    
    // 点击展开按钮（使用 aria-label）
    const expandBtn = await screen.findByRole('button', { name: '展开' });
    fireEvent.click(expandBtn);
    
    // 应该显示 Tab 区域
    expect(screen.getByText('大纲')).toBeInTheDocument();
    expect(screen.getByText('问答')).toBeInTheDocument();
    expect(screen.getByText('润色')).toBeInTheDocument();
  });

  it('问答 Tab:输入问题并提问', async () => {
    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => ({ answer: '这是 AI 的回答' }),
    });

    renderPanel();
    
    // 展开面板
    const expandBtn = await screen.findByRole('button', { name: '展开' });
    fireEvent.click(expandBtn);
    
    // 输入问题并提交
    const questionInput = await screen.findByPlaceholderText('输入您的问题...');
    fireEvent.change(questionInput, { target: { value: '什么是 RAG?' } });
    const askBtn = screen.getByRole('button', { name: '提问' });
    fireEvent.click(askBtn);
    
    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith('/api/wiki/pages/1/ask', expect.any(Object));
    });
    
    expect(await screen.findByText('这是 AI 的回答')).toBeInTheDocument();
  });

  it('大纲 Tab:点击生成大纲按钮', async () => {
    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => ({ outline: '# 大纲\n## 第一部分' }),
    });

    renderPanel();
    
    // 展开并切换到大纲 Tab
    const expandBtn = await screen.findByRole('button', { name: '展开' });
    fireEvent.click(expandBtn);
    fireEvent.click(screen.getByText('大纲'));
    
    // 点击生成按钮
    const generateBtn = screen.getByRole('button', { name: '生成大纲' });
    fireEvent.click(generateBtn);
    
    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith('/api/wiki/pages/1/generate-outline', expect.any(Object));
    });
  });

  it('润色 Tab:输入文本并润色', async () => {
    global.fetch = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => ({ polishedText: '经过润色的更好表达' }),
    });

    renderPanel();
    
    // 展开并切换到润色 Tab
    const expandBtn = await screen.findByRole('button', { name: '展开' });
    fireEvent.click(expandBtn);
    fireEvent.click(screen.getByText('润色'));
    
    // 输入文本并润色
    const textInput = await screen.findByPlaceholderText('输入要润色的文字...');
    fireEvent.change(textInput, { target: { value: '这个句子不太好' } });
    const polishBtn = screen.getByRole('button', { name: '润色' });
    fireEvent.click(polishBtn);
    
    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith('/api/wiki/ai/polish', expect.any(Object));
    });
    
    expect(await screen.findByText('经过润色的更好表达')).toBeInTheDocument();
  });

  it('网络错误时:显示错误提示', async () => {
    global.fetch = vi.fn().mockRejectedValueOnce(new Error('问答失败'));

    renderPanel();
    
    // 展开并切换到问答 Tab
    const expandBtn = await screen.findByRole('button', { name: '展开' });
    fireEvent.click(expandBtn);
    fireEvent.click(screen.getByText('问答'));
    
    // 输入问题并提交
    const questionInput = await screen.findByPlaceholderText('输入您的问题...');
    fireEvent.change(questionInput, { target: { value: '测试问题' } });
    const askBtn = screen.getByRole('button', { name: '提问' });
    fireEvent.click(askBtn);
    
    expect(await screen.findByText('问答失败')).toBeInTheDocument();
  });
});