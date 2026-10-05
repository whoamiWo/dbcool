# 移动端适配说明（PHASE73 实测校正）

## 实测结论（2026-10-05）
- **断点**: `useIsMobile()` → `matchMedia('(max-width:768px)')`
- **45 个页面中仅 2 个完成窄屏适配**：`MessageList.tsx`、`KanbanView.tsx`
- **其余页面**: 桌面布局直接缩窄，存在横向溢出/触控区过小问题

## 已完成适配清单
| 页面/组件 | 文件路径 | 适配内容 |
|---|---|---|
| IM 消息列表 | `src/features/im/MessageList.tsx` | 头像 28/32、字号 13/14、间距、气泡 maxWidth 90%、时间戳 |
| IM 频道抽屉 | `src/features/im/ImLayout.tsx` | 窄屏汉堡菜单 → Drawer → 选中自动收起 |
| IM 消息输入 | `src/features/im/MessageComposer.tsx` | 触控区 ≥44px、iOS 16px 防缩放 |
| 底部导航 | `src/components/AppLayout.tsx` | 窄屏专属、触控区 ≥44px、当前页高亮 |
| 登录页 | `src/pages/Login.tsx` | 表单占满宽度、触控区 ≥44px、字号 16px |
| 看板视图 | `src/pages/KanbanView.tsx` | 窄屏纵向滚动、列自适应 |
| Wiki 阅读 | `src/pages/wiki/WikiPageRead.tsx` | 窄屏单列布局 |

## 未覆盖页面清单（需手动逐页核对）
- Home.tsx
- AlertCenter.tsx
- 所有 `/designer/*` 页面（Schema/Form/View 设计器）
- 所有 `/admin/*` 页面（用户/自动化配置）
- 所有报表/图表页面

## 已知限制
1. **MUI v9 的 Drawer**: 使用 `slotProps={{ paper: {...} }}`（旧版 `PaperProps` 已废弃）
2. **iOS Safari**: 输入框字号必须 ≥16px，否则自动缩放整页
3. **触控区**: WCAG 2.5.8 AA 要求 ≥44px（宽度或高度任一维度）
4. **safe-area-inset**: 移动端需额外处理刘海屏/底部 Home Indicator

## 下一步
- [ ] 逐页跑 Playwright 375×812 断言（表单不溢出、触控区≥44px、字号≥16px）
- [ ] 补全剩余 38 个页面的 `isMobile` 分支
- [ ] 建立回归测试：每次改动后跑 `npx playwright test e2e/mobile-responsive.spec.ts`
