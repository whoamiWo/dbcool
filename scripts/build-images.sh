#!/usr/bin/env bash
# =====================================================================
# Wave1 T1.1 — 应用镜像版本化构建（多架构）
#
# 用法：
#   bash scripts/build-images.sh                      # 构建 4 个应用镜像并 push（需 docker login）
#   bash scripts/build-images.sh --no-push            # 仅构建（离线打包用）
#   bash scripts/build-images.sh --load               # 构建并 load 到本地 docker（单架构）
#   bash scripts/build-images.sh --help
#
# 版本来源：根目录 VERSION（Wave0 单一真相源），可用 APP_VERSION 覆盖。
# 镜像名：${IMAGE_REGISTRY:-ghcr.io/whoamiwo/dbcool}/dbcool-<svc>:<version>
#
# 退出码：0 = 成功；2 = 前置条件缺失（VERSION 不存在 / docker 不可用）
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REGISTRY="${IMAGE_REGISTRY:-ghcr.io/whoamiwo/dbcool}"
PLATFORM="${PLATFORM:-linux/amd64,linux/arm64}"
PUSH=1
LOAD=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-push) PUSH=0; shift ;;
    --load)    LOAD=1; PUSH=0; shift ;;
    --help|-h) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
done

# --load 只能加载单架构镜像到本地 daemon；未显式指定 PLATFORM 时收敛为当前架构
if [[ $LOAD -eq 1 && -z "${PLATFORM:-}" ]]; then
  PLATFORM="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')"
fi

VERSION="${APP_VERSION:-$(cat "$ROOT/VERSION" 2>/dev/null || true)}"
if [[ -z "$VERSION" ]]; then
  echo "❌ 退出码 2：VERSION 文件缺失且未设置 APP_VERSION" >&2
  exit 2
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "❌ 退出码 2：docker 不可用" >&2
  exit 2
fi

# Wave4：backend-java 的 Dockerfile 用 `COPY target/nocobase-backend-*.jar ./`，
# 若 target 里残留上一版本的 jar（如 2.1.1 与 2.1.2 同时存在），COPY 会因
# "more than one source file" 直接失败（实测）。因此构建前先 clean package，
# 保证 target 中只有当前版本的一个主 jar（.jar.original 不匹配 *.jar）。
if [[ "${SKIP_JAVA_PACKAGE:-0}" != "1" ]]; then
  # 注意：不用 -o —— CI runner 无 Maven 缓存时离线模式必然失败（v2.1.2 首次发布实测）；
  # 本地离线构建可用 SKIP_JAVA_PACKAGE=1 跳过（需自行保证 target jar 存在）。
  echo "▶ backend-java: mvn clean package -DskipTests"
  (cd "$ROOT/backend-java" && mvn clean package -DskipTests -q)
fi

# 服务 → Dockerfile/上下文
declare -A CONTEXT=(
  [backend-java]="$ROOT/backend-java"
  [backend-python]="$ROOT/backend-python"
  [crdt-service]="$ROOT/crdt-service"
  [frontend]="$ROOT/frontend"
)

for svc in backend-java backend-python crdt-service frontend; do
  ctx="${CONTEXT[$svc]}"
  if [[ ! -f "$ctx/Dockerfile" ]]; then
    echo "❌ 退出码 2：$ctx/Dockerfile 不存在" >&2
    exit 2
  fi
  image="$REGISTRY/dbcool-$svc:$VERSION"
  echo "▶ 构建 $image [$PLATFORM]"
  args=(buildx build --platform "$PLATFORM" -t "$image" -f "$ctx/Dockerfile" "$ctx")
  if [[ $PUSH -eq 1 ]]; then
    args+=(--push)
  elif [[ $LOAD -eq 1 ]]; then
    args+=(--load)
  else
    args+=(--output=type=cacheonly)
  fi
  docker "${args[@]}"
  echo "✅ $image"
done

echo "完成：版本 $VERSION，registry=$REGISTRY，push=$PUSH"
