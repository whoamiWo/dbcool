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
# 发布构建可证性（H2b）：
#   默认要求工作树干净 —— 镜像 label 的 revision 必须真的等于镜像内容来源。
#   若确实需要脏构建，设置 ALLOW_DIRTY_BUILD=1：revision 会标记为 <sha>-dirty，
#   并写入 io.dbcool.build.diff-sha256（工作树 diff + 未跟踪文件内容的 sha256）。
#   .kilo/ 是本地工具配置，不参与构建，也不计入脏判定。
#
# 退出码：0 = 成功；2 = 前置条件缺失（VERSION 不存在 / docker 不可用 / 工作树脏且未允许）
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# GHCR 仓库名必须全小写（v2.1.2 发布实测：whoamiWo 含大写被拒）—— 统一转小写
REGISTRY="$(echo "${IMAGE_REGISTRY:-ghcr.io/whoamiwo/dbcool}" | tr '[:upper:]' '[:lower:]')"
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

# 镜像可证性（H2）：把构建时的 commit / 版本 / 源码地址写进 OCI label，
# 事后可用 `docker inspect` 证明运行中的镜像到底包含哪个提交，而不是靠时间戳推断。
REVISION_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
REVISION="$REVISION_SHA"
SOURCE_URL="${IMAGE_SOURCE:-https://github.com/whoamiWo/dbcool}"

