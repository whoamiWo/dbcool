#!/bin/bash
# ============================================================
#  NocoBase 一键安装 + 启动脚本
#  适用: Ubuntu 22.04+ / Debian 12+
#  用法:
#    chmod +x install.sh
#    ./install.sh all         # 全栈
#    ./install.sh backend     # 后端 + DB
#    ./install.sh java        # 只 Java + DB(最小可跑)
#    ./install.sh frontend    # 只前端
#    ./install.sh stop        # 停止
#    ./install.sh status      # 看状态
# ============================================================

set -e

CYAN='\033[36m'
GREEN='\033[32m'
YELLOW='\033[33m'
RED='\033[31m'
RESET='\033[0m'

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
DATE=$(date +%Y%m%d_%H%M%S)
LOG_FILE="/tmp/nocobase-install-${DATE}.log"

log() { echo -e "${CYAN}[$(date '+%H:%M:%S')]${RESET} $*" | tee -a "$LOG_FILE"; }
ok()  { echo -e "${GREEN}✅${RESET} $*" | tee -a "$LOG_FILE"; }
warn(){ echo -e "${YELLOW}⚠️${RESET}  $*" | tee -a "$LOG_FILE"; }
err() { echo -e "${RED}❌${RESET} $*" | tee -a "$LOG_FILE"; exit 1; }

detect_os() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        OS=$ID
    else
        err "无法检测操作系统"
    fi
    case $OS in
        ubuntu|debian) ;;
        *) err "不支持:$OS(本脚本只支持 Ubuntu/Debian)" ;;
    esac
    log "检测到系统:$OS"
}

check_cmd() {
    command -v "$1" &>/dev/null
}

docker_cmd() {
    if docker ps &>/dev/null 2>&1; then
        docker "$@"
    else
        sudo docker "$@"
    fi
}

install_jdk_maven() {
    if check_cmd java && check_cmd mvn; then
        ok "JDK 和 Maven 已安装"
        return 0
    fi
    log "安装 OpenJDK 21 + Maven..."
    sudo apt update -qq
    sudo apt install -y -qq openjdk-21-jdk maven curl wget gnupg
    JAVA_VER=$(java -version 2>&1 | head -1 | awk '{print $2}' | tr -d '"' | cut -d. -f1)
    if [ "$JAVA_VER" != "21" ]; then
        warn "apt 装的 JDK=$JAVA_VER,改用 Adoptium Temurin 21"
        wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg 2>/dev/null
        echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -sc) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
        sudo apt update -qq
        sudo apt install -y -qq temurin-21-jdk
    fi
    ok "JDK 21 + Maven 安装完成"
}

install_docker() {
    if check_cmd docker; then
        ok "Docker 已安装:$(docker --version)"
        return 0
    fi
    log "安装 Docker..."
    sudo apt install -y -qq docker.io docker-compose-plugin
    sudo systemctl start docker
    sudo systemctl enable docker
    sudo usermod -aG docker "$USER"
    warn "已将 $USER 加入 docker 组,新登录生效"
    ok "Docker 安装完成"
}

install_node_pnpm() {
    if check_cmd node && check_cmd pnpm; then
        ok "Node + pnpm 已安装"
        return 0
    fi
    if ! check_cmd node; then
        log "安装 Node.js 20..."
        if check_cmd curl; then
            curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
        fi
        sudo apt install -y -qq nodejs
    fi
    if ! check_cmd pnpm; then
        log "安装 pnpm..."
        sudo npm install -g pnpm@9
    fi
    pnpm config set registry https://registry.npmmirror.com 2>/dev/null || true
    ok "Node $(node --version) + pnpm $(pnpm --version) 安装完成"
}

install_python_tools() {
    if check_cmd uv; then
        ok "uv 已安装"
        return 0
    fi
    log "安装 uv..."
    curl -LsSf https://astral.sh/uv/install.sh | sh
    export PATH="$HOME/.local/bin:$PATH"
    ok "uv 安装完成"
}

configure_maven_mirror() {
    mkdir -p ~/.m2
    if [ ! -f ~/.m2/settings.xml ]; then
        log "配置 Maven 阿里云镜像..."
        cat > ~/.m2/settings.xml <<'EOF'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF
        ok "Maven 镜像已配置"
    fi
}

