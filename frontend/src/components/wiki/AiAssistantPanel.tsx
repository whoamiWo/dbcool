import { useState } from 'react';
import type { ReactElement } from 'react';
import {
  Box,
  Paper,
  IconButton,
  Tooltip,
  Typography,
  Divider,
  Collapse,
  TextField,
  Button,
  Alert,
  CircularProgress,
} from '@mui/material';
import SmartToyIcon from '@mui/icons-material/SmartToy';
import ArticleIcon from '@mui/icons-material/Article';
import HelpIcon from '@mui/icons-material/Help';
import AutoFixHighIcon from '@mui/icons-material/AutoFixHigh';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';

interface AiAssistantPanelProps {
  pageId: number;
  pageTitle: string;
  pageContent: string;
}

/**
 * AI 助手面板 - Wiki 页面编辑页右侧折叠面板
 * 
 * 功能:
 * 1. 📝 自动生成大纲（基于标题结构）
 * 2. 🔍 智能问答（RAG 检索 + LLM 回答）
 * 3. ✨ 文本润色（选中文字后调用）
 */
export function AiAssistantPanel({ pageId, pageTitle, pageContent }: AiAssistantPanelProps) {
  const [expanded, setExpanded] = useState(false);
  const [activeTab, setActiveTab] = useState<'outline' | 'qa' | 'polish'>('qa');
  const [qaQuestion, setQaQuestion] = useState('');
  const [qaAnswer, setQaAnswer] = useState('');
  const [polishText, setPolishText] = useState('');
  const [polishedResult, setPolishedResult] = useState('');
  
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 生成大纲
  const generateOutline = async () => {
    setLoading(true);
    setError(null);
    try {
      // TODO: 调用后端 API 生成大纲
      const response = await fetch(`/api/wiki/${pageId}/generate-outline`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: pageContent }),
      });
      
      if (!response.ok) throw new Error('生成失败');
      
      const data = await response.json();
      setPolishedResult(data.outline || '未生成内容');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  // 智能问答
  const askQuestion = async () => {
    if (!qaQuestion.trim()) return;
    
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(`/api/wiki/${pageId}/ask`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          question: qaQuestion,
          context: pageContent 
        }),
      });
      
      if (!response.ok) throw new Error('问答失败');
      
      const data = await response.json();
      setQaAnswer(data.answer || '暂无答案');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  // 文本润色
  const polishTextAction = async () => {
    if (!polishText.trim()) return;
    
    setLoading(true);
    setError(null);
    try {
      const response = await fetch('/api/ai/polish', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: polishText }),
      });
      
      if (!response.ok) throw new Error('润色失败');
      
      const data = await response.json();
      setPolishedResult(data.polishedText || polishText);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Paper
      sx={{
        width: 350,
        flexShrink: 0,
        ml: 2,
        borderLeft: '1px solid #e0e0e0',
        bgcolor: '#fafafa',
      }}
    >
      {/* 头部 */}
      <Box sx={{ p: 2, borderBottom: '1px solid #e0e0e0', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <SmartToyIcon sx={{ color: '#1976d2' }} />
          <Typography variant="h6">AI 助手</Typography>
        </Box>
        <IconButton size="small" onClick={() => setExpanded(!expanded)} aria-label={expanded ? '收起' : '展开'}>
          {expanded ? <ExpandLessIcon /> : <ExpandMoreIcon />}
        </IconButton>
      </Box>

      {/* 折叠内容 */}
      <Collapse in={expanded}>
        {/* Tab 切换 */}
        <Box sx={{ display: 'flex', borderBottom: '1px solid #e0e0e0' }}>
          <Tooltip title="📝 生成大纲">
            <Button
              size="small"
              startIcon={<ArticleIcon />}
              onClick={() => setActiveTab('outline')}
              sx={{ flex: 1, borderRadius: 0, bgcolor: activeTab === 'outline' ? '#e3f2fd' : 'transparent' }}
            >
              大纲
            </Button>
          </Tooltip>
          <Tooltip title="🔍 智能问答">
            <Button
              size="small"
              startIcon={<HelpIcon />}
              onClick={() => setActiveTab('qa')}
              sx={{ flex: 1, borderRadius: 0, bgcolor: activeTab === 'qa' ? '#e3f2fd' : 'transparent' }}
            >
              问答
            </Button>
          </Tooltip>
          <Tooltip title="✨ 润色文本">
            <Button
              size="small"
              startIcon={<AutoFixHighIcon />}
              onClick={() => setActiveTab('polish')}
              sx={{ flex: 1, borderRadius: 0, bgcolor: activeTab === 'polish' ? '#e3f2fd' : 'transparent' }}
            >
              润色
            </Button>
          </Tooltip>
        </Box>

        <Divider />

        {/* 错误提示 */}
        {error && (
          <Alert severity="error" sx={{ m: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        {/* 大纲面板 */}
        {activeTab === 'outline' && (
          <Box sx={{ p: 2 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              基于当前内容自动生成结构化大纲
            </Typography>
            <Button
              variant="contained"
              fullWidth
              startIcon={<ArticleIcon />}
              onClick={generateOutline}
              disabled={loading}
              sx={{ mb: 2 }}
            >
              {loading ? <CircularProgress size={20} /> : '生成大纲'}
            </Button>
            {polishedResult && (
              <Paper sx={{ p: 2, bgcolor: '#fff' }}>
                <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: '14px' }}>
                  {polishedResult}
                </pre>
              </Paper>
            )}
          </Box>
        )}

        {/* 问答面板 */}
        {activeTab === 'qa' && (
          <Box sx={{ p: 2 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              基于知识库的智能问答（RAG）
            </Typography>
            <TextField
              fullWidth
              multiline
              rows={3}
              placeholder="输入您的问题..."
              value={qaQuestion}
              onChange={(e) => setQaQuestion(e.target.value)}
              sx={{ mb: 2 }}
            />
            <Button
              variant="contained"
              fullWidth
              startIcon={<HelpIcon />}
              onClick={askQuestion}
              disabled={loading || !qaQuestion.trim()}
              sx={{ mb: 2 }}
            >
              {loading ? <CircularProgress size={20} /> : '提问'}
            </Button>
            {qaAnswer && (
              <Paper sx={{ p: 2, bgcolor: '#fff' }}>
                <Typography variant="body2" dangerouslySetInnerHTML={{ __html: qaAnswer }} />
              </Paper>
            )}
          </Box>
        )}

        {/* 润色面板 */}
        {activeTab === 'polish' && (
          <Box sx={{ p: 2 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              粘贴需要润色的文本
            </Typography>
            <TextField
              fullWidth
              multiline
              rows={4}
              placeholder="输入要润色的文字..."
              value={polishText}
              onChange={(e) => setPolishText(e.target.value)}
              sx={{ mb: 2 }}
            />
            <Button
              variant="contained"
              fullWidth
              startIcon={<AutoFixHighIcon />}
              onClick={polishTextAction}
              disabled={loading || !polishText.trim()}
              sx={{ mb: 2 }}
            >
              {loading ? <CircularProgress size={20} /> : '润色'}
            </Button>
            {polishedResult && (
              <Paper sx={{ p: 2, bgcolor: '#fff' }}>
                <Typography variant="body2" sx={{ mb: 1, fontWeight: 'bold' }}>润色结果：</Typography>
                <Typography variant="body2">{polishedResult}</Typography>
              </Paper>
            )}
          </Box>
        )}
      </Collapse>
    </Paper>
  );
}