# H2b：revision label 只有在干净工作树上才可信。旧行为直接用 `git rev-parse HEAD`，
# 构建时若有未提交改动，镜像内容 ≠ revision —— 发布证据会被误读。
if command -v git >/dev/null 2>&1 && git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  # .kilo/ 是本地工具状态（不入库），不参与构建，从脏判定中排除。
  GIT_STATUS="$(git -C "$ROOT" status --porcelain | grep -Ev '^.. \.kilo/' || true)"
  if [[ -n "$GIT_STATUS" ]]; then
    if [[ "${ALLOW_DIRTY_BUILD:-0}" != "1" ]]; then
      echo "❌ 退出码 2：工作树有未提交改动，不能生成可证 revision 的发布镜像。" >&2
      echo "   先提交（或设置 ALLOW_DIRTY_BUILD=1 接受 <sha>-dirty 标签）：" >&2
      echo "$GIT_STATUS" | sed 's/^/     /' >&2
      exit 2
    fi
    # 记录工作树状态的 sha256：tracked diff + 未跟踪文件内容，二者共同标识构建输入。
    DIFF_SHA="$(
      {
        git -C "$ROOT" diff HEAD --binary
        while IFS= read -r -d '' f; do
          case "$f" in .kilo/*) continue ;; esac
          printf 'untracked:%s\n' "$f"
          sha256sum "$ROOT/$f" 2>/dev/null || true
        done < <(git -C "$ROOT" ls-files --others --exclude-standard -z)
      } | sha256sum | awk '{print $1}'
    )"
    REVISION="${REVISION_SHA}-dirty"
    echo "⚠️  ALLOW_DIRTY_BUILD=1：工作树不干净，revision 标记为 $REVISION"
    echo "   dirty-diff-sha256=$DIFF_SHA（不得作为发布镜像冒充干净 commit）"
  fi
fi

# label 必须使用最终 revision（脏构建为 <sha>-dirty），否则会冒充干净 commit。
LABEL_ARGS=(
  --label "org.opencontainers.image.revision=$REVISION"
  --label "org.opencontainers.image.version=$VERSION"
  --label "org.opencontainers.image.source=$SOURCE_URL"
)
if [[ "${REVISION}" == *-dirty ]]; then
  LABEL_ARGS+=(--label "io.dbcool.build.dirty=true")
  LABEL_ARGS+=(--label "io.dbcool.build.diff-sha256=${DIFF_SHA:-unknown}")
fi
echo "▶ 构建来源：version=$VERSION revision=$REVISION"

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

if [[ -n "${PUSH_BY_DIGEST:-}" ]]; then
  METADATA_DIR="${BUILD_METADATA_DIR:-${RUNNER_TEMP:-$ROOT/target/build-metadata}}"
  mkdir -p "$METADATA_DIR"
fi

for svc in backend-java backend-python crdt-service frontend; do
  ctx="${CONTEXT[$svc]}"
  if [[ ! -f "$ctx/Dockerfile" ]]; then
    echo "❌ 退出码 2：$ctx/Dockerfile 不存在" >&2
    exit 2
  fi
  image="$REGISTRY/dbcool-$svc:$VERSION"
  if ! docker buildx version >/dev/null 2>&1; then
    # 无 buildx 的 docker 安装（2026-11-12 本环境实测 docker 29.1.3 无插件）：
    # 多架构 manifest 必须 buildx，不能用经典 build 冒充 —— 显式拒绝并给指引。
    if [[ "$PLATFORM" == *,* ]]; then
      echo "❌ 退出码 2：本机 docker 无 buildx 插件，无法构建多架构 [$PLATFORM]。" >&2
      echo "   单架构本地/推送：PLATFORM=linux/$(uname -m | sed 's/x86_64/amd64/') bash scripts/build-images.sh" >&2
      echo "   多架构发布请在带 buildx 的环境（如 CI）执行。" >&2
      exit 2
    fi
    echo "▶ 构建 $image [$PLATFORM]（无 buildx，经典 build + push）"
    docker build --platform "$PLATFORM" -t "$image" "${LABEL_ARGS[@]}" \
      -f "$ctx/Dockerfile" "$ctx"
    if [[ $PUSH -eq 1 ]]; then
      docker push "$image"
    fi
    echo "✅ $image"
    # 本地镜像必须留下可核对的 image ID + revision label（容器 image ID 要能对上）。
    docker image inspect -f '   image id={{.Id}} revision={{index .Config.Labels "org.opencontainers.image.revision"}} version={{index .Config.Labels "org.opencontainers.image.version"}}' "$image"
    continue
  fi
  echo "▶ 构建 $image [$PLATFORM]"
  args=(buildx build --platform "$PLATFORM" "${LABEL_ARGS[@]}" -f "$ctx/Dockerfile" "$ctx")
  if [[ $PUSH -eq 1 ]]; then
    if [[ -n "${PUSH_BY_DIGEST:-}" ]]; then
      # 多架构 release：先按 digest 推送，统一由 imagetools 创建版本 manifest。
      # 各架构绝不能直接覆盖同一个版本 tag；metadata 是 digest 的发布证据。
      if [[ "$PLATFORM" == *,* ]]; then
        echo "❌ PUSH_BY_DIGEST=1 要求单架构 PLATFORM，实际为 $PLATFORM" >&2
        exit 2
      fi
      mkdir -p "${BUILD_METADATA_DIR:-${RUNNER_TEMP:-$ROOT/target/build-metadata}}"
      args+=(--push --output=type=image,push-by-digest=true,name-canonical=true
        --metadata-file="${BUILD_METADATA_DIR:-${RUNNER_TEMP:-$ROOT/target/build-metadata}}/${svc}.json")
    else
      args+=(-t "$image" --push)
    fi
  elif [[ $LOAD -eq 1 ]]; then
    args+=(--load)
  else
    args+=(--output=type=cacheonly)
  fi
  docker "${args[@]}"
  if [[ -n "${PUSH_BY_DIGEST:-}" ]]; then
    python3 - "$svc" "$image" "${BUILD_METADATA_DIR:-${RUNNER_TEMP:-$ROOT/target/build-metadata}}/${svc}.json" <<'PY'
import json, sys
svc, image, path = sys.argv[1:]
metadata = json.load(open(path, encoding="utf-8"))
digest = metadata["containerimage.digest"]
if not digest.startswith("sha256:"):
    raise SystemExit(f"invalid image digest for {svc}: {digest!r}")
print(f"{svc}@{digest}")
PY
  fi
  echo "✅ $image"
  if [[ $PUSH -eq 0 && $LOAD -eq 1 ]]; then
    docker image inspect -f '   image id={{.Id}} revision={{index .Config.Labels "org.opencontainers.image.revision"}} version={{index .Config.Labels "org.opencontainers.image.version"}}' "$image"
  fi
done

echo "完成：版本 $VERSION，revision=$REVISION，registry=$REGISTRY，push=$PUSH"
