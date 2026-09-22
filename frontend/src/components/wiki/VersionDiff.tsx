import { Box, Chip } from '@mui/material';

export interface DiffLine {
  type: 'unchanged' | 'added' | 'removed';
  content: string;
  leftLine?: number | null;
  rightLine?: number | null;
}

interface VersionDiffProps {
  oldContent: string;
  newContent: string;
  oldLabel?: string;
  newLabel?: string;
}

function diffLines(oldLines: string[], newLines: string[]): DiffLine[] {
  const result: DiffLine[] = [];
  const m = oldLines.length;
  const n = newLines.length;
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));

  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      if (oldLines[i] === newLines[j]) {
        dp[i][j] = dp[i + 1][j + 1] + 1;
      } else {
        dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
      }
    }
  }

  let i = 0;
  let j = 0;
  while (i < m && j < n) {
    if (oldLines[i] === newLines[j]) {
      result.push({ type: 'unchanged', content: oldLines[i], leftLine: i + 1, rightLine: j + 1 });
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      result.push({ type: 'removed', content: oldLines[i], leftLine: i + 1, rightLine: null });
      i++;
    } else {
      result.push({ type: 'added', content: newLines[j], leftLine: null, rightLine: j + 1 });
      j++;
    }
  }
  while (i < m) {
    result.push({ type: 'removed', content: oldLines[i], leftLine: i + 1, rightLine: null });
    i++;
  }
  while (j < n) {
    result.push({ type: 'added', content: newLines[j], leftLine: null, rightLine: j + 1 });
    j++;
  }
  return result;
}

export function VersionDiff({ oldContent, newContent, oldLabel, newLabel }: VersionDiffProps) {
  const oldLines = oldContent.split('\n');
  const newLines = newContent.split('\n');
  const diffs = diffLines(oldLines, newLines);

  const stats = {
    added: diffs.filter(d => d.type === 'added').length,
    removed: diffs.filter(d => d.type === 'removed').length,
    unchanged: diffs.filter(d => d.type === 'unchanged').length,
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 2, mb: 2, flexWrap: 'wrap' }}>
        <Chip label={`+${stats.added} 新增`} size="small" color="success" variant="outlined" />
        <Chip label={`-${stats.removed} 删除`} size="small" color="error" variant="outlined" />
        <Chip label={`=${stats.unchanged} 未变`} size="small" variant="outlined" />
        {oldLabel && <Chip label={oldLabel} size="small" variant="outlined" />}
        {newLabel && <Chip label={newLabel} size="small" variant="outlined" />}
      </Box>
      <Box
        sx={{
          fontFamily: 'monospace',
          fontSize: 13,
          maxHeight: 500,
          overflow: 'auto',
          border: '1px solid var(--color-border-medium)',
          borderRadius: 1,
        }}
      >
        {diffs.map((line, idx) => {
          const bg = line.type === 'added' ? 'rgba(16,185,129,0.2)'
            : line.type === 'removed' ? 'rgba(239,68,68,0.1)'
            : 'transparent';
          const color = line.type === 'added' ? 'var(--color-success)'
            : line.type === 'removed' ? 'var(--color-error)'
            : 'inherit';
          const prefix = line.type === 'added' ? '+' : line.type === 'removed' ? '-' : ' ';
          return (
            <Box
              key={idx}
              sx={{
                display: 'flex',
                background: bg,
                borderLeft: line.type !== 'unchanged' ? '3px solid' : 'none',
                borderColor: line.type === 'added' ? 'var(--color-success)' : line.type === 'removed' ? 'var(--color-error)' : 'transparent',
                minHeight: 20,
                lineHeight: '20px',
              }}
            >
              <Box
                sx={{
                  width: 50,
                  minWidth: 50,
                  textAlign: 'right',
                  color: 'var(--color-text-muted)',
                  borderRight: '1px solid var(--color-border-light)',
                  pr: 1,
                  userSelect: 'none',
                }}
              >
                {line.leftLine || ''}
              </Box>
              <Box
                sx={{
                  width: 50,
                  minWidth: 50,
                  textAlign: 'right',
                  color: 'var(--color-text-muted)',
                  borderRight: '1px solid var(--color-border-light)',
                  pr: 1,
                  userSelect: 'none',
                }}
              >
                {line.rightLine || ''}
              </Box>
              <Box
                sx={{
                  flex: 1,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  color,
                  pl: 1,
                  pr: 1,
                }}
              >
                {prefix} {line.content}
              </Box>
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}