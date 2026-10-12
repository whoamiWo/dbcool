import { IconButton, type IconButtonProps } from '@mui/material';

/**
 * PHASE98 R4: 图标按钮封装 —— **`aria-label` 强制必填**。
 *
 * 背景：图标按钮没有可见文字，屏幕阅读器只能读到"按钮"。全仓约 30 个
 * icon-only 按钮里只有 4 个写了显式 `aria-label`（87% 缺失），
 * 在政企招标的 VPAT / WCAG 2.1 AA 合规审查中是硬门槛。
 *
 * 这里把 `aria-label` 从可选改为**必填**，让 TypeScript 从源头阻止新增无标签的图标按钮。
 *
 * 用法：
 *   <IconButtonA11y aria-label="删除该页面" onClick={...}><DeleteIcon /></IconButtonA11y>
 */
export type IconButtonA11yProps = Omit<IconButtonProps, 'aria-label'> & {
  /** 可访问名（必填，且应有语义，不得写 "button" 这类占位） */
  'aria-label': string;
};

export function IconButtonA11y({ children, ...rest }: IconButtonA11yProps) {
  return <IconButton {...rest}>{children}</IconButton>;
}

export default IconButtonA11y;
