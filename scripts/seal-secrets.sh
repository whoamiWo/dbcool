#!/usr/bin/env bash
# =====================================================================
# Wave4 待办池 #4 — Sealed Secrets 密钥密封
#
# 用法：
#   cp k8s/secrets.env.example k8s/secrets.env   # 填真实密钥（该文件不入库）
#   bash scripts/seal-secrets.sh
#
# 产物：k8s/sealed/secret.yaml（SealedSecret，可安全提交进仓库）
# 前置：
#   - kubeseal 二进制（自动下载到 /tmp/kubeseal 若无）
#   - controller 公钥证书：SEAL_CERT 环境变量指向 PEM 文件，
#     或自动尝试 `kubeseal --fetch-cert`（需可访问集群的 sealed-secrets controller）
#
# 退出码：0 = 成功；2 = 前置条件缺失 / 明文输入缺失 / 公钥不可用
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${SECRETS_ENV:-$ROOT/k8s/secrets.env}"
OUT_DIR="$ROOT/k8s/sealed"
NAME="${SEAL_NAME:-nocobase-secret}"
NS="${SEAL_NAMESPACE:-nocobase}"
CERT="${SEAL_CERT:-}"

fail() { echo "❌ 退出码 2：$*" >&2; exit 2; }

[[ -f "$ENV_FILE" ]] || fail "明文输入不存在：$ENV_FILE（先 cp k8s/secrets.env.example k8s/secrets.env 并填真实密钥；该文件已 gitignore，绝不入库）"

# 1) kubeseal
if ! command -v kubeseal >/dev/null 2>&1; then
  if [[ ! -x /tmp/kubeseal ]]; then
    KUBE_SEAL_VERSION="${KUBE_SEAL_VERSION:-0.41.2}"
    echo "▶ 下载 kubeseal（v${KUBE_SEAL_VERSION}）"
    curl -sSL -o /tmp/kubeseal.tar.gz \
      "https://github.com/bitnami-labs/sealed-secrets/releases/download/v${KUBE_SEAL_VERSION}/kubeseal-${KUBE_SEAL_VERSION}-linux-amd64.tar.gz" \
      || fail "kubeseal 下载失败（无网或不可达）"
    tar -xzf /tmp/kubeseal.tar.gz -C /tmp kubeseal || fail "kubeseal 解包失败（下载内容可能不是发布包）"
    chmod +x /tmp/kubeseal
    # 反向自检：必须是 ELF 可执行文件，防止把 HTML 错误页当二进制执行
    if ! head -c 4 /tmp/kubeseal | grep -q ELF; then
      fail "下载的 kubeseal 不是可执行 ELF（版本号或资产名可能已变更）"
    fi
  fi
  KUBESEAL=/tmp/kubeseal
else
  KUBESEAL=$(command -v kubeseal)
fi

# 2) 公钥证书
if [[ -z "$CERT" ]]; then
  if [[ -f /tmp/sealed-cert.pem ]]; then
    CERT=/tmp/sealed-cert.pem
  else
    echo "▶ 尝试从集群 controller 获取公钥（kubeseal --fetch-cert）"
    CERT_OUT=$("$KUBESEAL" --fetch-cert 2>/dev/null || true)
    if [[ -z "$CERT_OUT" ]]; then
      fail "无可用公钥：请用 SEAL_CERT=/path/to/cert.pem 指定（可用 openssl req -x509 自签模拟 controller 做本地验证），或确保 sealed-secrets controller 可访问"
    fi
    printf '%s\n' "$CERT_OUT" > /tmp/sealed-cert.pem
    CERT=/tmp/sealed-cert.pem
  fi
fi

mkdir -p "$OUT_DIR"

# 3) 明文 env → Secret manifest → 管道给 kubeseal 加密为 SealedSecret YAML
echo "▶ 密封 → $OUT_DIR/secret.yaml"
python3 - "$ENV_FILE" "$NAME" "$NS" > /tmp/secret-plain.yaml <<'PYEOF'
import sys
env_file, name, ns = sys.argv[1], sys.argv[2], sys.argv[3]
data = []
for line in open(env_file, encoding='utf-8'):
    line = line.strip()
    if not line or line.startswith('#') or '=' not in line:
        continue
    k, v = line.split('=', 1)
    data.append((k.strip(), v))
print('apiVersion: v1')
print('kind: Secret')
print('metadata:')
print(f'  name: {name}')
print(f'  namespace: {ns}')
print('type: Opaque')
print('stringData:')
for k, v in data:
    print(f'  {k}: "{v}"')
PYEOF
"$KUBESEAL" --cert "$CERT" --namespace "$NS" --name "$NAME" -o yaml \
  < /tmp/secret-plain.yaml > "$OUT_DIR/secret.yaml" \
  || fail "kubeseal 密封失败"

# 4) 校验产物类型正确
if ! grep -q "kind: SealedSecret" "$OUT_DIR/secret.yaml"; then
  fail "产物不是 SealedSecret 类型"
fi
# 反向自检校验点：占位符必须在**密封前**拦截 —— 密文是 base64，
# 在产物里 grep CHANGE_ME 永远查不到（此前版本在校验产物，属无效校验）。
if grep -q "CHANGE_ME" "$ENV_FILE"; then
  fail "明文输入仍含占位符 CHANGE_ME（请填真实密钥，禁止把占位符密封进产物）"
fi
if grep -q "CHANGE_ME" "$OUT_DIR/secret.yaml"; then
  fail "产物仍含占位符（异常：明文已加密时不应出现）"
fi
if ! grep -q "kind: SealedSecret" "$OUT_DIR/secret.yaml"; then
  fail "产物不是 SealedSecret 类型"
fi

echo "✅ 已生成 $OUT_DIR/secret.yaml（可安全提交；部署时由 controller 解密为 Secret $NAME）"
echo "ℹ️  明文输入 $ENV_FILE 保持 gitignore，不入库。"
