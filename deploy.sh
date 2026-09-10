#!/bin/bash
# ============================================================
#  NocoBase 一键部署到 Docker
#  支持: 生产 / 演示 两种模式
#  用法:
#    chmod +x deploy.sh
#    ./deploy.sh prod         # 生产模式
#    ./deploy.sh demo         # 演示模式(暴露所有端口)
#    ./deploy.sh stop         # 停止
#    ./deploy.sh logs         # 看日志
# ============================================================

set -e

CYAN='\033[36m'
GREEN='\033[32m'
YELLOW='\033[33m'
RED='\033[31m'
RESET='\033[0m'

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
DATE=$(date +%Y%m%d_%H%M%S)

log() { echo -e "${CYAN}[$(date '+%H:%M:%S')]${RESET} $*" | tee -a /tmp/nocobase-deploy.log; }
ok()  { echo -e "${GREEN}✅${RESET} $*"; }
warn(){ echo -e "${YELLOW}⚠️${RESET}  $*"; }
err() { echo -e "${RED}❌${RESET} $*"; exit 1; }

# ============================================================
#  检查环境
# ============================================================
check_docker() {
    if ! command -v docker &>/dev/null; then
        err "Docker 未安装。安装: curl -fsSL https://get.docker.com | sh"
    fi
    if ! docker ps &>/dev/null 2>&1; then
        if ! sudo docker ps &>/dev/null 2>&1; then
            err "Docker daemon 未运行或无权限"
        fi
    fi
}

docker_cmd() {
    if docker ps &>/dev/null 2>&1; then
        docker "$@"
    else
        sudo docker "$@"
    fi
}

# ============================================================
#  生产模式
# ============================================================
deploy_prod() {
    log "=== 生产模式部署 ==="
    check_docker
    [ ! -f .env ] && cp .env.example .env

    # 生产环境需强密码
    if grep -q "dev_password" .env 2>/dev/null; then
        warn "检测到 .env 仍用默认密码,生产前请修改!"
        warn "  POSTGRES_PASSWORD=你的强密码"
        warn "  JWT_SECRET=32+ 字符随机"
    fi

    log "启动全栈(包含前端 nginx)..."
    docker_cmd compose up -d --build

    echo ""
    ok "生产部署完成!"
    echo ""
    echo -e "${CYAN}访问:${RESET}"
    echo "  http://你的服务器IP  (前端 + 反代)"
    echo ""
    echo -e "${CYAN}健康检查:${RESET}"
    sleep 10
    curl -sf -m 3 http://localhost/api/health 2>/dev/null && echo "  ✅ /api/health 通" || echo "  ❌ /api/health 不通"
}

# ============================================================
#  演示模式
# ============================================================
deploy_demo() {
    log "=== 演示模式部署 ==="
    check_docker
    [ ! -f .env ] && cp .env.example .env

    log "启动全栈(暴露所有端口便于调试)..."
    docker_cmd compose up -d --build

    echo ""
    ok "演示部署完成!"
    echo ""
    echo -e "${CYAN}访问:${RESET}"
    echo "  前端:    http://localhost:5173"
    echo "  Java:    http://localhost:8080"
    echo "  Python:  http://localhost:8000"
    echo "  Postgres:localhost:5432  (nocobase/dev_password)"
    echo "  MinIO:   http://localhost:9001  (minio/minio123)"
    echo ""
    echo -e "${CYAN}账号:${RESET} admin / admin123"
}

# ============================================================
#  停止
# ============================================================
stop_all() {
    log "停止所有服务..."
    cd "$PROJECT_ROOT"
    docker_cmd compose stop 2>/dev/null || true
    ok "停止完成"
}

# ============================================================
#  日志
# ============================================================
show_logs() {
    cd "$PROJECT_ROOT"
    docker_cmd compose logs -f --tail=100
}

# ============================================================
#  入口
# ============================================================
case "${1:-}" in
    prod)  deploy_prod ;;
    demo)  deploy_demo ;;
    stop)  stop_all ;;
    logs)  show_logs ;;
    *)
        echo "用法: $0 {prod|demo|stop|logs}"
        echo ""
        echo "  prod   - 生产模式(用 nginx 反代,仅暴露 80/443)"
        echo "  demo   - 演示模式(暴露所有端口,便于调试)"
        echo "  stop   - 停止所有服务"
        echo "  logs   - 查看日志"
        exit 1
        ;;
esac
