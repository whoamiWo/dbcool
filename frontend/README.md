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


## 🧪 测试覆盖(Week 37 — 10 文件 / 55 tests)

| 文件 | tests | 目标 |
|---|---|---|
| `pages/Login.test.tsx` | 7 | 渲染 / 校验 / 成功 / 后端错误 / 网络错误 / 记住用户名 / 预填 |
| `pages/Profile.test.tsx` | 5 | 渲染 / 加载 / 字段 / 改密码成功 / 失败 |
| `pages/Home.test.tsx` | 5 | 游客 / display_name / fallback username / loading |
| `pages/CollectionsList.test.tsx` | 5 | 加载 / 错误 / 空 / 数据 / 新建按钮 |
| `pages/FormsList.test.tsx` | 6 | 加载 / 错误 / 无 collection / 有 collection / 数据 / 空状态 |
| `pages/ViewsList.test.tsx` | 4 | 加载 / 空 / collection 过滤 / 数据 |
| `pages/RolesList.test.tsx` | 7 | 加载 / 空 / 数据 / 创建按钮 / 成功 / 失败 / disabled |
| `pages/UsersList.test.tsx` | 7 | 加载 / 空 / 数据 / 创建按钮 / 成功 / 失败 / disabled |
| `components/AppLayout.test.tsx` | 4 | 导航 / 退出 / active link / 未登录 |
| `stores/auth.test.ts` | 5 | 初始 / setAuth / clear / 多次覆盖 / localStorage |
| **总计** | **55 tests PASS** | |

跑测试:`pnpm test:run`(约 1.5 秒)

vitest 配置(`vite.config.ts`):
- `environment: 'jsdom'`(React DOM 测试环境)
- `globals: true`(describe/it/expect 不需 import)
- `setupFiles: ['./src/test-setup.ts'`(引入 `@testing-library/jest-dom` matchers)
- `include: ['src/**/*.{test,spec}.{ts,tsx}']`(只跑 src/)
- `exclude: ['node_modules', 'dist', '.vscode-server']`(排除干扰)

## 🧪 测试覆盖(Week 37 — 10 文件 / 55 tests)

| 文件 | tests | 目标 |
|---|---|---|
| `pages/Login.test.tsx` | 7 | 渲染 / 校验 / 成功 / 后端错误 / 网络错误 / 记住用户名 / 预填 |
| `pages/Profile.test.tsx` | 5 | 渲染 / 加载 / 字段 / 改密码成功 / 失败 |
| `pages/Home.test.tsx` | 5 | 游客 / display_name / fallback username / loading |
| `pages/CollectionsList.test.tsx` | 5 | 加载 / 错误 / 空 / 数据 / 新建按钮 |
| `pages/FormsList.test.tsx` | 6 | 加载 / 错误 / 无 collection / 有 collection / 数据 / 空状态 |
| `pages/ViewsList.test.tsx` | 4 | 加载 / 空 / collection 过滤 / 数据 |
| `pages/RolesList.test.tsx` | 7 | 加载 / 空 / 数据 / 创建按钮 / 成功 / 失败 / disabled |
| `pages/UsersList.test.tsx` | 7 | 加载 / 空 / 数据 / 创建按钮 / 成功 / 失败 / disabled |
| `components/AppLayout.test.tsx` | 4 | 导航 / 退出 / active link / 未登录 |
| `stores/auth.test.ts` | 5 | 初始 / setAuth / clear / 多次覆盖 / localStorage |
| **总计** | **55 tests PASS** | |

跑测试:`pnpm test:run`(约 1.5 秒)

vitest 配置(`vite.config.ts`):
- `environment: 'jsdom'`(React DOM 测试环境)
- `globals: true`(describe/it/expect 不需 import)
- `setupFiles: ['./src/test-setup.ts'](引入 `@testing-library/jest-dom` matchers)
- `include: ['src/**/*.{test,spec}.{ts,tsx}']`(只跑 src/)
- `exclude: ['node_modules', 'dist', '.vscode-server']`(排除干扰)
