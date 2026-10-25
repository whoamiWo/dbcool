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
error() { echo -e "${RED}❌${RESET} $*"; }

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

    # PHASE104 审计修正：prometheus 必须一并启动。
    # Grafana 数据源 url 为 http://prometheus:9090，而 prometheus 只定义在
    # docker-compose.observability.yml（主 compose 无此服务），且三服务间无
    # depends_on，故不显式拉起则数据源不可达、面板无数据。
    log "启动可观测性栈 (Prometheus + AlertManager + Grafana)..."
    if [ -f docker-compose.observability.yml ]; then
        docker_cmd compose -f docker-compose.yml -f docker-compose.observability.yml up -d prometheus alertmanager grafana
        ok "可观测性栈已启动"
        echo ""
        echo -e "${CYAN}监控访问:${RESET}"
        echo "  Grafana:    http://localhost:${GRAFANA_PORT:-3000}"
        echo "  Prometheus: http://localhost:${PROMETHEUS_PORT:-9090}"
        echo "  AlertMgr:   http://localhost:${ALERTMANAGER_PORT:-9093}"
    else
        warn "docker-compose.observability.yml 不存在，跳过可观测性栈"
    fi

    echo ""
    ok "生产部署完成! (tag=$IMAGE_TAG)"

    # PHASE114: 根据 PUBLIC_HOST 决定访问提示与健康检查协议
    # 域名模式(PUBLIC_HOST 有值) → https://PUBLIC_HOST + 绑定 127.0.0.1
    # IP 模式(PUBLIC_HOST 为空) → http://0.0.0.0:${FRONTEND_PORT} + 明文安全提醒
    if [ -n "${PUBLIC_HOST:-}" ]; then
        HOST_PROTO="https"
        HOST_ADDR="${PUBLIC_HOST}"
        ACCESS_URL="https://${PUBLIC_HOST}"
        BIND_HINT="端口绑定 127.0.0.1(需外层 LB/防火墙放行)"
        echo ""
        echo -e "${CYAN}访问:${RESET}"
        echo "  ${ACCESS_URL}  (前端 + 反代, HTTPS)"
        echo "  ${BIND_HINT}"
    else
        HOST_PROTO="http"
        HOST_ADDR="${FRONTEND_BIND_ADDR:-127.0.0.1}"
        # IP 模式绑定到本机访问地址(默认 127.0.0.1; 0.0.0.0 表示对外)
        if [ "${FRONTEND_BIND_ADDR:-127.0.0.1}" = "0.0.0.0" ]; then
            IP_ADDR=$(hostname -I 2>/dev/null | awk '{print $1}')
            [ -z "$IP_ADDR" ] && IP_ADDR="<服务器IP>"
        else
            IP_ADDR="${HOST_ADDR}"
        fi
        ACCESS_URL="http://${IP_ADDR}:${FRONTEND_PORT:-80}"
        BIND_HINT="端口绑定 ${FRONTEND_BIND_ADDR:-127.0.0.1}"
        echo ""
        echo -e "${CYAN}访问:${RESET}"
        echo "  ${ACCESS_URL}  (前端 + 反代, HTTP 明文)"
        echo "  ${BIND_HINT}"
        echo ""
        warn "  当前为 IP 直连 HTTP 明文模式(未启用 TLS),仅建议内网使用!"
        warn "  生产环境建议配置域名 + 证书切换为 HTTPS: PUBLIC_HOST=你的域名"
    fi

    echo ""
    echo -e "${CYAN}健康检查:${RESET}"
    sleep 10
    # PHASE100 T3/T8 + PHASE114: 真实探活 + 失败必须非 0 退出
    # 按模式选协议: 域名 https, IP http (本机探测走 127.0.0.1)
    if [ "${HOST_PROTO}" = "https" ]; then
        if curl -sk -m 5 https://127.0.0.1:${FRONTEND_TLS_PORT:-443}/api/health >/dev/null 2>&1; then
            ok "  /api/health 通 (HTTPS)"
        elif curl -sf -m 5 http://127.0.0.1:${FRONTEND_PORT:-80}/api/health >/dev/null 2>&1; then
            ok "  /api/health 通 (HTTP 兜底)"
        else
            err "  /api/health 不通 —— 部署失败,请执行 ./deploy.sh rollback 回滚"
        fi
    else
        if curl -sf -m 5 http://127.0.0.1:${FRONTEND_PORT:-80}/api/health >/dev/null 2>&1; then
            ok "  /api/health 通 (HTTP)"
        else
            err "  /api/health 不通 —— 部署失败,请执行 ./deploy.sh rollback 回滚"
        fi
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

    # 验证回滚目标不是空值
    if [ -z "$PREV_TAG" ]; then
        err ".last-deployed-tag 为空,无法回滚"
    fi

    # 记录当前正在运行的 tag,便于回滚失败时对比
    CURRENT_TAG=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
    log "当前运行 tag: $CURRENT_TAG → 回滚到: $PREV_TAG"

    # 用上一个 tag 重新拉起(镜像已在本机则秒起)
    IMAGE_TAG="$PREV_TAG" docker_cmd compose up -d

    # 等待回滚完成并做带重试的健康检查(首次 10s 可能仍在拉镜像/启动中)
    local max_attempts=6 attempt=1 health_ok=false
    echo ""
    echo -e "${CYAN}健康检查(最多重试 ${max_attempts} 次,每次间隔 10s):${RESET}"
    while [ $attempt -le $max_attempts ]; do
        if curl -sf -m 5 http://localhost/api/health >/dev/null 2>&1 \
           || curl -sf -m 5 https://localhost/api/health -k >/dev/null 2>&1; then
            health_ok=true
            break
        fi
        log "  第 ${attempt}/${max_attempts} 次健康检查未通过,10s 后重试..."
        sleep 10
        attempt=$((attempt + 1))
    done

    if [ "$health_ok" = true ]; then
        ok "回滚成功: tag=$PREV_TAG, /api/health 通"
    else
        # 回滚失败:尝试再拉起,但保持 PREV_TAG 不变
        err "回滚后 /api/health 仍未通,请人工介入"
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

    # PHASE104 审计修正：deploy_demo 此前完全没有可观测性块
    # （原 deploy_prod 内那段重复代码的注释写着"演示模式"，但其实一直留在 prod 里）。
    # 此处补上，并显式拉起 prometheus 以保证 Grafana 数据源可达。
    log "启动可观测性栈 (Prometheus + AlertManager + Grafana)..."
    if [ -f docker-compose.observability.yml ]; then
        docker_cmd compose -f docker-compose.yml -f docker-compose.observability.yml up -d prometheus alertmanager grafana
        ok "可观测性栈已启动"
        echo ""
        echo -e "${CYAN}监控访问:${RESET}"
        echo "  Grafana:    http://localhost:${GRAFANA_PORT:-3000}"
        echo "  Prometheus: http://localhost:${PROMETHEUS_PORT:-9090}"
        echo "  AlertMgr:   http://localhost:${ALERTMANAGER_PORT:-9093}"
    else
        warn "docker-compose.observability.yml 不存在，跳过可观测性栈"
    fi

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
#  初始化环境变量
# ============================================================
init_env() {
    log "检查 .env 文件..."
    cd "$PROJECT_ROOT"

    if [ ! -f .env ]; then
        log ".env 不存在，从 .env.example 生成..."
        python3 scripts/init-env.py
        if [ $? -ne 0 ]; then
            error ".env 生成失败"
            return 1
        fi
        ok ".env 已生成"
    else
        log ".env 已存在，跳过生成"
        if grep -q "CHANGEME" .env; then
            warn "发现 CHANGEME 占位符，建议重新生成: python3 scripts/init-env.py --force"
        else
            ok ".env 配置完整"
        fi
    fi
}

