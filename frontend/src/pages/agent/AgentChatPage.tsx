import { useState, useCallback } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  Box, Card, CardContent, TextField, Typography,
  CircularProgress, Chip, IconButton, Tooltip,
} from '@mui/material';
import {
  Send as SendIcon,
  AutoAwesome as MagicIcon,
} from '@mui/icons-material';
import apiClient from '@/api/client';

/** AI 工具类型 */
type AITool = 'wiki' | 'task' | 'code' | 'search' | 'general';

/** 快捷提示词 */
const QUICK_PROMPTS: Array<{ label: string; tool: AITool; prompt: string }> = [
  { label: '📄 生成文档', tool: 'wiki', prompt: '请帮我生成一份产品需求文档(PRD)，包含背景、目标、范围、详细需求' },
  { label: '📋 创建任务', tool: 'task', prompt: '请帮我创建一个新项目任务，标题为"季度复盘"，优先级为高' },
  { label: '🔍 搜索知识', tool: 'search', prompt: '请搜索知识库中关于产品架构的文档' },
  { label: '💻 生成代码', tool: 'code', prompt: '请用 TypeScript 写一个带分页查询的 React Hook' },
  { label: '💬 通用问答', tool: 'general', prompt: '请解释微服务架构中服务拆分的最佳实践' },
];

