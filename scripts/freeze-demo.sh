#!/usr/bin/env bash
# R13 — 演示代码冻结脚本
#
# 用法:
#   ./scripts/freeze-demo.sh v0.5.0-demo "首次演示版本"
#
# 效果:
#   - 创建 git tag v0.X.Y-demo
#   - 创建/更新 release/demo 分支(指针同 main)
#   - 记录 git log 最近 50 commits 到 docs/RELEASE_NOTES.md
#   - 输出版本号 + commit hash + changelog 摘要

set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <version-tag> [release-note]"
  echo "Example: $0 v0.5.0-demo '首次正式演示版本'"
  exit 1
fi

VERSION="$1"
NOTE="${2:-}"

# 1. 校验 — 必须在 main 分支且 working tree clean
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$BRANCH" != "main" ]; then
  echo "❌ 当前分支 $BRANCH,必须在 main 上冻结演示代码" >&2
  exit 1
fi

if ! git diff-index --quiet HEAD --; then
  echo "❌ working tree 有未提交改动,先 commit 或 stash" >&2
  exit 1
fi

# 2. 校验 — mvn verify 必须通过
echo "🔍 跑 mvn verify(必须全绿)..."
if ! mvn -q verify > /tmp/demo-freeze-verify.log 2>&1; then
  echo "❌ mvn verify 失败,先修测试。日志: /tmp/demo-freeze-verify.log" >&2
  exit 1
fi
echo "✅ mvn verify BUILD SUCCESS"

# 3. 创建 tag
COMMIT=$(git rev-parse --short HEAD)
if git rev-parse "$VERSION" >/dev/null 2>&1; then
  echo "⚠️  tag $VERSION 已存在,删除后重建"
  git tag -d "$VERSION"
fi
git tag -a "$VERSION" -m "演示冻结 ${VERSION}

${NOTE}

commit: ${COMMIT}
date:   $(date -u +%Y-%m-%dT%H:%M:%SZ)
"
echo "✅ 创建 tag $VERSION @ $COMMIT"

# 4. 同步 release/demo 分支
if git show-ref --verify --quiet refs/heads/release/demo; then
  git branch -f release/demo HEAD
  echo "✅ 更新 release/demo → $COMMIT"
else
  git branch release/demo HEAD
  echo "✅ 创建 release/demo → $COMMIT"
fi

# 5. 生成 release notes
git log --pretty=format:"- %h %s (%an, %ad)" --date=short -50 > docs/RELEASE_NOTES.md
echo "" >> docs/RELEASE_NOTES.md
echo "" >> docs/RELEASE_NOTES.md
echo "Frozen: $VERSION @ $COMMIT ($(date -u +%Y-%m-%dT%H:%M:%SZ))" >> docs/RELEASE_NOTES.md
echo "✅ 写入 docs/RELEASE_NOTES.md"

# 6. 提示用户
echo ""
echo "════════════════════════════════════════"
echo "演示代码冻结完成:"
echo "  Tag:           $VERSION"
echo "  Branch:        release/demo @ $COMMIT"
echo "  Release notes: docs/RELEASE_NOTES.md"
echo ""
echo "下一步:"
echo "  git push origin $VERSION release/demo"
echo "  ./scripts/unfreeze-demo.sh  # 出问题时回滚"
echo "════════════════════════════════════════"
