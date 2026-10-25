# 企业综合平台一键部署与运维指南

## 1. 概述

本文档描述企业综合平台（Java 后端 + Python AI/集成后端 + React 前端 + CRDT 协同服务）的**从零环境一键部署流程**及日常运维操作规范。适用于生产、测试、演示等多种场景。

### 1.1 系统架构

- **基础设施层**: PostgreSQL 16 (pgvector)、Redis 7、MinIO、RabbitMQ
- **应用层**: Java 后端 (Spring Boot)、Python 后端 (FastAPI)、CRDT 服务、Nginx 前端反代
- **可观测性**: Prometheus + Grafana + AlertManager
- **编排方式**: Docker Compose v2

### 1.2 部署模式

| 模式 | 说明 | 端口绑定 | 协议 | 证书要求 |
|------|------|----------|------|----------|
| IP 直连模式 | 内网 HTTP 明文，仅建议内网使用 | 0.0.0.0 | HTTP | 不需要 |
| 域名模式 | 公网 HTTPS，启用 TLS + HSTS | 127.0.0.1 | HTTPS | 必须挂载有效证书 |
| 演示模式 | 暴露所有端口便于调试 | 0.0.0.0 | HTTP | 不需要 |
| 生产严格模式 | 强制手工输入强密码，禁止默认口令 | 127.0.0.1 | HTTP/HTTPS | 按需 |

---

## 2. 前置要求

### 2.1 硬件资源

| 资源 | 最低要求 | 推荐配置 |
|------|----------|----------|
| CPU | 4 核 | 8 核+ |
| 内存 | 16 GB | 32 GB+ |
| 磁盘空间 | 50 GB | 100 GB+ SSD |
| 操作系统 | Linux (Ubuntu 22.04+) | Ubuntu 24.04 LTS |

### 2.2 软件依赖

- Docker Engine ≥ 29.1.3
- Docker Compose ≥ 2.40
- OpenSSL 3.0+ (用于密钥生成)
- pnpm 9.15+ (前端构建)
- Maven 3.9+ (Java 构建)

### 2.3 网络要求

- 出站访问: Docker Hub、Maven Central、PyPI、npmmirror.com
- 入站端口 (IP 模式): 80、443、5173、5443
- 入站端口 (域名模式): 80、443

---

## 3. 一键部署流程

### 3.1 从零启动命令

```bash
chmod +x deploy.sh
./deploy.sh from-scratch
```

该命令自动执行以下流程:
1. **环境初始化**: 生成 `.env` 文件并填充强密钥
2. **部署前自检**: 校验 Docker、资源、端口、镜像源、构建产物
3. **证书占位**: 生成自签名证书 (IP 模式)
4. **构建产物准备**: Maven 打包 + 前端构建
5. **Docker 构建**: 全栈镜像构建
6. **服务启动**: 按依赖顺序启动所有容器
7. **健康验证**: 聚合健康状态 + 六链路冒烟测试

### 3.2 部署模式切换

#### IP 直连模式 (默认)

```bash
# .env 中保持 PUBLIC_HOST 为空
PUBLIC_HOST=
FRONTEND_PORT=5173

# 启动
./deploy.sh from-scratch
```

访问地址:
- 前端: `http://<宿主机 IP>:5173`
- Java: `http://<宿主机 IP>:8080`
- Python: `http://<宿主机 IP>:8000`

#### 域名模式 (HTTPS)

```bash
# .env 中设置域名
PUBLIC_HOST=app.example.com
FRONTEND_PORT=80

# 挂载证书到 certs/ 目录
mkdir -p certs
cp /path/to/fullchain.pem certs/
cp /path/to/privkey.pem certs/

# 启动
./deploy.sh from-scratch
```

访问地址: `https://app.example.com`

#### 演示模式 (调试用)

```bash
./deploy.sh demo
```

所有端口暴露到 `0.0.0.0`，便于外部调试。

#### 生产严格模式

```bash
./deploy.sh prod
```

强制要求手工输入强密码，拒绝默认口令。

---

## 4. 部署前自检项说明

执行 `./deploy.sh preflight` 输出如下表格:

