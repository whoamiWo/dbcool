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

# PHASE114: 安装 python3（entrypoint 用于渲染 nginx 模板）
# alpine 自带 busybox 但无 python，需显式安装
RUN apk add --no-cache python3

# 复制构建产物
COPY --from=build /app/dist .

# PHASE114: 复制 nginx 模板 + render_nginx.py + entrypoint
COPY nginx.conf.template /etc/nginx/nginx.conf.template
COPY render_nginx.py /render_nginx.py
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 80 443

# PHASE114: 双协议健康检查兜底（HTTP + HTTPS），兼容 IP 直连模式（仅 80）与域名模式（443）
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD wget -q --spider --no-check-certificate http://127.0.0.1/health || wget -q --spider --no-check-certificate https://127.0.0.1/health || exit 1

# PHASE114: 渲染模板后启动 nginx（PUBLIC_HOST 决定 80/443 模式）
ENTRYPOINT ["docker-entrypoint.sh"]
