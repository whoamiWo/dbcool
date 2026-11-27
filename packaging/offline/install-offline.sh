#!/usr/bin/env bash
# Offline installer: verify package, validate credentials, then deploy and smoke-test.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

VERIFY_ONLY=0
[[ "${1:-}" == "--verify" ]] && VERIFY_ONLY=1

log() { printf '▶ %s\n' "$*"; }
fail() { printf '❌ %s\n' "$*" >&2; exit "${2:-2}"; }

# Python 3 is required for env parsing, Compose interpolation comparison and health probes.
command -v python3 >/dev/null 2>&1 \
  || fail "需要 Python 3（用于环境变量解析、Compose 校验与健康探针），请先在目标主机安装 python3"
compose() {
  local key
  local -a unset_vars=()
  while IFS= read -r key; do
    if [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ && "$key" != PATH && "$key" != HOME ]]; then
      unset_vars+=(-u "$key")
    fi
  done < <(python3 - "$ENV_FILE" <<'PY'
import re
import sys
from pathlib import Path
for raw in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    line = raw.strip()
    if line.startswith("export "):
        line = line[7:].lstrip()
    key, separator, _ = line.partition("=")
    if separator and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key.strip()):
        print(key.strip())
PY
  )
  env "${unset_vars[@]}" docker compose -f docker-compose.offline.yml --env-file "$ENV_FILE" "$@"
}

# First-run deployment requires a human-prepared .env. --verify remains read-only.
if [[ $VERIFY_ONLY -eq 0 && ! -f .env ]]; then
  if [[ "${NON_INTERACTIVE:-}" == "1" ]]; then
    fail "CONFIG_REQUIRED：非交互安装必须预先准备 .env；未执行 Docker 命令"
  fi
  [[ -f .env.example ]] || fail "缺少 .env.example"
  cp .env.example .env
  printf 'CONFIG_REQUIRED：已生成 %s/.env，请填写必需凭证后重新运行安装器。\n' "$HERE" >&2
  exit 2
fi

command -v docker >/dev/null 2>&1 || fail "docker 不可用（离线包需要本机已有 Docker Engine 20.10+）"
docker compose version >/dev/null 2>&1 || fail "docker compose 插件不可用"

# Ensure critical package assets are covered before accepting its checksum list.
[[ -f SHA256SUMS ]] || fail "缺少 SHA256SUMS（包不完整）"
python3 - SHA256SUMS <<'PY' || fail "SHA256SUMS 未覆盖离线安装必需文件"
import sys
from pathlib import Path
required = {"images.tar", "docker-compose.offline.yml", ".env.example",
            "install-offline.sh", "offline-smoke.py", "image-tags.txt"}
listed = set()
try:
    for raw in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
        parts = raw.split(None, 1)
        if len(parts) == 2:
            listed.add(parts[1].lstrip("* ").strip())
except OSError:
    raise SystemExit(1)
raise SystemExit(0 if required <= listed else 1)
PY
log "校验文件完整性"
sha256sum -c SHA256SUMS >/dev/null || fail "SHA256 校验失败 —— 包已损坏，禁止继续安装"

# --verify uses .env when present, otherwise .env.example, and never writes .env.
if [[ -f .env ]]; then
  ENV_FILE=.env
else
  ENV_FILE=.env.example
fi
[[ -f "$ENV_FILE" ]] || fail "缺少 $ENV_FILE"
[[ -f image-tags.txt ]] || fail "离线包缺少 image-tags.txt"
[[ -f offline-smoke.py ]] || fail "离线包缺少 offline-smoke.py"

if [[ -f "$ENV_FILE" ]]; then
  ENV_INSTANCE_ID="$(python3 - "$ENV_FILE" <<'PY'
import re
import sys
from pathlib import Path
for raw in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    line = raw.strip()
    if line.startswith("export "):
        line = line[7:].lstrip()
    key, separator, value = line.partition("=")
    if separator and key.strip() == "OFFLINE_INSTANCE_ID":
        print(value.strip().strip("'\""))
PY
)"
  ENV_INSTANCE_ID="${ENV_INSTANCE_ID:-default}"
  [[ "$ENV_INSTANCE_ID" =~ ^[a-z0-9][a-z0-9_-]{0,31}$ ]] \
    || fail "OFFLINE_INSTANCE_ID 必须为 1-32 位小写字母、数字、下划线或连字符，且以字母或数字开头"
fi

CHECKED_CONFIG="$(python3 - "$ENV_FILE" image-tags.txt "$VERIFY_ONLY" <<'PY'
import base64
import binascii
import re
import sys
from pathlib import Path

services = ("backend-java", "backend-python", "crdt-service", "frontend")
base_images = (
    "pgvector/pgvector:pg16",
    "redis:7.4-alpine",
    "minio/minio:RELEASE.2025-09-07T16-13-09Z",
    "rabbitmq:3.13.7-management-alpine",
)

