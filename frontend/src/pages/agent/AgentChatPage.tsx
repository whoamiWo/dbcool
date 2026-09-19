import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Box, Button, Card, CardContent, TextField, Typography, CircularProgress } from '@mui/material';
import SendIcon from '@mui/icons-material/Send';
import apiClient from '@/api/client';

export function AgentChatPage() {
  const [prompt, setPrompt] = useState('');
  const [messages, setMessages] = useState<{ role: string; content: string }[]>([
    { role: 'assistant', content: '你好，我是 AI Agent。你可以输入任务请求，例如 /create_task 或搜索知识库。' },
  ]);

  const mutation = useMutation({
    mutationFn: (p: string) =>
      apiClient.post<{ code: number; data: { result: string; agentName: string } }>(
        '/ai/agent/execute',
        { prompt: p, channelId: '' },
      ),
    onSuccess: (resp) => {
      const result = resp?.data?.result ?? '无响应';
      setMessages((prev) => [
        ...prev,
        { role: 'user', content: prompt },
        { role: 'assistant', content: String(result) },
      ]);
      setPrompt('');
    },
    onError: () => {
      setMessages((prev) => [
        ...prev,
        { role: 'user', content: prompt },
        { role: 'assistant', content: '请求失败，可能 Agent 未启用或通道未配置。' },
      ]);
      setPrompt('');
    },
  });

  return (
    <Box sx={{ maxWidth: 960, mx: 'auto', p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: 2 }}>
        AI Agent 对话
      </Typography>
      <Card variant="outlined" sx={{ borderRadius: 2, mb: 2, minHeight: 320 }}>
        <CardContent sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          {messages.map((m, i) => (
            <Box
              key={i}
              sx={{
                alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start',
                maxWidth: '80%',
                p: 1.5,
                borderRadius: 2,
                background: m.role === 'user' ? '#e3f2fd' : '#f5f5f5',
                border: '1px solid #e2e8f0',
              }}
            >
              <Typography variant="body2" sx={{ fontWeight: 500, mb: 0.5 }}>
                {m.role === 'user' ? '你' : 'Agent'}
              </Typography>
              <Typography variant="body2">{m.content}</Typography>
            </Box>
          ))}
        </CardContent>
      </Card>
      <Box sx={{ display: 'flex', gap: 1 }}>
        <TextField
          fullWidth
          label="输入任务请求"
          placeholder="例如：搜索知识库 / 创建任务 / 生成文档"
          value={prompt}
          onChange={(e) => setPrompt(e.currentTarget.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              if (prompt.trim()) mutation.mutate(prompt.trim());
            }
          }}
          disabled={mutation.isPending}
        />
        <Button
          variant="contained"
          startIcon={mutation.isPending ? <CircularProgress size={16} color="inherit" /> : <SendIcon />}
          onClick={() => {
            if (prompt.trim()) mutation.mutate(prompt.trim());
          }}
          disabled={mutation.isPending || !prompt.trim()}
        >
          发送
        </Button>
      </Box>
    </Box>
  );
}
