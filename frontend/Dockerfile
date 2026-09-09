# ============================================================
#  NocoBase 前端 — 多阶段构建
# ============================================================

FROM node:20-alpine AS build
WORKDIR /app

# 启用 pnpm
RUN corepack enable && corepack prepare pnpm@9.12.0 --activate

# 复制依赖文件(利用 Docker 缓存)
COPY package.json pnpm-lock.yaml* ./
RUN pnpm install --frozen-lockfile || pnpm install

# 复制源码并构建
COPY . .
RUN pnpm build

# Runtime — Nginx
FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

# 复制构建产物
COPY --from=build /app/dist .

# 复制 nginx 配置
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q --spider http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
