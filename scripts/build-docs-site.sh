#!/usr/bin/env bash
# =====================================================================
# Wave2 T2.2 — 文档站构建
#
# 用法：
#   bash scripts/build-docs-site.sh           # 同步核心文档 + vitepress build
#   bash scripts/build-docs-site.sh --dev     # 同步后进入 vitepress dev
#
# 机制：docs/ 下核心 runbook 复制进 docs-site/pages/（单一事实源仍是 docs/），
#       站点只引用这份白名单，避免 222 篇工程文档全部变成页面。
# 退出码：0 = 成功；2 = npm/vitepress 不可用或构建失败
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SITE="$ROOT/docs-site"
PAGES="$SITE/pages"
DEV=0
[[ "${1:-}" == "--dev" ]] && DEV=1

command -v npm >/dev/null 2>&1 || { echo "❌ 退出码 2：npm 不可用"; exit 2; }

# 白名单：与 docs-site/.vitepress/config.mts 的 sidebar 同源维护
CORE_DOCS=(
  WAVE1_PRIVATE_DELIVERY_TASKS.md
  upgrade-runbook.md
  backup-restore-runbook.md
  key-rotation-runbook.md
  release-rollout-runbook.md
  install-support-matrix.md
  tenant-isolation-audit-sample.md
  PLATFORM_BENCHMARK_ANALYSIS_2026-10-30.md
  PRODUCTIZATION_ROADMAP.md
  DEVELOPMENT_NOTES.md
)
ROOT_DOCS=(SECURITY.md TROUBLESHOOTING.md)

mkdir -p "$PAGES"
for f in "${CORE_DOCS[@]}"; do
  if [[ -f "$ROOT/docs/$f" ]]; then cp "$ROOT/docs/$f" "$PAGES/"; else
    echo "❌ 退出码 2：核心文档缺失 docs/$f"; exit 2; fi
done
for f in "${ROOT_DOCS[@]}"; do
  if [[ -f "$ROOT/$f" ]]; then cp "$ROOT/$f" "$PAGES/"; else
    echo "❌ 退出码 2：核心文档缺失 $f"; exit 2; fi
done

# Wave2 T2.2：VitePress 把正文中的 {{ }} 当 Vue 模板表达式渲染（代码块除外），
# runbook 里的 docker inspect '{{.State.Health.Status}}' 之类会直接构建失败。
# 同步时把非代码块行的 {{ 转成 HTML 实体（渲染结果不变，Vue 不再解析）。
python3 - "$PAGES" <<'PY'
import sys, pathlib
pages = pathlib.Path(sys.argv[1])
for md in pages.glob('*.md'):
    out, in_fence = [], False
    for line in md.read_text(encoding='utf-8').splitlines():
        if line.strip().startswith('```'):
            in_fence = not in_fence
            out.append(line); continue
        if not in_fence and '{{' in line:
            line = line.replace('{{', '&#123;&#123;')
        out.append(line)
    md.write_text('\n'.join(out) + '\n', encoding='utf-8')
print('✅ 非代码块 {{ 已转义（VitePress 兼容）')
PY
echo "✅ 核心文档同步完成（${#CORE_DOCS[@]} + ${#ROOT_DOCS[@]} 篇）"

cd "$SITE"
if [[ ! -d node_modules ]]; then
  echo "▶ 安装 vitepress"
  npm install --no-audit --no-fund
fi

if [[ $DEV -eq 1 ]]; then
  exec npx vitepress dev
fi

npx vitepress build
echo "✅ 文档站构建完成：$SITE/.vitepress/dist"
