import { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
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

/**
 * 快捷提示词 —— PHASE118 i18n：改为存 key 而非字面量。
 *
 * 原实现在模块级常量里写死中文，而 useTranslation() 是 hook 无法在组件外调用，
 * 故改为保存 key，在组件内用 t(key) 取值。
 */
const QUICK_PROMPTS: Array<{ labelKey: string; promptKey: string; tool: AITool }> = [
  { labelKey: 'agentChat.quickDoc', promptKey: 'agentChat.quickDocPrompt', tool: 'wiki' },
  { labelKey: 'agentChat.quickTask', promptKey: 'agentChat.quickTaskPrompt', tool: 'task' },
  { labelKey: 'agentChat.quickSearch', promptKey: 'agentChat.quickSearchPrompt', tool: 'search' },
  { labelKey: 'agentChat.quickCode', promptKey: 'agentChat.quickCodePrompt', tool: 'code' },
  { labelKey: 'agentChat.quickGeneral', promptKey: 'agentChat.quickGeneralPrompt', tool: 'general' },
];

/** 统一 AI Copilot 面板 */
export function AgentChatPage() {
  const { t } = useTranslation();
  const [prompt, setPrompt] = useState('');
  const [selectedTool, setSelectedTool] = useState<AITool>('general');
  const [messages, setMessages] = useState<{ role: string; content: string; tool?: AITool }[]>([
    { role: 'assistant', content: t('agentChat.welcome'), tool: 'general' },
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
      const result = resp?.data?.result ?? t('agentChat.noResponse');
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
        { role: 'assistant', content: t('agentChat.requestFailed'), tool },
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
        {t('agentChat.title')}
      </Typography>

      {/* 快捷工具栏 */}
      <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: 'wrap' }}>
        {QUICK_PROMPTS.map((q) => (
          <Chip
            key={q.tool}
            label={t(q.labelKey)}
            size="small"
            variant={selectedTool === q.tool ? 'filled' : 'outlined'}
            onClick={() => handleQuickPrompt(q.tool, t(q.promptKey))}
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
                  {m.role === 'user' ? t('agentChat.roleUser') : t('agentChat.roleCopilot')}
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
          placeholder={t('agentChat.inputPlaceholder', { tool: selectedTool })}
          disabled={mutation.isPending}
          size="small"
        />
        <Tooltip title={t('agentChat.sendTooltip', { tool: selectedTool })}>
          <IconButton
            onClick={handleSend}
            aria-label={t('agentChat.sendAria')}
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
          {t('agentChat.quotaRemaining', { count: quotaQuery.data.data.remaining })}
        </Typography>
      )}
    </Box>
  );
}
