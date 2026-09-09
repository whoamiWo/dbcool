# ADR-001: 前端使用 React 18 + TypeScript

- **状态**: ACCEPTED
- **日期**: 2026-09-09
- **影响栈**: JS/TS

## 背景

需要构建一个可视化低代码平台,核心特性包括:
- 拖拽设计器(Schema / Form / View / Workflow)
- 运行时根据 Schema 动态渲染 UI
- 复杂表单交互与状态管理

## 决策

前端技术栈:
- **React 18** + **TypeScript 5**(严格模式)
- **Vite 5** 构建
- **pnpm** 包管理
- **React Flow** 用于工作流编排
- **dnd-kit** 用于拖拽
- **Zustand** 用于状态管理
- **React Hook Form + Zod** 用于表单
- **TanStack Query** 用于数据请求
- **Vitest + Testing Library + Playwright** 测试
- **ESLint + Prettier + tsc** 代码质量

## 备选方案

| 方案 | 优点 | 缺点 | 否决原因 |
|---|---|---|---|
| Vue 3 | 中文社区好、上手快 | 拖拽生态弱、TS 支持略差 | 拖拽需求大 |
| Svelte | 性能好、体积小 | 生态小、人才难招 | 长期维护风险 |
| SolidJS | 性能极佳 | 生态太新 | 风险过高 |

## 后果

### 正面
- React 生态最丰富,拖拽(dnd-kit)、流程图(React Flow)、表单(RHF)都有成熟库
- TS 严格模式保证 Schema 引擎的接口类型安全
- Vite 启动毫秒级,DX 优秀

### 负面
- React 18 SSR 复杂,首屏优化需额外工作(可用 React Server Components 缓解)
- 状态管理需要克制,Zustand 多个 store 协调需约定
- 拖拽性能瓶颈需用虚拟化解决

### 缓解措施
- 引入 ESLint 规则禁止 PropTypes
- 制定 State 划分约定(全局 / 页面 / 表单)
- 用 React.memo + useMemo 优化拖拽性能