# ============================================================
#  部署前自检
# ============================================================
preflight_check() {
    log "运行部署前自检..."
    cd "$PROJECT_ROOT"

    python3 scripts/preflight.py
    local rc=$?

    if [ $rc -eq 0 ]; then
        ok "自检通过"
        return 0
    else
        error "自检失败，请修复问题后重试"
        return 1
    fi
}

# ============================================================
#  生成自签名证书
# ============================================================
gen_cert() {
    log "检查证书..."
    cd "$PROJECT_ROOT"

    bash scripts/gen-selfsigned-cert.sh
    if [ $? -ne 0 ]; then
        error "证书生成失败"
        return 1
    fi
    ok "证书就绪"
}

# ============================================================
#  准备构建产物
# ============================================================
prepare_build_artifacts() {
    log "检查构建产物..."
    cd "$PROJECT_ROOT"

    if [ ! -f "backend-java/target/nocobase-backend-0.0.1-SNAPSHOT.jar" ]; then
        log "Java jar 不存在，执行 Maven 构建..."
        make build-java || { error "Maven 构建失败"; return 1; }
        ok "Maven 构建完成"
    else
        log "Java jar 已存在"
    fi

    if [ ! -d "frontend/dist" ] || [ -z "$(ls -A frontend/dist 2>/dev/null)" ]; then
        log "前端 dist 为空，执行构建..."
        make build-frontend || { error "前端构建失败"; return 1; }
        ok "前端构建完成"
    else
        log "前端 dist 已存在"
    fi
}