def parse_env(path):
    result = {}
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        key, separator, value = line.partition("=")
        if not separator:
            continue
        key = key.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            quote = value[0]
            value = value[1:-1]
            if quote == '"':
                value = value.replace(r"\n", "\n").replace(r"\r", "\r").replace(r"\t", "\t")
                value = value.replace(r'\"', '"').replace(r"\\", "\\")
        elif " #" in value:
            value = value.split(" #", 1)[0].rstrip()
        result[key] = value
    return result

def reject(message):
    print(f"配置校验失败：{message}", file=sys.stderr)
    raise SystemExit(2)

try:
    env = parse_env(sys.argv[1])
except (OSError, UnicodeError):
    reject("无法读取环境文件")
version = env.get("APP_VERSION", "").strip()
if not version or not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", version):
    reject("APP_VERSION 缺失或格式无效")
registry = env.get("IMAGE_REGISTRY", "ghcr.io/whoamiwo/dbcool").strip()
if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]*(?::[0-9]+)?(?:/[A-Za-z0-9][A-Za-z0-9_.-]*)*", registry):
    reject("IMAGE_REGISTRY 格式无效")
instance_id = env.get("OFFLINE_INSTANCE_ID", "default").strip()
if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,31}", instance_id):
    reject("OFFLINE_INSTANCE_ID 必须为 1-32 位小写字母、数字、下划线或连字符，且以字母或数字开头")
try:
    manifest = [line.strip() for line in Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()
                if line.strip() and not line.lstrip().startswith("#")]
except (OSError, UnicodeError):
    reject("无法读取 image-tags.txt")
expected_app = [f"{registry}/dbcool-{service}:{version}" for service in services]
expected = set(expected_app) | set(base_images)
if len(manifest) != len(expected) or len(set(manifest)) != len(expected) or set(manifest) != expected:
    reject("image-tags.txt 与 APP_VERSION / IMAGE_REGISTRY / 基础镜像清单不一致")

if sys.argv[3] != "1":
    required = (
        "POSTGRES_USER", "POSTGRES_PASSWORD", "MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD",
        "RABBITMQ_USER", "RABBITMQ_PASSWORD", "JWT_SECRET", "INTERNAL_SERVICE_TOKEN",
        "INTEGRATION_CREDENTIAL_MASTER_KEY",
    )
    placeholders = {"dev_password", "minio123", "guest"}
    for name in required:
        value = env.get(name, "").strip()
        lowered = value.casefold()
        if not value:
            reject(f"缺少必需变量 {name}")
        if (lowered.startswith("changeme_") or lowered.startswith("请设置")
                or lowered.startswith("请用") or lowered in placeholders):
            reject(f"变量 {name} 仍为占位值或默认值")
    try:
        jwt_bytes = base64.b64decode(env["JWT_SECRET"].strip(), validate=True)
    except (ValueError, binascii.Error):
        reject("JWT_SECRET 必须是有效 Base64 且解码后至少 32 字节")
    if len(jwt_bytes) < 32:
        reject("JWT_SECRET 解码后不足 32 字节")
    try:
        master_key = base64.b64decode(env["INTEGRATION_CREDENTIAL_MASTER_KEY"].strip(), validate=True)
    except (ValueError, binascii.Error):
        reject("INTEGRATION_CREDENTIAL_MASTER_KEY 必须是有效 Base64")
    if len(master_key) != 32:
        reject("INTEGRATION_CREDENTIAL_MASTER_KEY 解码后必须恰为 32 字节")

print(f"{version}\t{registry}")
PY
)" || fail "APP_VERSION、镜像清单或必需凭证校验失败"
IFS=$'\t' read -r APP_VERSION IMAGE_REGISTRY <<<"$CHECKED_CONFIG"

# Ask Compose to interpolate using exactly the env file, then compare resolved app tags.
COMPOSE_IMAGES="$(compose config --images 2>/dev/null)" \
  || fail "离线 compose 配置无效（检查 APP_VERSION、镜像清单与必需环境变量）"
python3 - image-tags.txt "$COMPOSE_IMAGES" <<'PY' \
  || fail "离线 compose 应用镜像与 image-tags.txt 不一致"
import re
import sys
from pathlib import Path
apps = re.compile(r"/dbcool-(?:backend-java|backend-python|crdt-service|frontend):")
expected = {line.strip() for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
            if line.strip() and apps.search(line)}
actual = {line.strip() for line in sys.argv[2].splitlines() if apps.search(line)}
raise SystemExit(0 if expected and actual == expected else 1)
PY

if [[ $VERIFY_ONLY -eq 1 ]]; then
  log "PACKAGE_VERIFIED"
  log "DEPLOYMENT_NOT_VERIFIED（未检查本机镜像、未加载镜像、未启动服务）"
  exit 0
fi

