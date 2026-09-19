import { useState } from 'react';
import { Dialog, TextField, Button, Box, Typography } from '@mui/material';

/** Rollup/Lookup 字段配置器 — 选择关联字段与聚合方式 */
export function RollupEditor({
  open,
  onClose,
  onSave,
  initialValue,
  relationFields,
}: {
  open: boolean;
  onClose: () => void;
  onSave: (config: { relationField: string; targetField: string; agg?: string }) => void;
  initialValue?: { relationField: string; targetField: string; agg?: string };
  relationFields: Array<{ name: string; label?: string; type: string }>;
}) {
  const [relationField, setRelationField] = useState(initialValue?.relationField ?? '');
  const [targetField, setTargetField] = useState(initialValue?.targetField ?? '');
  const [agg, setAgg] = useState(initialValue?.agg ?? 'SUM');

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <Box sx={{ p: 3 }}>
        <Typography variant="h6" gutterBottom>
          编辑聚合字段
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          选择关联字段与目标字段，并指定聚合方式（SUM/COUNT/AVG/MIN/MAX）
        </Typography>

        <TextField
          select
          fullWidth
          label="关联字段"
          value={relationField}
          onChange={(e) => setRelationField(e.currentTarget.value)}
          sx={{ mb: 2 }}
          slotProps={{ select: { native: true } }}
        >
          {relationFields.map((f) => (
            <option key={f.name} value={f.name}>
              {f.label ?? f.name} ({f.type})
            </option>
          ))}
        </TextField>

        <TextField
          select
          fullWidth
          label="目标字段"
          value={targetField}
          onChange={(e) => setTargetField(e.currentTarget.value)}
          sx={{ mb: 2 }}
          disabled
          slotProps={{ select: { native: true } }}
        >
          <option>暂无可用字段</option>
        </TextField>

        <TextField
          select
          fullWidth
          label="聚合方式"
          value={agg}
          onChange={(e) => setAgg(e.currentTarget.value)}
          sx={{ mb: 2 }}
          slotProps={{ select: { native: true } }}
        >
          <option value="SUM">SUM</option>
          <option value="COUNT">COUNT</option>
          <option value="AVG">AVG</option>
          <option value="MIN">MIN</option>
          <option value="MAX">MAX</option>
        </TextField>

        <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
          <Button onClick={onClose}>取消</Button>
          <Button
            variant="contained"
            disabled={!relationField || !targetField}
            onClick={() => onSave({ relationField, targetField, agg })}
          >
            保存
          </Button>
        </Box>
      </Box>
    </Dialog>
  );
}