start_infra() {
    cd "$PROJECT_ROOT"
    log "启动基础设施..."
    [ ! -f .env ] && cp .env.example .env
    docker_cmd compose up -d postgres redis minio
    log "等待 Postgres..."
    for i in {1..30}; do
        if docker_cmd exec postgres pg_isready -U "${POSTGRES_USER:-nocobase}" &>/dev/null; then
            ok "Postgres ready"
            break
        fi
        sleep 2
    done
    ok "基础设施已启动"
}

start_java() {
    cd "$PROJECT_ROOT"
    log "启动 Java 后端..."
    cd backend-java
    nohup mvn spring-boot:run > /tmp/nocobase-java.log 2>&1 &
    echo $! > /tmp/nocobase-java.pid
    cd "$PROJECT_ROOT"

    log "等待 Java 启动(Flyway migration + 下载依赖可能要 1~2 分钟)..."
    for i in {1..80}; do
        if curl -sf http://localhost:8080/api/health &>/dev/null; then
            ok "Java ready (PID: $(cat /tmp/nocobase-java.pid))"
            return 0
        fi
        sleep 3
    done
    err "Java 未启动,查看 /tmp/nocobase-java.log 最后 30 行"
    print_faq
}

# ============================================================
#  失败提示(FAQ)
# ============================================================
print_faq() {
    echo ""
    echo -e "${YELLOW}💡 常见原因 + 解决办法:${RESET}"
    echo ""
    echo "  1) Postgres 没起来(最常见)"
    echo "     docker ps | grep postgres"
    echo "     docker logs nocobase-postgres"
    echo ""
    echo "  2) 8080 端口被占"
    echo "     sudo lsof -i :8080"
    echo ""
    echo "  3) mvn 下载依赖慢(第一次要 5~10 分钟)"
    echo "     tail -f /tmp/nocobase-java.log 看进度"
    echo "     如果长时间无下载,检查 ~/.m2/settings.xml 阿里云镜像"
    echo ""
    echo "  4) Flyway migration 失败(SQL 语法错)"
    echo "     tail -50 /tmp/nocobase-java.log | grep -i 'flyway\\|migration'"
    echo ""
    echo "  5) JWT secret 太短"
    echo "     检查 .env 里的 JWT_SECRET(≥32 字符)"
    echo ""
    echo "  6) 完全重置"
    echo "     ./install.sh stop && docker compose down -v && ./install.sh java"
    echo ""
    echo "  7) 跑验证脚本看具体哪些 API 失败"
    echo "     ./verify.sh"
    echo ""
    echo "完整 FAQ:TROUBLESHOOTING.md"
}

start_python() {
    cd "$PROJECT_ROOT"
    log "启动 Python 后端..."
    export PATH="$HOME/.local/bin:$PATH"
    if ! check_cmd uv; then
        install_python_tools
    fi
    cd backend-python
    [ ! -d .venv ] && uv sync --all-extras
    nohup uv run uvicorn nocobase_py.main:app --reload --port 8000 > /tmp/nocobase-python.log 2>&1 &
    echo $! > /tmp/nocobase-python.pid
    cd "$PROJECT_ROOT"
    log "等待 Python 启动..."
    for i in {1..30}; do
        if curl -sf http://localhost:8000/api/health &>/dev/null; then
            ok "Python ready (PID: $(cat /tmp/nocobase-python.pid))"
            return 0
        fi
        sleep 2
    done
    warn "Python 未启动,查看 /tmp/nocobase-python.log"
}

start_frontend() {
    cd "$PROJECT_ROOT"
    log "启动前端..."
    cd frontend
    [ ! -d node_modules ] && pnpm install --registry=https://registry.npmmirror.com
    nohup pnpm dev > /tmp/nocobase-frontend.log 2>&1 &
    echo $! > /tmp/nocobase-frontend.pid
    cd "$PROJECT_ROOT"
    log "等待 Vite..."
    for i in {1..30}; do
        if curl -sf http://localhost:5173/ &>/dev/null; then
            ok "Frontend ready (PID: $(cat /tmp/nocobase-frontend.pid))"
            return 0
        fi
        sleep 2
    done
    warn "前端未启动,查看 /tmp/nocobase-frontend.log"
}

