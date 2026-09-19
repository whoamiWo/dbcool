import { useState } from 'react';
import { Dialog, TextField, Button, Box, Typography } from '@mui/material';

/** 公式字段配置器 — 输入表达式如 price * qty */
export function FormulaEditor({
  open,
  onClose,
  onSave,
  initialValue,
}: {
  open: boolean;
  onClose: () => void;
  onSave: (expr: string) => void;
  initialValue?: string;
}) {
  const [expr, setExpr] = useState(initialValue ?? '');

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <Box sx={{ p: 3 }}>
        <Typography variant="h6" gutterBottom>
          编辑公式
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          支持的运算符：+ - * / % ^，函数：ABS/SQRT/POWER/CONCAT/UPPER/LOWER/LEN/TRIM
        </Typography>
        <TextField
          fullWidth
          label="表达式"
          placeholder="例如：price * qty"
          value={expr}
          onChange={(e) => setExpr(e.currentTarget.value)}
          sx={{ mb: 2 }}
        />
        <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
          <Button onClick={onClose}>取消</Button>
          <Button variant="contained" onClick={() => onSave(expr)}>
            保存
          </Button>
        </Box>
      </Box>
    </Dialog>
  );
}
