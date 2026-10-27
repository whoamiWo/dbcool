import React, { useState, useCallback, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  Box, Card, CardContent, TextField, Typography,
  CircularProgress, Chip, IconButton, Tooltip,
} from '@mui/material';
import {
  Send as SendIcon,
  AutoAwesome as MagicIcon,
  Settings as SettingsIcon,
} from '@mui/icons-material';
import apiClient from '@/api/client';
import Select, { SelectChangeEvent } from '@mui/material/Select';
import MenuItem from '@mui/material/MenuItem';

/** 移动端断点检测 hook（<=768px 视为移动端） */
function useIsMobile(breakpoint = 768): boolean {
  const [isMobile, setIsMobile] = useState<boolean>(
    typeof window !== 'undefined' ? window.innerWidth <= breakpoint : false
  );
  useEffect(() => {
    const handler = () => setIsMobile(window.innerWidth <= breakpoint);
    window.addEventListener('resize', handler);
    return () => window.removeEventListener('resize', handler);
  }, [breakpoint]);
  return isMobile;
}

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
  const [selectedModel, setSelectedModel] = useState<string | null>(null);
  const isMobile = useIsMobile();

  const statusQuery = useQuery({
    queryKey: ['ai-status'],
    queryFn: () => apiClient.get<{ code: number; data: { enabled: boolean; model: string; models: string[] } }>('/ai/status'),
    staleTime: 60000,
  });

  const quotaQuery = useQuery({
    queryKey: ['ai-quota'],
    queryFn: () => apiClient.get<{ code: number; data: { remaining: number } }>('/ai/quota'),
    refetchInterval: 30000,
  });

  // 首次从 status 加载默认模型
  const firstRef = React.useRef(false);
  React.useEffect(() => {
    if (!firstRef.current && statusQuery.data?.data) {
      firstRef.current = true;
      setSelectedModel(statusQuery.data.data.model ?? statusQuery.data.data.models?.[0] ?? null);
    }
  }, [statusQuery.data]);

  const mutation = useMutation({
    mutationFn: ({ tool, p }: { tool: AITool; p: string }) => {
      // 统一入口：走 Java /api/ai/chat，Java 再转发 Python LLM 网关
      if (tool === 'general') {
        return apiClient.post<{ code: number; data: { result: string } }>('/ai/chat', { 
          prompt: p, 
          model: selectedModel ?? 'gpt-4o', 
          max_tokens: 1024 
        });
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
    <Box sx={{ maxWidth: 1000, mx: 'auto', p: isMobile ? 1.5 : 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: isMobile ? 1 : 2, display: 'flex', alignItems: 'center', gap: 1, fontSize: isMobile ? 18 : 'inherit' }}>
        <MagicIcon sx={{ color: 'var(--color-primary-400)', fontSize: isMobile ? 20 : 24 }} />
        {t('agentChat.title')}
      </Typography>

      {/* 快捷工具栏 — 移动端横向滚动 */}
      <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: isMobile ? 'nowrap' : 'wrap', overflowX: isMobile ? 'auto' : 'visible', pb: isMobile ? 0.5 : 0, '-webkit-overflow-scrolling': 'touch' }}>
        {QUICK_PROMPTS.map((q) => (
          <Chip
            key={q.tool}
            label={t(q.labelKey)}
            size="small"
            variant={selectedTool === q.tool ? 'filled' : 'outlined'}
            onClick={() => handleQuickPrompt(q.tool, t(q.promptKey))}
            sx={{
              cursor: 'pointer',
              flexShrink: 0,
              minWidth: isMobile ? 80 : undefined,
              fontSize: isMobile ? 11 : undefined,
              px: isMobile ? 1 : undefined,
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

      {/* 对话区域 — 移动端固定底部，滚动填充 */}
      <Card variant="outlined" sx={{ borderRadius: 2, mb: 2, minHeight: 360, background: 'var(--glass-bg-medium)', backdropFilter: 'var(--glass-blur)', border: 'var(--glass-border)', height: isMobile ? 'calc(100vh - 280px)' : undefined }}>
        <CardContent sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, maxHeight: isMobile ? 'none' : 400, overflowY: 'auto' }}>
          {messages.map((m, i) => (
            <Box
              key={i}
              sx={{
                alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start',
                maxWidth: '85%',
                p: isMobile ? 1 : 1.5,
                borderRadius: 2,
                background: m.role === 'user' ? 'var(--color-primary-500)' : 'var(--glass-bg-light)',
                color: 'var(--color-text-primary)',
                border: 'none',
              }}
            >
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
                {m.role === 'assistant' && <MagicIcon sx={{ fontSize: 14, color: 'var(--color-primary-300)' }} />}
                <Typography variant="caption" sx={{ fontWeight: 600, opacity: 0.8, fontSize: 10 }}>
                  {m.role === 'user' ? t('agentChat.roleUser') : t('agentChat.roleCopilot')}
                </Typography>
                {m.tool && (
                  <Chip size="small" label={m.tool} sx={{ bgcolor: 'rgba(255,255,255,0.15)', color: 'var(--color-text-primary)', fontSize: 9, height: 16 }} />
                )}
              </Box>
              <Typography variant="body2" sx={{ color: 'var(--color-text-primary)', whiteSpace: 'pre-wrap', fontSize: isMobile ? 13 : undefined }}>{m.content}</Typography>
            </Box>
          ))}
          {mutation.isPending && (
            <Box sx={{ alignSelf: 'flex-start', p: 1.5 }}>
              <CircularProgress size={20} />
            </Box>
          )}
        </CardContent>
      </Card>

      {/* 模型选择器 — 移动端紧凑显示 */}
      {(statusQuery.data?.data?.models?.length ?? 0) > 1 && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
          <SettingsIcon sx={{ fontSize: 16, color: 'var(--color-text-secondary)' }} />
          <Select
            size="small"
            value={selectedModel ?? ''}
            onChange={(e: SelectChangeEvent<string>) => setSelectedModel(e.target.value)}
            sx={{
              minWidth: isMobile ? 120 : 200,
              fontSize: isMobile ? 11 : undefined,
              '& .MuiSelect-select': { py: 0.5, fontSize: isMobile ? 11 : undefined },
            }}
            aria-label={t('agentChat.modelSelectorAria')}
          >
            {statusQuery.data!.data.models.map((m) => (
              <MenuItem key={m} value={m} sx={{ fontSize: isMobile ? 11 : undefined }}>{m}</MenuItem>
            ))}
          </Select>
        </Box>
      )}

      {/* 输入区域 — 移动端占满宽度 */}
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-end' }}>
        <TextField
          fullWidth
          multiline
          maxRows={isMobile ? 2 : 3}
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
          sx={{ fontSize: isMobile ? 13 : undefined }}
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
              width: 40, height: 40,
            }}
          >
            {mutation.isPending ? <CircularProgress size={20} color="inherit" /> : <SendIcon />}
          </IconButton>
        </Tooltip>
      </Box>

      {/* 配额信息 — 移动端更紧凑 */}
      {quotaQuery.data?.data?.remaining !== undefined && (
        <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block', fontSize: isMobile ? 10 : undefined }}>
          {t('agentChat.quotaRemaining', { count: quotaQuery.data.data.remaining })}
        </Typography>
      )}
    </Box>
  );
}
