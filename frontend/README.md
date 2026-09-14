# NocoBase 前端

> React 18 + TypeScript 5 + Vite 5 + TanStack Query

## 本地启动

```bash
# 1. 安装依赖
pnpm install

# 2. 启动开发服务器
pnpm dev

# 3. 浏览器打开
open http://localhost:5173
```

或用 Makefile:

```bash
make frontend   # 启动前端
```

## 测试

```bash
pnpm test           # watch 模式
pnpm test:run       # 一次性
pnpm tsc            # 类型检查
pnpm lint           # ESLint
pnpm format         # Prettier
```

## 构建

```bash
pnpm build          # 构建生产产物(到 dist/)
pnpm preview        # 预览生产构建
```

## 模块结构

```
src/
├── main.tsx                  # 入口
├── router.tsx                # 路由配置
├── styles.css                # 全局样式
├── test-setup.ts             # 测试 setup
├── api/
│   └── client.ts             # Axios 实例(自动注入 token)
├── stores/
│   └── auth.ts               # 认证状态(Zustand)
├── components/
│   └── AppLayout.tsx         # 应用布局(顶部导航)
└── pages/
    ├── Login.tsx             # 登录页
    ├── Login.test.tsx
    ├── Home.tsx              # 首页
    └── SchemaDesigner.tsx    # Schema Designer(Week 3 占位)
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | /api | 后端 API 基础 URL(开发时通过 vite proxy 转发) |

## Vite 代理(开发模式)

`vite.config.ts` 配置:
- `/api/*` → `http://localhost:8080`(Java)
- `/api/ai/*` → `http://localhost:8000`(Python)

生产环境由 `nginx.conf` 处理反向代理。



## 🧪 测试覆盖(Week 38 — 15 文件 / 92 tests + coverage)

| 文件 | tests | 目标 |
|---|---|---|
| `api/client.test.ts` | 10 | **token 注入 / 401 重定向 / 响应解包 / 错误透传 / 方法委托** |
| `pages/Login.test.tsx` | 7 | 渲染 / 校验 / 成功 / 后端错误 / 网络错误 / 记住用户名 / 预填 |
| `pages/Profile.test.tsx` | 5 | 渲染 / 加载 / 字段 / 改密码成功 / 失败 |
| `pages/Home.test.tsx` | 5 | 游客 / display_name / fallback username / loading |
| `pages/CollectionsList.test.tsx` | 7 | 加载 / 错误 / 空 / 数据 / 新建按钮 |
| `pages/FormsList.test.tsx` | 6 | 加载 / 错误 / 无 collection / 有 collection / 数据 / 空状态 |
| `pages/ViewsList.test.tsx` | 4 | 加载 / 空 / collection 过滤 / 数据 |
| `pages/RolesList.test.tsx` | 7 | 加载 / 空 / 数据 / 创建按钮 / 成功 / 失败 / disabled |
| `pages/UsersList.test.tsx` | 7 | 加载 / 空 / 数据 / 创建按钮 / 成功 / 失败 / disabled |
| `pages/AuditLogs.test.tsx` | 7 | 加载 / 空 / 数据 / 过滤 / payload 展开 |
| `pages/MessagesInbox.test.tsx` | 7 | 加载 / 空 / 未读数 / 加载更多 / markRead / 只看未读 |
| `pages/MyTasks.test.tsx` | 6 | 加载 / PENDING 过滤 / 通过 / 拒绝 / 空待办 / 计数 |
| `pages/NotificationChannels.test.tsx` | 7 | fetch 加载 / 错误 / 新建 / 保存 / 删除 / 401 |
| `components/AppLayout.test.tsx` | 4 | 导航 / 退出 / active link / 未登录 |
| `stores/auth.test.ts` | 5 | 初始 / setAuth / clear / 多次覆盖 / localStorage |
| **总计** | **92 tests PASS** | |

跑测试:
```bash
pnpm test:run        # 一次性
pnpm test:coverage   # 一次性 + 覆盖率报告(写 coverage/)
```

### 覆盖率(Week 38,首次有数字!)

| 模块 | % Stmts |
|---|---|
| `api/client.ts` | **100%** ⭐ |
| `stores/auth.ts` | **100%** ⭐ |
| `components/AppLayout.tsx` | **100%** ⭐ |
| `components/forms/FormRuntime.tsx` | 0%(Week 39 候选) |
| `components/views/FilterBar.tsx` | 0%(Week 39 候选) |
| **All files(排除 pages/)** | **29.11%** |

vitest 配置(`vite.config.ts`):
- `environment: 'jsdom'`(React DOM 测试环境)
- `globals: true`(describe/it/expect 不需 import)
- `setupFiles: ['./src/test-setup.ts'](引入 `@testing-library/jest-dom` matchers)
- `include: ['src/**/*.{test,spec}.{ts,tsx}']`(只跑 src/)
- `exclude: ['node_modules', 'dist', '.vscode-server']`(排除干扰)
- `coverage.provider: 'v8'`(V8 内置覆盖率,无需 Babel)

CI 集成(`.github/workflows/ci.yml`):
- 跑 `pnpm test:coverage` 并把 `coverage/` 作为 artifact 上传