| 检查项 | 状态 | 修复指引 |
|--------|------|----------|
| Docker 已安装 | PASS/FAIL | 未安装时执行 `curl -fsSL https://get.docker.com \| sh` |
| Docker daemon 运行中 | PASS/FAIL | `sudo systemctl start docker` |
| Docker Compose 可用 | PASS/FAIL | `sudo apt install docker-compose-plugin` |
| 可用内存 | PASS/WARN | 低于 10GB 时调整 `docker-compose.yml` 资源限制 |
| 可用磁盘空间 | PASS/WARN | 低于 50GB 时清理 `/var/lib/docker` |
| 端口占用 | PASS/FAIL | 冲突时修改 `.env` 中对应端口变量 |
| npm/PyPI/Docker Hub 连通性 | PASS/WARN | 网络不通时使用国内镜像源 |
| .env 必填项 | PASS/FAIL | 缺失时运行 `./deploy.sh init-env` |
| 证书文件 | PASS/FAIL | IP 模式自动生成，域名模式需手动挂载 |
| Java 构建产物 | PASS/WARN | 不存在时自动触发 Maven 构建 |
| 前端构建产物 | PASS/WARN | 不存在时自动触发前端构建 |

**注意**: 存在任一 FAIL 项时部署将中止，需先修复问题。

---

## 5. 健康检查与冒烟测试

### 5.1 健康检查端点

| 服务 | 端点 | 说明 |
|------|------|------|
| Java 后端 | `/api/health/ready` | 校验 DB + 连接池 + 下游依赖 |
| Python 后端 | `/api/health` | 基础健康检查 |
| 前端 | `/` | 透传至后端健康端点 |
| PostgreSQL | 内置健康检查 | 连接池探活 |
| Redis | `PING/PONG` | 内置健康检查 |
| MinIO | `/minio/health/live` | 内置健康检查 |
| RabbitMQ | `/api/health/checks/basic` | 内置健康检查 |

### 5.2 六链路冒烟测试

执行 `./deploy.sh verify` 或 `./deploy.sh from-scratch` 自动运行:

| 链路 | 测试内容 | 预期结果 |
|------|----------|----------|
| 链路 1: 登录鉴权 | POST `/api/auth/login` | 返回 JWT token |
| 链路 2: 数据视图 | GET `/api/data/views` | HTTP 200 |
| 链路 3: IM 后端 | GET `/api/ai/chat` | HTTP 200 |
| 链路 4: Slack 入站 | POST `/api/integrations/slack/webhook` | HTTP 200 (若未配置则 WARN) |
| 链路 5: 钉钉入站 | POST `/api/integrations/dingtalk/webhook` | HTTP 200 (若未配置则 WARN) |
| 链路 6: CRDT 服务 | WebSocket 连接 | 连接成功 |

**验收标准**: 至少 19 PASS、0 FAIL 视为部署成功。

---

## 6. 日常运维操作

### 6.1 状态查看

```bash
docker compose ps
```

输出示例:
```
NAME                    STATUS
nocobase-postgres       Up (healthy)
nocobase-redis          Up (healthy)
nocobase-backend-java   Up (healthy)
nocobase-frontend       Up (healthy)
```

### 6.2 日志跟随

```bash
# 查看所有服务日志
./deploy.sh logs

# 查看单个服务日志
docker compose logs -f backend-java

# 实时跟踪前端访问日志
docker compose logs -f frontend
```

### 6.3 优雅停止

```bash
./deploy.sh stop
```

### 6.4 版本回滚

```bash
./deploy.sh rollback
```

回滚逻辑:
1. 备份当前镜像 tag 到 `.deploy-tag`
2. 加载上一版本镜像
3. 重启容器
4. 验证健康检查

### 6.5 数据备份与恢复

#### 备份

```bash
# 全量备份 (含数据库 + 对象存储)
docker exec nocobase-postgres pg_dump -U nocobase nocobase > backup_$(date +%Y%m%d).sql
docker exec nocobase-minio mc mb -p backup/nocobase
docker exec nocobase-minio mc cp -r data/ backup/nocobase/data
```

#### 恢复

```bash
# 数据库恢复
cat backup_20241025.sql | docker exec -i nocobase-postgres psql -U nocobase

# MinIO 数据恢复
docker exec nocobase-minio mc cp -r backup/nocobase/data/ data/
```

### 6.6 数据卷清理 (危险操作)

```bash
# 警告：这将清空所有持久化数据！
docker compose down -v
rm -rf volumes/postgres-data volumes/redis-data volumes/minio-data
```

---

## 7. 故障排查

### 7.1 常见问题

#### Q1: `.env 不存在，部署失败`

**现象**: `docker-compose.yml: variable POSTGRES_PASSWORD is missing`

**解决**:
```bash
./deploy.sh init-env
```

#### Q2: `端口 80 已被占用`

**现象**: `Bind for 0.0.0.0:80 failed: port is already allocated`

**解决**:
```bash
# 查找占用进程
sudo lsof -i :80

# 修改 .env
FRONTEND_PORT=8081
```