cmd_all() {
    log "=== 一键启动全栈 ==="
    detect_os
    install_jdk_maven
    install_docker
    install_node_pnpm
    configure_maven_mirror
    start_infra
    start_java
    start_python
    start_frontend
    echo ""
    ok "全栈启动完成!"
    echo ""
    echo -e "${CYAN}访问地址:${RESET}"
    echo "  前端:http://localhost:5173"
    echo "  Java:http://localhost:8080"
    echo "  Python:http://localhost:8000"
    echo -e "${CYAN}账号:${RESET} admin / admin123"
    echo -e "${CYAN}停止:${RESET} $0 stop"
}

cmd_backend() {
    log "=== 启动后端 ==="
    detect_os
    install_jdk_maven
    install_docker
    install_python_tools
    configure_maven_mirror
    start_infra
    start_java
    start_python
    ok "后端启动完成"
}

cmd_java() {
    log "=== 最小可跑方案:Java + DB ==="
    detect_os
    install_jdk_maven
    install_docker
    configure_maven_mirror
    start_infra
    start_java
    ok "Java + DB 启动完成"
    echo ""
    echo -e "${CYAN}验证命令:${RESET}"
    echo "  curl http://localhost:8080/api/health"
    echo ""
    echo "  TOKEN=\$(curl -s -X POST http://localhost:8080/api/auth/login \\"
    echo "    -H 'Content-Type: application/json' \\"
    echo "    -d '{\"username\":\"admin\",\"password\":\"admin123\"}' | jq -r '.data.access_token')"
    echo ""
    echo "  curl -X POST http://localhost:8080/api/collections \\"
    echo "    -H \"Authorization: Bearer \$TOKEN\" \\"
    echo "    -H 'Content-Type: application/json' \\"
    echo "    -d '{\"name\":\"customer\",\"title\":\"客户\",\"fields\":[{\"name\":\"name\",\"type\":\"text\"}]}'"
}

cmd_frontend() {
    log "=== 启动前端 ==="
    detect_os
    install_node_pnpm
    start_frontend
    ok "前端启动完成"
}

cmd_status() {
    echo -e "${CYAN}=== 服务状态 ===${RESET}"
    if check_cmd docker; then
        echo ""
        echo "Docker 容器:"
        docker_cmd ps --format "  - {{.Names}}\t{{.Status}}" 2>/dev/null | head -10
    fi
    echo ""
    echo "健康检查:"
    curl -sf -m 2 http://localhost:8080/api/health &>/dev/null && echo "  Java   /api/health  ✅" || echo "  Java   /api/health  ❌"
    curl -sf -m 2 http://localhost:8000/api/health &>/dev/null && echo "  Python /api/health  ✅" || echo "  Python /api/health  ❌"
    curl -sf -m 2 http://localhost:5173/ &>/dev/null && echo "  Frontend            ✅" || echo "  Frontend            ❌"
}

cmd_stop() {
    log "=== 停止所有服务 ==="
    [ -f /tmp/nocobase-java.pid ] && kill "$(cat /tmp/nocobase-java.pid)" 2>/dev/null && ok "Java stopped"
    [ -f /tmp/nocobase-python.pid ] && kill "$(cat /tmp/nocobase-python.pid)" 2>/dev/null && ok "Python stopped"
    [ -f /tmp/nocobase-frontend.pid ] && kill "$(cat /tmp/nocobase-frontend.pid)" 2>/dev/null && ok "Frontend stopped"
    if check_cmd docker; then
        docker_cmd compose stop 2>/dev/null && ok "Docker containers stopped"
    fi
    rm -f /tmp/nocobase-*.pid
    ok "停止完成"
}

case "${1:-}" in
    all)        cmd_all ;;
    backend)    cmd_backend ;;
    java)       cmd_java ;;
    frontend)   cmd_frontend ;;
    status)     cmd_status ;;
    stop)       cmd_stop ;;
    *)
        echo "用法:$0 {all|backend|java|frontend|status|stop}"
        echo ""
        echo "推荐:首次运行 $0 java(最小可跑,30 分钟内能验证 Java)"
        echo "      验证后 $0 all(全栈)"
        exit 1
        ;;
esac
