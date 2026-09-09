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
