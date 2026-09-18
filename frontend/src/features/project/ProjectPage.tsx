import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Box, Button, Card, CardContent, Stack, Tab, Tabs, TextField, Typography,
} from '@mui/material';
import TaskBoard from './TaskBoard';
import GanttView from './GanttView';
import { GanttLegend } from './GanttView';

/**
 * 项目协同页 — 看板(Trello 对标)与甘特图切换。
 * projectId 由 URL 查询参数或输入框提供。
 */
export default function ProjectPage() {
  const [params, setParams] = useSearchParams();
  const [tab, setTab] = useState(0);
  const [input, setInput] = useState(params.get('projectId') ?? '');
  const [projectId, setProjectId] = useState(params.get('projectId') ?? '');

  const load = () => {
    if (!input.trim()) return;
    setProjectId(input.trim());
    setParams({ projectId: input.trim() });
  };

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: 2 }}>
        🗂️ 项目协同
      </Typography>

      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
            <TextField
              size="small"
              label="项目 ID"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="输入项目 UUID"
              sx={{ minWidth: 320 }}
            />
            <Button variant="contained" onClick={load} disabled={!input.trim()}>
              加载任务
            </Button>
          </Stack>
        </CardContent>
      </Card>

      {!projectId ? (
        <Typography color="text.secondary">
          请输入项目 ID 以查看该项目的任务看板与甘特图。
        </Typography>
      ) : (
        <>
          <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
            <Tab label="看板" />
            <Tab label="甘特图" />
          </Tabs>

          {tab === 0 && <TaskBoard projectId={projectId} />}
          {tab === 1 && (
            <Box>
              <GanttLegend />
              <GanttView projectId={projectId} />
            </Box>
          )}
        </>
      )}
    </Box>
  );
}