# The earlier first-run guard guarantees a human-configured .env before Docker writes.
log "加载镜像 images.tar"
docker load -i images.tar
# Verify every packaged image (4 application + 4 base) has the exact tag from image-tags.txt.
while IFS= read -r image; do
  image="${image%$'\r'}"
  [[ -z "$image" || "$image" == \#* ]] && continue
  docker image inspect "$image" >/dev/null 2>&1 \
    || fail "离线包缺少精确镜像 $image（image-tags.txt 与 images.tar 不一致）"
done < image-tags.txt

log "启动服务（APP_VERSION=$APP_VERSION）"
compose up -d --no-build

services=(postgres redis minio rabbitmq backend-java backend-python crdt-service frontend)
health_timeout="${OFFLINE_HEALTH_TIMEOUT_SECONDS:-300}"
[[ "$health_timeout" =~ ^[0-9]+$ ]] || fail "OFFLINE_HEALTH_TIMEOUT_SECONDS 必须是非负整数"
[[ "${SMOKE_BASE_URL:-}" != *\$* && "${PUBLIC_HOST:-}" != *\$* ]] \
  || fail "SMOKE_BASE_URL / PUBLIC_HOST 不得包含未展开的环境变量表达式"
[[ "${FRONTEND_PORT:-80}" =~ ^[0-9]+$ && "${FRONTEND_TLS_PORT:-443}" =~ ^[0-9]+$ ]] \
  || fail "FRONTEND_PORT / FRONTEND_TLS_PORT 必须是端口数字"
[[ "${JAVA_PORT:-8080}" =~ ^[0-9]+$ && "${PYTHON_PORT:-8000}" =~ ^[0-9]+$ ]] \
  || fail "JAVA_PORT / PYTHON_PORT 必须是端口数字"
deadline=$((SECONDS + health_timeout))
frontend_ready=0
health_failure_service=""
health_pending_service=""

service_state() {
  local service="$1" cid state
  cid="$(compose ps --all -q "$service" 2>/dev/null | tr -d '\r' | head -n 1)"
  if [[ -z "$cid" ]]; then
    printf 'missing|'
    return
  fi
  state="$(docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || true)"
  printf '%s|%s' "$cid" "$state"
}

log "等待 8 个服务逐项健康（超时 ${health_timeout}s）"
while :; do
  all_ready=1
  health_pending_service=""
  for service in "${services[@]}"; do
    result="$(service_state "$service")"
    if [[ "$result" == missing\|* ]]; then
      all_ready=0
      health_pending_service="$service (container missing)"
      continue
    fi
    cid="${result%%|*}"
    state="${result#*|}"
    status="${state%%|*}"
    health="${state#*|}"
    if [[ "$status" == exited || "$status" == dead || "$status" == removing ]]; then
      health_failure_service="$service"
      all_ready=0
      break
    fi
    if [[ "$status" != running ]]; then
      all_ready=0
      health_pending_service="$service (status=$status)"
      continue
    fi
    if [[ "$service" == frontend ]]; then
      if ! python3 offline-smoke.py --env-file .env --frontend-only --quiet; then
        all_ready=0
        health_pending_service="frontend HTTP readiness"
      fi
    elif [[ "$health" == unhealthy ]]; then
      health_failure_service="$service"
      all_ready=0
      break
    elif [[ "$health" != healthy ]]; then
      all_ready=0
      health_pending_service="$service (status=$status health=$health)"
    fi
  done
  if [[ -n "$health_failure_service" ]]; then
    break
  fi
  if [[ "$all_ready" == 1 ]]; then
    frontend_ready=1
    break
  fi
  if (( SECONDS >= deadline )); then
    health_failure_service="${health_pending_service:-readiness timeout}"
    break
  fi
  sleep 5
done

if [[ "$frontend_ready" != 1 ]]; then
  printf '❌ 服务健康检查失败或超时：%s\n' "${health_failure_service:-unknown}" >&2
  failed_service="${health_failure_service%% *}"
  log_text="$(compose logs --tail 40 "$failed_service" 2>&1 || true)"
  LOG_TEXT="$log_text" python3 - .env <<'PY'
import os
import sys
from pathlib import Path
secrets = []
for raw in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    if line.startswith("export "):
        line = line[7:].lstrip()
    _, separator, value = line.partition("=")
    if not separator:
        continue
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    if value:
        secrets.append(value)
text = os.environ.get("LOG_TEXT", "")
for secret in sorted(secrets, key=len, reverse=True):
    text = text.replace(secret, "[REDACTED]")
if text:
    print("服务日志摘要（凭证值已脱敏）：")
    print(text, end="" if text.endswith("\n") else "\n")
PY
  exit 1
fi

log "执行离线 smoke"
python3 offline-smoke.py --env-file .env || fail "OFFLINE_SMOKE_FAILED：离线健康 smoke 未通过" 1

frontend_port="$(python3 - .env <<'PY'
import sys
from pathlib import Path
port = "80"
for raw in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    key, separator, value = raw.partition("=")
    if separator and key.strip() == "FRONTEND_PORT":
        port = value.strip()
print(port)
PY
)"
printf '✅ 安装完成。请访问 http://localhost:%s\n' "$frontend_port"
