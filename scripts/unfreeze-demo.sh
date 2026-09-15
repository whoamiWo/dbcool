#!/usr/bin/env bash
# R13 — 紧急回滚脚本
#
# 用法:
#   ./scripts/unfreeze-demo.sh v0.5.0-demo
#
# 效果:
#   - checkout 指定 tag 到 main(创建临时 backup 分支)
#   - 备份当前 main 到 main-backup-{ts}
#   - 演示结束恢复用 ./scripts/restore-main.sh

set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <version-tag>"
  echo "Example: $0 v0.5.0-demo"
  exit 1
fi

TAG="$1"

# 校验 tag 存在
if ! git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "❌ tag $TAG 不存在" >&2
  exit 1
fi

# 备份当前 main
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_BRANCH="main-backup-$TIMESTAMP"
git branch "$BACKUP_BRANCH" main
echo "✅ 备份当前 main 到 $BACKUP_BRANCH"

# 切到 tag
git checkout "$TAG" 2>&1 | sed 's/^/  /'
echo ""
echo "════════════════════════════════════════"
echo "已切到 $TAG:"
echo "  $(git log -1 --pretty='%h %s (%ad)' --date=short)"
echo ""
echo "演示完成恢复 main:"
echo "  git checkout main"
echo "  git reset --hard $BACKUP_BRANCH  # 若 main 需回滚"
echo "════════════════════════════════════════"
