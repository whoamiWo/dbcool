# ============================================================
#  CRDT 服务端合并服务 — 多阶段构建（离线友好）
# ============================================================
#
# 背景：crdt-service 依赖 yjs、pg、express 等 npm 包，
#       离线环境下无法从 npm registry 拉取。
#
# 方案：
#   1) 第一阶段：使用 node:20-alpine 安装依赖（需网络）
#   2) 第二阶段：最小化镜像，仅复制 node_modules 和源码
#
# 离线构建（当网络不可用时）：
#   1) 先在主机运行：npm ci（将 node_modules 下载到本地）
#   2) 修改 Dockerfile，直接使用 host 的 node_modules 作为构建上下文
#   3) docker build -f Dockerfile.offline -t nocobase-crdt-service:latest .
#
# 正常构建（网络可用时）：
#   docker build -t nocobase-crdt-service:latest .

FROM node:20-alpine AS builder

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY index.js ./

FROM node:20-alpine

WORKDIR /app

ENV NODE_ENV=production
ENV PORT=3100

# 复制依赖（来自 builder 阶段）
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./
COPY --from=builder /app/index.js ./

EXPOSE 3100

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -qO- http://localhost:3100/health || exit 1

CMD ["node", "index.js"]
