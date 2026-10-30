#!/usr/bin/env bash
# =====================================================================
# Wave1 T1.3 — 离线安装脚本（断网环境可用）
#
# 用法：
#   tar -xzf dbcool-offline-<version>.tar.gz && cd offline
#   ./install-offline.sh            # 安装并启动
#   ./install-offline.sh --verify   # 只做校验（不启动）
#
# 步骤：校验 SHA256 → docker load 镜像 → 生成 .env → docker compose up -d
#       → 等待健康 → 调用 scripts/smoke.py 冒烟
#
# 退出码：0 = 成功；2 = 前置条件缺失（docker / compose / 校验和不符）
# =====================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

VERIFY_ONLY=0
[[ "${1:-}" == "--verify" ]] && VERIFY_ONLY=1

log() { echo "▶ $*"; }
fail() { echo "❌ $*" >&2; exit 2; }

command -v docker >/dev/null || fail "docker 不可用（离线包需要本机已有 Docker Engine 20.10+）"
docker compose version >/dev/null 2>&1 || fail "docker compose 插件不可用"

# 1) 校验和
[[ -f SHA256SUMS ]] || fail "缺少 SHA256SUMS（包不完整）"
log "校验文件完整性"
sha256sum -c SHA256SUMS || fail "SHA256 校验失败 —— 包已损坏，禁止继续安装"

# 2) 环境变量
if [[ ! -f .env ]]; then
  log "生成 .env（来自 .env.example）"
  cp .env.example .env
  if [[ -z "${NON_INTERACTIVE:-}" ]]; then
    echo "⚠️  请编辑 $HERE/.env，至少设置 POSTGRES_PASSWORD / MINIO_ROOT_PASSWORD / JWT_SECRET / INTERNAL_SERVICE_TOKEN"
    echo "   （无人值守安装请设置 NON_INTERACTIVE=1 并预置 .env）"
    exit 0
  fi
fi

if [[ $VERIFY_ONLY -eq 1 ]]; then
  log "校验通过（--verify 模式，未启动）"
  exit 0
fi

# 3) 载入镜像
log "加载镜像 images.tar"
docker load -i images.tar

# 4) 启动
log "启动服务"
docker compose -f docker-compose.offline.yml --env-file .env up -d

# 5) 等待健康（最多 5 分钟）
log "等待服务健康"
deadline=$((SECONDS + 300))
while (( SECONDS < deadline )); do
  running=$(docker compose -f docker-compose.offline.yml --env-file .env ps --services --filter status=running 2>/dev/null | wc -l)
  total=$(docker compose -f docker-compose.offline.yml --env-file .env config --services 2>/dev/null | wc -l)
  if [[ "$running" -ge "$total" && "$total" -gt 0 ]]; then
    log "全部 $running 个服务已运行"
    break
  fi
  sleep 5
done

# 6) 冒烟（脚本不存在则跳过，不谎报）
if [[ -f ../scripts/smoke.py ]]; then
  log "执行冒烟"
  python3 ../scripts/smoke.py || echo "⚠️ 冒烟未全部通过，请查看日志"
else
  echo "ℹ️  未找到 scripts/smoke.py（离线包内不含），跳过冒烟"
fi

echo "✅ 安装完成。请访问 http://localhost:8080"
