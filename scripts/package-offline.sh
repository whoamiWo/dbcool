#!/usr/bin/env bash
# =====================================================================
# Wave1 T1.3 — 离线部署包打包（内网/断网安装用）
#
# 用法：
#   bash scripts/package-offline.sh                 # 构建镜像并打包（需联网构建）
#   bash scripts/package-offline.sh --skip-build    # 用本地已有镜像打包
#   bash scripts/package-offline.sh --help
#
# 产物：dist/dbcool-offline-<version>.tar.gz
#   packaging/offline/ 目录含：images.tar / docker-compose.offline.yml /
#     .env.example / install-offline.sh / SHA256SUMS
#
# 退出码：0 = 成功；2 = 前置条件缺失（docker / VERSION / 镜像缺失）
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/packaging/offline"
DIST="$ROOT/dist"
SKIP_BUILD=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1; shift ;;
    --help|-h) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
done

VERSION="${APP_VERSION:-$(cat "$ROOT/VERSION" 2>/dev/null || true)}"
[[ -z "$VERSION" ]] && { echo "❌ 退出码 2：VERSION 缺失"; exit 2; }
command -v docker >/dev/null || { echo "❌ 退出码 2：docker 不可用"; exit 2; }
REGISTRY="${IMAGE_REGISTRY:-ghcr.io/whoamiwo/dbcool}"

mkdir -p "$OUT" "$DIST"

if [[ $SKIP_BUILD -eq 0 ]]; then
  bash "$ROOT/scripts/build-images.sh" --load
fi

# 1) 校验镜像都在本地（缺任一即失败，不打半成品包）
for svc in backend-java backend-python crdt-service frontend; do
  image="$REGISTRY/dbcool-$svc:$VERSION"
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "❌ 退出码 2：本地镜像缺失 $image（先运行 build-images.sh --load）" >&2
    exit 2
  fi
done

# 2) 保存应用镜像为 tar
docker save -o "$OUT/images.tar" \
  "$REGISTRY/dbcool-backend-java:$VERSION" \
  "$REGISTRY/dbcool-backend-python:$VERSION" \
  "$REGISTRY/dbcool-crdt-service:$VERSION" \
  "$REGISTRY/dbcool-frontend:$VERSION"
echo "✅ images.tar 生成（$(du -h "$OUT/images.tar" | cut -f1)）"

# 3) 生成仅含 image: 的离线 compose（去掉 build 段，保证断网可用）
python3 - "$ROOT" "$OUT" <<'PY'
import sys, yaml, pathlib
root, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
doc = yaml.safe_load((root / 'docker-compose.yml').read_text(encoding='utf-8'))
for svc, cfg in doc.get('services', {}).items():
    if isinstance(cfg, dict):
        cfg.pop('build', None)
(out / 'docker-compose.offline.yml').write_text(
    yaml.safe_dump(doc, allow_unicode=True, sort_keys=False), encoding='utf-8')
print('✅ docker-compose.offline.yml 生成（不含 build 段）')
PY

# 4) .env 样例
cat > "$OUT/.env.example" <<EOF
# Wave1 T1.3 离线部署包环境变量样例（拷贝为 .env 后按需修改）
APP_VERSION=$VERSION
IMAGE_REGISTRY=$REGISTRY
POSTGRES_USER=nocobase
POSTGRES_PASSWORD=请设置强密码
POSTGRES_DB=nocobase
MINIO_ROOT_USER=nocobase
MINIO_ROOT_PASSWORD=请设置强密码
JWT_SECRET=请设置至少32位随机密钥
INTERNAL_SERVICE_TOKEN=请设置服务间调用令牌
EOF

# 5) 校验和
(cd "$OUT" && sha256sum images.tar docker-compose.offline.yml .env.example install-offline.sh > SHA256SUMS)
echo "✅ SHA256SUMS 生成"

# 6) 打包
tar -czf "$DIST/dbcool-offline-$VERSION.tar.gz" -C "$ROOT/packaging" offline
echo "✅ 打包完成：$DIST/dbcool-offline-$VERSION.tar.gz（$(du -h "$DIST/dbcool-offline-$VERSION.tar.gz" | cut -f1)）"
