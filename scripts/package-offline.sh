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
#    Wave1 T1.3 实测缺陷修复：基础镜像也必须进包 —— 否则断网安装时 compose
#    会尝试联网拉取 postgres/redis/minio/rabbitmq，违背"断网可用"红线。
for svc in backend-java backend-python crdt-service frontend; do
  image="$REGISTRY/dbcool-$svc:$VERSION"
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "❌ 退出码 2：本地镜像缺失 $image（先运行 build-images.sh --load）" >&2
    exit 2
  fi
done
for base in pgvector/pgvector:pg16 redis:7.4-alpine minio/minio:RELEASE.2025-09-07T16-13-09Z rabbitmq:3.13.7-management-alpine; do
  if ! docker image inspect "$base" >/dev/null 2>&1; then
    echo "❌ 退出码 2：基础镜像缺失 $base（离线包必须包含，否则断网安装会联网拉取）" >&2
    exit 2
  fi
done

# 2) 保存全部镜像为 tar（4 应用 + 4 基础）
docker save -o "$OUT/images.tar" \
  "$REGISTRY/dbcool-backend-java:$VERSION" \
  "$REGISTRY/dbcool-backend-python:$VERSION" \
  "$REGISTRY/dbcool-crdt-service:$VERSION" \
  "$REGISTRY/dbcool-frontend:$VERSION" \
  pgvector/pgvector:pg16 redis:7.4-alpine \
  minio/minio:RELEASE.2025-09-07T16-13-09Z rabbitmq:3.13.7-management-alpine
echo "✅ images.tar 生成（$(du -h "$OUT/images.tar" | cut -f1)）"

# 3) 生成仅含 image: 的离线 compose（去掉 build 段，保证断网可用）
python3 - "$ROOT" "$OUT" <<'PY'
import sys, yaml, pathlib
root, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
doc = yaml.safe_load((root / 'docker-compose.yml').read_text(encoding='utf-8'))
for svc, cfg in doc.get('services', {}).items():
    if isinstance(cfg, dict):
        cfg.pop('build', None)
        # Wave1 T1.3 实测缺陷修复：主栈的 container_name 是全局唯一名，
        # 离线栈与主栈并存时会直接 Conflict。离线栈改用 compose 项目前缀命名。
        cfg.pop('container_name', None)
# ⚠️ 数据安全级修复：主 compose 顶级卷显式命名（name: nocobase-pgdata 等），
# 离线栈若原样复用会与主栈共用数据卷 → 串库/覆盖（实测已复现：离线栈直接挂上
# 主栈 pgdata 导致密码不匹配起不来，主栈数据险些被共存写坏）。离线栈强制独立前缀。
for vol_key, vol_cfg in (doc.get('volumes') or {}).items():
    if isinstance(vol_cfg, dict) and 'name' in vol_cfg:
        vol_cfg['name'] = f"dbcool-offline-{vol_key}"
# 同理：网络名显式命名（name: nocobase-net）会让离线栈与主栈共用网络，
# `postgres` 等 DNS 别名解析到主栈容器 → backend 连到主栈数据库（实测 28P01）。
# 离线栈强制独立网络名。
for net_key, net_cfg in (doc.get('networks') or {}).items():
    if isinstance(net_cfg, dict) and 'name' in net_cfg:
        net_cfg['name'] = f"dbcool-offline-{net_key}"
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
RABBITMQ_USER=nocobase
RABBITMQ_PASSWORD=请设置强密码
JWT_SECRET=请设置至少32位随机密钥
INTERNAL_SERVICE_TOKEN=请设置服务间调用令牌
# 凭证加密主密钥（32 字节 Base64）：openssl rand -base64 32
# 未配置时后端启动即 fail-fast（PHASE99 红线）
INTEGRATION_CREDENTIAL_MASTER_KEY=请用 openssl rand -base64 32 生成
# 端口（与主 compose 一致，可按需调整）
POSTGRES_PORT=5432
REDIS_PORT=6379
MINIO_PORT=9000
MINIO_CONSOLE_PORT=9001
RABBITMQ_PORT=5672
RABBITMQ_MGMT_PORT=15672
JAVA_PORT=8080
PYTHON_PORT=8000
CRDT_PORT=3100
FRONTEND_PORT=80
EOF

# 5) 校验和
(cd "$OUT" && sha256sum images.tar docker-compose.offline.yml .env.example install-offline.sh > SHA256SUMS)
echo "✅ SHA256SUMS 生成"

# 6) 打包
tar -czf "$DIST/dbcool-offline-$VERSION.tar.gz" -C "$ROOT/packaging" offline
echo "✅ 打包完成：$DIST/dbcool-offline-$VERSION.tar.gz（$(du -h "$DIST/dbcool-offline-$VERSION.tar.gz" | cut -f1)）"
