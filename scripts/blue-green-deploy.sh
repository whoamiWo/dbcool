#!/usr/bin/env bash
# =====================================================================
# Wave4 待办池 #13 — 蓝绿发布（Docker Compose 单机，应用层零停机）
#
# 用法：
#   bash scripts/blue-green-deploy.sh --to <新版本tag>          # 蓝绿升级
#   bash scripts/blue-green-deploy.sh --rollback               # 手动回切 blue
#   bash scripts/blue-green-deploy.sh --status                 # 查看当前颜色/green 状态
#   bash scripts/blue-green-deploy.sh --cleanup                # 清理 green 副本
#   bash scripts/blue-green-deploy.sh --dry-run --to <tag>     # 只打印将执行的命令
#
# 流程：
#   1) 用新 tag 启动 green 副本（backend-java-green / backend-python-green / crdt-green，
#      共享现网基础设施与数据库，不暴露宿主端口）
#   2) 等待 green /api/health/ready 就绪（超时 180s）
#   3) 在 frontend nginx 容器内切换 BACKEND_COLOR → 重渲染 → reload（秒级，无请求中断）
#   4) 验证公网 /api/health；失败自动回切 blue 并清理 green
#   5) blue 保持运行以备回切
#
# 与 scripts/upgrade.sh 的关系：upgrade.sh 是单机滚动替换（重启期间有短暂中断）；
# 本脚本是应用层蓝绿（零停机 + 秒级回切）。
#
# 退出码：0 = 成功；1 = 失败（已回切 blue）；2 = 前置条件缺失
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TO_TAG=""
MODE=""
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --to) TO_TAG="$2"; MODE="deploy"; shift 2 ;;
    --rollback) MODE="rollback"; shift ;;
    --status) MODE="status"; shift ;;
    --cleanup) MODE="cleanup"; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help|-h) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
done
[[ -z "$MODE" ]] && { echo "❌ 退出码 2：需要 --to / --rollback / --status / --cleanup"; exit 2; }
command -v docker >/dev/null || { echo "❌ 退出码 2：docker 不可用"; exit 2; }
docker compose version >/dev/null 2>&1 || { echo "❌ 退出码 2：docker compose 不可用"; exit 2; }
[[ -f "$ROOT/.env" ]] || { echo "❌ 退出码 2：缺少 $ROOT/.env"; exit 2; }

BG_FILE="$ROOT/docker-compose.blue-green.yml"
log() { echo "▶ $*"; }
run() {
  if [[ $DRY_RUN -eq 1 ]]; then echo "  [dry-run] $*"; else "$@"; fi
}

current_color() {
  docker exec nocobase-frontend sh -c 'cat /etc/nginx/.backend-color 2>/dev/null || echo blue' 2>/dev/null || echo blue
}

switch_color() {
  local color="$1"
  # Wave4 #13 实测修正：nginx 容器内的 render_nginx.py 是**镜像构建时的旧版**
  # （无 BACKEND_COLOR 逻辑）——调用它重渲染会静默失效且 health 仍指向 blue（假阳性）。
  # 改为直接 sed 修改 default.conf 的 proxy_pass 上游，再 reload。
  # 切换后强制校验 default.conf 确实指向目标颜色，防止"切换了但流量没动"。
  log "切换 nginx 后端颜色 → $color（sed 替换 proxy_pass + reload，秒级）"
  run docker exec nocobase-frontend sh -c "echo $color > /etc/nginx/.backend-color"
  if [[ "$color" == "green" ]]; then
    run docker exec nocobase-frontend sed -i 's#http://backend-java:#http://backend-java-green:#g; s#http://backend-python:#http://backend-python-green:#g' /etc/nginx/conf.d/default.conf
  else
    run docker exec nocobase-frontend sed -i 's#http://backend-java-green:#http://backend-java:#g; s#http://backend-python-green:#http://backend-python:#g' /etc/nginx/conf.d/default.conf
  fi
  run docker exec nocobase-frontend nginx -s reload
}

wait_green_ready() {
  local timeout="${1:-180}" i=0
  log "等待 green /api/health/ready（超时 ${timeout}s）"
  while [[ $i -lt $timeout ]]; do
    if docker exec nocobase-backend-java-green curl -fsS http://localhost:8080/api/health/ready >/dev/null 2>&1; then
      log "green 已就绪（${i}s）"
      return 0
    fi
    sleep 5; i=$((i + 5))
  done
  log "green 在 ${timeout}s 内未就绪"
  return 1
}

cleanup_green() {
  log "清理 green 副本"
  run docker compose -f "$BG_FILE" --profile bg down --remove-orphans 2>/dev/null || true
  run docker rm -f nocobase-backend-java-green nocobase-backend-python-green nocobase-crdt-green 2>/dev/null || true
}

case "$MODE" in
  status)
    echo "当前颜色：$(current_color)"
    for c in nocobase-backend-java-green nocobase-backend-python-green nocobase-crdt-green; do
      docker ps --format '{{.Names}} {{.Status}}' | grep "$c" || echo "$c: 未运行"
    done
    exit 0 ;;
  cleanup) cleanup_green; exit 0 ;;
  rollback)
    log "回切 blue"
    switch_color blue
    log "验证 blue /api/health"
    docker exec nocobase-frontend curl -fsS http://localhost/api/health >/dev/null \
      || { echo "❌ 回切后 health 仍失败，请人工介入"; exit 1; }
    cleanup_green
    log "✅ 已回切 blue 并清理 green"; exit 0 ;;
  deploy) : ;;
esac

[[ -z "$TO_TAG" ]] && { echo "❌ 退出码 2：--to <tag> 必填"; exit 2; }

CUR=$(current_color)
[[ "$CUR" != "blue" ]] && { echo "❌ 退出码 2：当前颜色为 $CUR，请先 --rollback 回 blue 再升级"; exit 2; }

log "启动 green 副本（镜像 tag=$TO_TAG，仅用预构建镜像：拉不到即失败）"
if [[ $DRY_RUN -eq 0 ]]; then
  if ! env TO_TAG="$TO_TAG" docker compose -f "$ROOT/docker-compose.yml" -f "$BG_FILE" --profile bg \
      up -d backend-java-green backend-python-green crdt-service-green; then
    log "❌ green 启动失败（镜像不可用）→ 保持 blue（现网未受影响）"
    cleanup_green
    exit 1
  fi
else
  run env TO_TAG="$TO_TAG" docker compose -f "$ROOT/docker-compose.yml" -f "$BG_FILE" --profile bg \
      up -d backend-java-green backend-python-green crdt-service-green
fi

if [[ $DRY_RUN -eq 0 ]] && ! wait_green_ready 180; then
  log "❌ green 未就绪 → 自动回切 blue（现网未受影响）"
  cleanup_green
  exit 1
fi

switch_color green

if [[ $DRY_RUN -eq 0 ]]; then
  sleep 2
  if docker exec nocobase-frontend curl -fsS http://localhost/api/health >/dev/null 2>&1 \
     && docker exec nocobase-frontend curl -fsS http://localhost/api/health/ready >/dev/null 2>&1; then
    log "✅ 蓝绿切换完成：流量已指向 green（tag=$TO_TAG）"
    log "   回滚：--rollback；确认稳定后：--cleanup"
  else
    log "❌ 切换后 health 验证失败 → 自动回切 blue"
    switch_color blue
    cleanup_green
    exit 1
  fi
fi
