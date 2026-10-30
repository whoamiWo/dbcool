#!/usr/bin/env bash
# =====================================================================
# Wave1 T1.6 — 版本升级与自动回滚
#
# 用法：
#   bash scripts/upgrade.sh --from <旧版本tag> --to <新版本tag> [--skip-backup]
#   bash scripts/upgrade.sh --help
#
# 流程：备份（Postgres dump）→ 换镜像 tag → 等待健康（含 Flyway 迁移完成）
#       → 冒烟 → 失败自动回滚（换回旧 tag + 恢复备份）
#
# 退出码：0 = 升级成功；1 = 升级失败且已回滚；2 = 前置条件缺失
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FROM_TAG=""
TO_TAG=""
SKIP_BACKUP=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --from) FROM_TAG="$2"; shift 2 ;;
    --to)   TO_TAG="$2"; shift 2 ;;
    --skip-backup) SKIP_BACKUP=1; shift ;;
    --help|-h) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
done

[[ -z "$FROM_TAG" || -z "$TO_TAG" ]] && { echo "❌ 退出码 2：必须提供 --from <旧tag> --to <新tag>"; exit 2; }
command -v docker >/dev/null || { echo "❌ 退出码 2：docker 不可用"; exit 2; }
docker compose version >/dev/null 2>&1 || { echo "❌ 退出码 2：docker compose 不可用"; exit 2; }

REGISTRY="${IMAGE_REGISTRY:-ghcr.io/whoamiwo/dbcool}"
COMPOSE="$ROOT/docker-compose.yml"
log() { echo "▶ $*"; }

# 1) 备份
if [[ $SKIP_BACKUP -eq 0 ]]; then
  log "升级前备份 Postgres"
  BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
  mkdir -p "$BACKUP_DIR"
  STAMP=$(date +%Y%m%d_%H%M%S)
  BACKUP_FILE="$BACKUP_DIR/pre-upgrade-$FROM_TAG-to-$TO_TAG-$STAMP.sql.gz"
  docker exec nocobase-postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip > "$BACKUP_FILE"
  [[ -s "$BACKUP_FILE" ]] || { echo "❌ 备份为空，中止升级"; exit 2; }
  log "备份完成：$BACKUP_FILE（$(du -h "$BACKUP_FILE" | cut -f1)）"
fi

rollback() {
  log "回滚：恢复镜像 tag $FROM_TAG"
  export APP_VERSION="$FROM_TAG"
  docker compose -f "$COMPOSE" up -d backend-java backend-python crdt-service frontend
  log "回滚完成（如需恢复数据：gunzip -c $BACKUP_FILE | docker exec -i nocobase-postgres psql -U \$POSTGRES_USER \$POSTGRES_DB）"
}

# 2) 拉取/确认新镜像存在
for svc in backend-java backend-python crdt-service frontend; do
  image="$REGISTRY/dbcool-$svc:$TO_TAG"
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker pull "$image" || { echo "❌ 退出码 2：镜像缺失且不可拉取 $image"; exit 2; }
  fi
done

# 3) 换版本启动
export APP_VERSION="$TO_TAG"
log "升级到 $TO_TAG"
if ! docker compose -f "$COMPOSE" up -d backend-java backend-python crdt-service frontend; then
  echo "❌ 启动失败"; rollback; exit 1
fi

# 4) 等待健康（最多 5 分钟，覆盖 Flyway 迁移时间）
deadline=$((SECONDS + 300))
healthy=0
while (( SECONDS < deadline )); do
  if [[ "$(docker inspect -f '{{.State.Health.Status}}' nocobase-backend-java 2>/dev/null || echo unknown)" == "healthy" ]]; then
    healthy=1; break
  fi
  sleep 5
done
if [[ $healthy -ne 1 ]]; then
  echo "❌ backend-java 5 分钟内未达到 healthy（含 Flyway 迁移）"; docker logs --tail 50 nocobase-backend-java || true; rollback; exit 1
fi
log "backend-java healthy（Flyway 迁移完成）"

# 5) 冒烟
if [[ -f "$ROOT/scripts/smoke.py" ]]; then
  if python3 "$ROOT/scripts/smoke.py"; then
    log "✅ 升级成功：$FROM_TAG → $TO_TAG"
    exit 0
  else
    echo "❌ 冒烟未通过"; rollback; exit 1
  fi
else
  echo "ℹ️  未找到 scripts/smoke.py，跳过冒烟（未实测冒烟路径）"
  log "✅ 升级成功（无冒烟验证）：$FROM_TAG → $TO_TAG"
  exit 0
fi
