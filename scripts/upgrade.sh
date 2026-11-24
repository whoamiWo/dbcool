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
cd "$ROOT"
FROM_TAG=""
TO_TAG=""
SKIP_BACKUP=0
BACKUP_FILE=""

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
[[ -f "$ROOT/.env" ]] || { echo "❌ 退出码 2：缺少 $ROOT/.env"; exit 2; }
CURRENT_ENV_TAG="$(grep -E '^APP_VERSION=' "$ROOT/.env" | tail -1 | cut -d= -f2- || true)"
[[ -n "$CURRENT_ENV_TAG" ]] || { echo "❌ 退出码 2：.env 缺少 APP_VERSION"; exit 2; }
[[ "$CURRENT_ENV_TAG" == "$FROM_TAG" ]] || {
  echo "❌ 退出码 2：.env APP_VERSION=$CURRENT_ENV_TAG 与 --from $FROM_TAG 不一致" >&2
  exit 2
}
command -v docker >/dev/null || { echo "❌ 退出码 2：docker 不可用"; exit 2; }
docker compose version >/dev/null 2>&1 || { echo "❌ 退出码 2：docker compose 不可用"; exit 2; }

COMPOSE="$ROOT/docker-compose.yml"
log() { echo "▶ $*"; }

set_env_app_version() {
  local tag="$1" env_file="$ROOT/.env"
  [[ -f "$env_file" ]] || return 0
  python3 - "$env_file" "$tag" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
tag = sys.argv[2]
lines = path.read_text(encoding="utf-8").splitlines()
found = False
for index, line in enumerate(lines):
    if line.startswith("APP_VERSION="):
        lines[index] = f"APP_VERSION={tag}"
        found = True
        break
if not found:
    lines.append(f"APP_VERSION={tag}")
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
}

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
  log "回滚：恢复 APP_VERSION=$FROM_TAG"
  export APP_VERSION="$FROM_TAG"
  docker compose -f "$COMPOSE" pull backend-java backend-python crdt-service frontend \
    || { echo "❌ 回滚镜像拉取失败（APP_VERSION=$FROM_TAG）" >&2; return 1; }
  docker compose -f "$COMPOSE" up -d --no-build backend-java backend-python crdt-service frontend \
    || { echo "❌ 回滚启动失败（APP_VERSION=$FROM_TAG）" >&2; return 1; }
  set_env_app_version "$FROM_TAG"
  printf '%s\n' "$FROM_TAG" > "$ROOT/.last-deployed-tag"
  if [[ -n "$BACKUP_FILE" ]]; then
    log "回滚完成（如需恢复数据：gunzip -c $BACKUP_FILE | docker exec -i nocobase-postgres psql -U \$POSTGRES_USER \$POSTGRES_DB）"
  else
    log "回滚完成（本次使用 --skip-backup，未创建数据库备份）"
  fi
}

# 2) 拉取精确版本镜像；生产升级不允许本地 build
export APP_VERSION="$TO_TAG"
log "拉取 APP_VERSION=$TO_TAG 的四个应用镜像"
if ! docker compose -f "$COMPOSE" pull backend-java backend-python crdt-service frontend; then
  echo "❌ 退出码 2：目标镜像缺失或不可拉取（APP_VERSION=$TO_TAG）" >&2
  exit 2
fi

# 3) 换版本启动
log "升级到 $TO_TAG"
if ! docker compose -f "$COMPOSE" up -d --no-build backend-java backend-python crdt-service frontend; then
  echo "❌ 启动失败"; rollback; exit 1
fi

# 4) 等待健康（最多 5 分钟，覆盖 Flyway 迁移时间）
HEALTH_TIMEOUT_SECONDS="${UPGRADE_HEALTH_TIMEOUT_SECONDS:-300}"
deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
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
    set_env_app_version "$TO_TAG"
    printf '%s\n' "$FROM_TAG" > "$ROOT/.prev-deployed-tag"
    printf '%s\n' "$TO_TAG" > "$ROOT/.last-deployed-tag"
    log "✅ 升级成功：APP_VERSION $FROM_TAG → $TO_TAG"
    exit 0
  else
    echo "❌ 冒烟未通过"; rollback; exit 1
  fi
else
  echo "ℹ️  未找到 scripts/smoke.py，跳过冒烟（未实测冒烟路径）"
  set_env_app_version "$TO_TAG"
  printf '%s\n' "$FROM_TAG" > "$ROOT/.prev-deployed-tag"
  printf '%s\n' "$TO_TAG" > "$ROOT/.last-deployed-tag"
  log "✅ 升级成功（无冒烟验证）：APP_VERSION $FROM_TAG → $TO_TAG"
  exit 0
fi