#### Q3: `Docker Hub 拉取超时`

**现象**: `network timeout while pulling image`

**解决**:
```bash
# 配置国内镜像源
sudo mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<EOF
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://hub.rat.dev"
  ]
}
EOF
sudo systemctl restart docker
```

#### Q4: `前端构建失败: Cannot find module '@emotion/cache'`

**现象**: `ERROR: process "/bin/sh -c pnpm build" did not complete successfully`

**解决**:
```bash
cd frontend
pnpm add @emotion/cache @mui/system
pnpm install --lockfile-only
cd ..
./deploy.sh from-scratch
```

#### Q5: `Java 后端启动超时`

**现象**: `Java 后端在 90 秒内未就绪`

**原因**: JVM 预热不足或数据库连接池初始化慢

**解决**:
```bash
# 查看详细日志
docker compose logs backend-java | grep -i error

# 增加 JVM 堆内存
JAVA_OPTS="-Xms1g -Xmx2g" ./deploy.sh from-scratch
```

### 7.2 诊断工具

```bash
# 网络连通性测试
docker exec nocobase-backend-java curl -v http://localhost:5432

# 数据库连接测试
docker exec -it nocobase-postgres psql -U nocobase -c "SELECT 1"

# Redis 连接测试
docker exec -it nocobase-redis redis-cli ping

# 查看容器资源占用
docker stats nocobase-backend-java
```

---

## 8. 安全加固建议

### 8.1 生产环境必做

1. **更换默认密码**: 确保 `.env` 中所有 `CHANGEME_*` 已替换为随机强密码
2. **启用防火墙**: `ufw allow 80,443/tcp && ufw enable`
3. **配置 TLS**: 域名模式必须使用有效证书，禁用自签名
4. **关闭调试模式**: `DEBUG=false`
5. **限制数据库访问**: PostgreSQL 仅允许 `127.0.0.1` 连接

### 8.2 审计日志

```bash
# 开启结构化日志轮转
docker inspect --format='{{.LogPath}}' nocobase-backend-java

# 查看审计日志
tail -f /var/log/nocobase/backend-java.log
```

---

## 9. 附录

### 9.1 环境变量清单

| 变量名 | 必填 | 说明 | 示例 |
|--------|------|------|------|
| `POSTGRES_PASSWORD` | 是 | 数据库密码 | `Kj8#mP2$vL9@nQ4!` |
| `JWT_SECRET` | 是 | JWT 签名密钥 | `a1b2c3d4e5f6...` (64字节) |
| `MINIO_ROOT_PASSWORD` | 是 | MinIO 管理员密码 | `MinIOAdmin@2024!` |
| `RABBITMQ_PASSWORD` | 是 | RabbitMQ 密码 | `Rabbit@2024!` |
| `PUBLIC_HOST` | 否 | 域名模式时的域名 | `app.example.com` |
| `FRONTEND_PORT` | 否 | 前端暴露端口 | `80` (生产) / `5173` (开发) |

### 9.2 端口映射表

| 服务 | 容器端口 | 宿主机端口 (IP 模式) | 宿主机端口 (域名模式) |
|------|----------|---------------------|----------------------|
| Nginx 前端 | 80 | 5173 | 80 |
| Nginx 前端 (HTTPS) | 443 | 5443 | 443 |
| Java 后端 | 8080 | 8080 | 8080 (内部) |
| Python 后端 | 8000 | 8000 | 8000 (内部) |
| PostgreSQL | 5432 | 5432 | 5432 (内部) |
| Redis | 6379 | 6379 | 6379 (内部) |
| MinIO | 9000/9001 | 9000/9001 | 9000/9001 (内部) |
| RabbitMQ | 5672/15672 | 5672/15672 | 5672/15672 (内部) |

### 9.3 默认账号

| 角色 | 用户名 | 密码 | 说明 |
|------|--------|------|------|
| 管理员 | `admin` | `admin123` | 首次登录后立即修改 |
| PostgreSQL | `nocobase` | (见 `.env`) | 数据库管理员 |
| MinIO | `minioadmin` | (见 `.env`) | 对象存储管理员 |
| RabbitMQ | `guest` | (见 `.env`) | 消息队列管理员 |

---

## 10. 变更记录

| 日期 | 版本 | 变更内容 | 作者 |
|------|------|----------|------|
| 2024-10-25 | v1.0 | 初始版本，支持一键部署全流程 | Platform Team |

---

**文档维护**: 如有部署相关问题，请联系 `platform-team@example.com` 或查阅 GitHub Issues。