/** 统一 AI Copilot 面板 */
export function AgentChatPage() {
  const [prompt, setPrompt] = useState('');
  const [selectedTool, setSelectedTool] = useState<AITool>('general');
  const [messages, setMessages] = useState<{ role: string; content: string; tool?: AITool }[]>([
    { role: 'assistant', content: '你好，我是 AI Copilot。我可以帮你：生成文档、创建任务、搜索知识、生成代码、通用问答。', tool: 'general' },
  ]);

  const quotaQuery = useQuery({
    queryKey: ['ai-quota'],
    queryFn: () => apiClient.get<{ code: number; data: { remaining: number } }>('/ai/quota'),
    refetchInterval: 30000,
  });

  const mutation = useMutation({
    mutationFn: ({ tool, p }: { tool: AITool; p: string }) => {
      // 统一入口：走 Java /api/ai/chat，Java 再转发 Python LLM 网关
      if (tool === 'general') {
        return apiClient.post<{ code: number; data: { result: string } }>('/ai/chat', { prompt: p, model: 'gpt-4', max_tokens: 1024 });
      }
      // 专用端点
      const endpoint = tool === 'wiki' ? '/ai/wiki' : tool === 'task' ? '/ai/ask' : '/ai/code';
      return apiClient.post<{ code: number; data: { result: string } }>(endpoint, {
        prompt: p,
        ...(tool === 'wiki' ? { title: p.substring(0, 50), instruction: p } : {}),
        ...(tool === 'code' ? { instruction: p, language: 'TypeScript' } : {}),
      });
    },
    onSuccess: (resp, { tool, p }) => {
      const result = resp?.data?.result ?? '无响应';
      setMessages((prev) => [
        ...prev,
        { role: 'user', content: p, tool },
        { role: 'assistant', content: String(result), tool },
      ]);
      setPrompt('');
    },
    onError: (_, { tool, p }) => {
      setMessages((prev) => [
        ...prev,
        { role: 'user', content: p, tool },
        { role: 'assistant', content: '请求失败，可能 LLM 网关未启用或配额已用尽。', tool },
      ]);
      setPrompt('');
    },
  });

  const handleSend = useCallback(() => {
    if (!prompt.trim() || mutation.isPending) return;
    mutation.mutate({ tool: selectedTool, p: prompt.trim() });
  }, [prompt, selectedTool, mutation]);

  const handleQuickPrompt = useCallback((tool: AITool, promptText: string) => {
    setSelectedTool(tool);
    setPrompt(promptText);
  }, []);

  return (
    <Box sx={{ maxWidth: 1000, mx: 'auto', p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: 2, display: 'flex', alignItems: 'center', gap: 1 }}>
        <MagicIcon sx={{ color: 'var(--color-primary-400)' }} />
        AI Copilot 统一入口
      </Typography>

      {/* 快捷工具栏 */}
      <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: 'wrap' }}>
        {QUICK_PROMPTS.map((q) => (
          <Chip
            key={q.tool}
            label={q.label}
            size="small"
            variant={selectedTool === q.tool ? 'filled' : 'outlined'}
            onClick={() => handleQuickPrompt(q.tool, q.prompt)}
            sx={{
              cursor: 'pointer',
              bgcolor: selectedTool === q.tool ? 'var(--color-primary-500)' : undefined,
              color: selectedTool === q.tool ? 'var(--color-text-primary)' : 'var(--color-text-secondary)',
              borderColor: selectedTool === q.tool ? 'var(--color-primary-500)' : 'var(--color-border-light)',
              '&:hover': {
                bgcolor: selectedTool === q.tool ? 'var(--color-primary-600)' : 'rgba(255,255,255,0.05)',
              },
            }}
          />
        ))}
      </Box>

      {/* 对话区域 */}
      <Card variant="outlined" sx={{ borderRadius: 2, mb: 2, minHeight: 360, background: 'var(--glass-bg-medium)', backdropFilter: 'var(--glass-blur)', border: 'var(--glass-border)' }}>
        <CardContent sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, maxHeight: 400, overflowY: 'auto' }}>
          {messages.map((m, i) => (
            <Box
              key={i}
              sx={{
                alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start',
                maxWidth: '85%',
                p: 1.5,
                borderRadius: 2,
                background: m.role === 'user' ? 'var(--color-primary-500)' : 'var(--glass-bg-light)',
                color: m.role === 'user' ? 'var(--color-text-primary)' : 'var(--color-text-primary)',
                border: 'none',
              }}
            >
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
                {m.role === 'assistant' && <MagicIcon sx={{ fontSize: 16, color: 'var(--color-primary-300)' }} />}
                <Typography variant="caption" sx={{ fontWeight: 600, opacity: 0.8 }}>
                  {m.role === 'user' ? '你' : 'Copilot'}
                </Typography>
                {m.tool && (
                  <Chip size="small" label={m.tool} sx={{ bgcolor: 'rgba(255,255,255,0.15)', color: 'var(--color-text-primary)', fontSize: 10, height: 16 }} />
                )}
              </Box>
              <Typography variant="body2" sx={{ color: 'var(--color-text-primary)', whiteSpace: 'pre-wrap' }}>{m.content}</Typography>
            </Box>
          ))}
          {mutation.isPending && (
            <Box sx={{ alignSelf: 'flex-start', p: 1.5 }}>
              <CircularProgress size={20} />
            </Box>
          )}
        </CardContent>
      </Card>

      {/* 输入区域 */}
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-end' }}>
        <TextField
          fullWidth
          multiline
          maxRows={3}
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSend();
            }
          }}
          placeholder={`选择工具后输入... (${selectedTool})`}
          disabled={mutation.isPending}
          size="small"
        />
        <Tooltip title={`发送 (${selectedTool})`}>
          <IconButton
            onClick={handleSend}
            disabled={mutation.isPending || !prompt.trim()}
            sx={{
              bgcolor: 'var(--color-primary-500)',
              color: 'var(--color-text-primary)',
              '&:hover': { bgcolor: 'var(--color-primary-600)' },
              opacity: mutation.isPending || !prompt.trim() ? 0.5 : 1,
            }}
          >
            {mutation.isPending ? <CircularProgress size={20} color="inherit" /> : <SendIcon />}
          </IconButton>
        </Tooltip>
      </Box>

      {/* 配额信息 */}
      {quotaQuery.data?.data?.remaining !== undefined && (
        <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
          LLM 配额剩余: {quotaQuery.data.data.remaining} 次
        </Typography>
      )}
    </Box>
  );
}