# ============================================================
#  Docker 构建与启动
# ============================================================
docker_up() {
    log "构建 Docker 镜像..."
    cd "$PROJECT_ROOT"

    docker_cmd compose build
    if [ $? -ne 0 ]; then
        error "Docker 构建失败"
        return 1
    fi
    ok "Docker 构建完成"

    log "启动服务..."
    docker_cmd compose up -d
    if [ $? -ne 0 ]; then
        error "Docker 启动失败"
        return 1
    fi
    ok "服务已启动"

    log "等待 Java 后端就绪..."
    local retries=45
    while [ $retries -gt 0 ]; do
        if curl -sf http://localhost:8080/api/health/ready >/dev/null 2>&1; then
            ok "Java 后端就绪"
            return 0
        fi
        retries=$((retries - 1))
        sleep 2
    done

    error "Java 后端在 90 秒内未就绪"
    return 1
}

# ============================================================
#  部署验证
# ============================================================
verify_deploy() {
    log "运行部署验证..."
    cd "$PROJECT_ROOT"

    if [ -f "scripts/verify-deploy.py" ]; then
        python3 scripts/verify-deploy.py
        return $?
    fi

    log "verify-deploy.py 不存在，执行基本健康检查"
    docker_cmd compose ps --format "table {{.Name}}\t{{.Status}}"

    if curl -sf http://localhost:8080/api/health/ready >/dev/null 2>&1; then
        ok "Java 后端健康"
    else
        error "Java 后端不健康"
        return 1
    fi

    if curl -sf http://localhost:8000/api/health >/dev/null 2>&1; then
        ok "Python 后端健康"
    else
        warn "Python 后端未响应"
    fi

    ok "基本健康检查通过"
    return 0
}

# ============================================================
#  一键部署（从零开始）
# ============================================================
deploy_from_scratch() {
    log "=========================================="
    log "  一键部署（从零开始）"
    log "=========================================="

    init_env || return 1
    preflight_check || return 1
    gen_cert || return 1
    prepare_build_artifacts || return 1
    docker_up || return 1
    verify_deploy || return 1

    echo ""
    ok "=========================================="
    ok "  部署成功"
    ok "=========================================="
    echo ""
    echo -e "${CYAN}访问地址:${RESET}"
    echo "  前端:      http://localhost:5173"
    echo "  Java:      http://localhost:8080"
    echo "  Python:    http://localhost:8000"
    echo "  MinIO:     http://localhost:9001"
    echo ""
    echo -e "${CYAN}账号:${RESET} admin / admin123"
}

# ============================================================
#  入口
# ============================================================
case "${1:-}" in
    init-env)    init_env ;;
    preflight)   preflight_check ;;
    gen-cert)    gen_cert ;;
    up)          docker_up ;;
    verify)      verify_deploy ;;
    from-scratch) deploy_from_scratch ;;
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
