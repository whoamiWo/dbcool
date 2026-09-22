import { useMemo } from 'react';
import {
  Box,
  Paper,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableRow,
  Chip,
} from '@mui/material';
import { CompareArrows } from '@mui/icons-material';

interface DiffLine {
  type: 'added' | 'removed' | 'unchanged';
  content: string;
  oldLineNumber?: number;
  newLineNumber?: number;
}

interface WikiDiffViewerProps {
  oldContent: string;
  newContent: string;
  oldVersion: number;
  newVersion: number;
}

/**
 * 简易行级 Diff 比较器
 * 逐行比较两个版本的内容，标记新增、删除、未变更的行
 */
function computeDiff(oldText: string, newText: string): DiffLine[] {
  const oldLines = oldText.split('\n');
  const newLines = newText.split('\n');
  const diff: DiffLine[] = [];

  const oldSet = new Set(oldLines);
  const newSet = new Set(newLines);

  let oldIdx = 0;
  let newIdx = 0;

  while (oldIdx < oldLines.length || newIdx < newLines.length) {
    const oldLine = oldLines[oldIdx];
    const newLine = newLines[newIdx];

    if (oldIdx < oldLines.length && newIdx < newLines.length) {
      if (oldLine === newLine) {
        diff.push({ type: 'unchanged', content: oldLine, oldLineNumber: oldIdx + 1, newLineNumber: newIdx + 1 });
        oldIdx++;
        newIdx++;
      } else if (newSet.has(oldLine) === false && oldSet.has(newLine) === false) {
        // 两边都不在对方中 — 同时删除和新增
        diff.push({ type: 'removed', content: oldLine, oldLineNumber: oldIdx + 1 });
        diff.push({ type: 'added', content: newLine, newLineNumber: newIdx + 1 });
        oldIdx++;
        newIdx++;
      } else if (!oldSet.has(newLine)) {
        // 新行是新增的
        diff.push({ type: 'added', content: newLine, newLineNumber: newIdx + 1 });
        newIdx++;
      } else {
        // 旧行是删除的
        diff.push({ type: 'removed', content: oldLine, oldLineNumber: oldIdx + 1 });
        oldIdx++;
      }
    } else if (oldIdx < oldLines.length) {
      diff.push({ type: 'removed', content: oldLine, oldLineNumber: oldIdx + 1 });
      oldIdx++;
    } else {
      diff.push({ type: 'added', content: newLine, newLineNumber: newIdx + 1 });
      newIdx++;
    }
  }

  return diff;
}

export function WikiDiffViewer({
  oldContent,
  newContent,
  oldVersion,
  newVersion,
}: WikiDiffViewerProps) {
  const diff = useMemo(
    () => computeDiff(oldContent, newContent),
    [oldContent, newContent]
  );

  const stats = useMemo(() => {
    const added = diff.filter((d) => d.type === 'added').length;
    const removed = diff.filter((d) => d.type === 'removed').length;
    const unchanged = diff.filter((d) => d.type === 'unchanged').length;
    return { added, removed, unchanged };
  }, [diff]);

  return (
    <Paper sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
        <CompareArrows sx={{ color: 'primary.main' }} />
        <Typography variant="subtitle1">
          版本对比: v{oldVersion} → v{newVersion}
        </Typography>
        <Chip label={`+${stats.added}`} color="success" size="small" />
        <Chip label={`-${stats.removed}`} color="error" size="small" />
        <Chip label={`${stats.unchanged} 未变`} color="default" size="small" />
      </Box>

      <Table size="small" sx={{ fontFamily: 'monospace', fontSize: 13 }}>
        <TableBody>
          {diff.map((line, index) => (
            <TableRow
              key={index}
              sx={{
                backgroundColor:
                  line.type === 'added'
                    ? 'rgba(16,185,129,0.2)'
                    : line.type === 'removed'
                    ? 'rgba(239,68,68,0.1)'
                    : 'transparent',
              }}
            >
              <TableCell sx={{ width: 30, fontWeight: 'bold' }}>
                {line.type === 'added' ? '+' : line.type === 'removed' ? '-' : ' '}
              </TableCell>
              <TableCell sx={{ color: 'text.secondary', fontSize: 12 }}>
                {line.oldLineNumber ?? ''}
              </TableCell>
              <TableCell sx={{ color: 'text.secondary', fontSize: 12 }}>
                {line.newLineNumber ?? ''}
              </TableCell>
              <TableCell
                sx={{
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                  backgroundColor:
                    line.type === 'added'
                      ? 'rgba(16,185,129,0.2)'
                      : line.type === 'removed'
                      ? 'rgba(239,68,68,0.2)'
                      : 'transparent',
                }}
              >
                {line.content || '\u00A0'}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Paper>
  );
}
