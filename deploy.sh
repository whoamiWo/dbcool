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

    # 生产环境必须强密码,检测到默认口令直接阻断
    if grep -qE "(dev_password|minio123|guest)" .env 2>/dev/null; then
        echo "❌ 错误:.env 中仍包含默认口令,生产部署被阻断!" >&2
        echo "   请修改以下项:" >&2
        echo "   - POSTGRES_PASSWORD=强密码(至少 16 位随机)" >&2
        echo "   - MINIO_ROOT_PASSWORD=强密码(至少 16 位随机)" >&2
        echo "   - RABBITMQ_PASSWORD=强密码(guest 用户禁止远程连接)" >&2
        exit 1
    fi

    # PHASE100 T1: 必须显式提供集成凭证加密主密钥(PHASE99 红线 1)
    if ! grep -qE "^INTEGRATION_CREDENTIAL_MASTER_KEY=.+" .env 2>/dev/null; then
        echo "❌ 错误:.env 未配置 INTEGRATION_CREDENTIAL_MASTER_KEY,生产部署被阻断!" >&2
        echo "   生成方式: openssl rand -base64 32" >&2
        exit 1
    fi

    # PHASE100 T8: 记录当前版本(用于回滚)
    IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD 2>/dev/null || date +%Y%m%d_%H%M%S)}"
    echo "$IMAGE_TAG" > .last-deployed-tag
    log "本次部署镜像 tag: $IMAGE_TAG"

    log "启动全栈(包含前端 nginx)..."
    docker_cmd compose up -d --build

    echo ""
    ok "生产部署完成! (tag=$IMAGE_TAG)"
    echo ""
    echo -e "${CYAN}访问:${RESET}"
    echo "  https://你的域名  (前端 + 反代)"
    echo ""
    echo -e "${CYAN}健康检查:${RESET}"
    sleep 10
    # PHASE100 T3/T8: 真实探活 + 失败必须非 0 退出(不再 || echo 掩盖)
    if curl -sf -m 5 https://localhost/api/health >/dev/null 2>&1; then
        ok "  /api/health 通 (HTTPS)"
    elif curl -sf -m 5 http://localhost/api/health >/dev/null 2>&1; then
        ok "  /api/health 通 (HTTP)"
    else
        err "  /api/health 不通 —— 部署失败,请执行 ./deploy.sh rollback 回滚"
    fi
}

# ============================================================
#  回滚到上一个已知可用版本（PHASE100 T8）
# ============================================================
rollback() {
    log "=== 回滚到上一个版本 ==="
    check_docker

    if [ ! -f .last-deployed-tag ]; then
        err "没有找到 .last-deployed-tag,无法回滚"
    fi

    PREV_TAG=$(cat .last-deployed-tag)
    log "回滚目标 tag: $PREV_TAG"

    # 用上一个 tag 重新拉起(镜像已在本机则秒起)
    IMAGE_TAG="$PREV_TAG" docker_cmd compose up -d

    echo ""
    ok "已回滚到 tag=$PREV_TAG"
    echo ""
    echo -e "${CYAN}健康检查:${RESET}"
    sleep 10
    if curl -sf -m 5 http://localhost/api/health >/dev/null 2>&1; then
        ok "  /api/health 通"
    else
        err "  回滚后 /api/health 仍不通,请人工介入"
    fi
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
    rollback) rollback ;;
    *)
        echo "用法: $0 {prod|demo|stop|logs|rollback}"
        echo ""
        echo "  prod     - 生产模式(用 nginx 反代,仅暴露 80/443)"
        echo "  demo     - 演示模式(暴露所有端口,便于调试)"
        echo "  stop     - 停止所有服务"
        echo "  logs     - 查看日志"
        echo "  rollback - 回滚到上一个已知可用版本"
        exit 1
        ;;
esac
