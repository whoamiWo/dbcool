#!/bin/bash
# ============================================================
#  生成 CN=localhost 自签名占位证书到 certs/ 目录
#  用于 IP 直连模式下的卷挂载（避免 Docker 创建目录而非文件）
#  用法：bash scripts/gen-selfsigned-cert.sh
# ============================================================

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CERTS_DIR="$PROJECT_ROOT/certs"
FULLCHAIN="$CERTS_DIR/fullchain.pem"
PRIVKEY="$CERTS_DIR/privkey.pem"

CYAN='\033[36m'
GREEN='\033[32m'
YELLOW='\033[33m'
RESET='\033[0m'

log() { echo -e "${CYAN}[gen-cert]${RESET} $*"; }
ok()  { echo -e "${GREEN}✅${RESET} $*"; }
warn(){ echo -e "${YELLOW}⚠️${RESET} $*"; }

# 检查 openssl 是否可用
if ! command -v openssl &>/dev/null; then
    echo "❌ openssl 未安装，请先安装: sudo apt install openssl (或 brew install openssl)" >&2
    exit 1
fi

# 确保 certs 目录存在
mkdir -p "$CERTS_DIR"

# 检查证书是否已存在且有效
if [ -f "$FULLCHAIN" ] && [ -f "$PRIVKEY" ]; then
    # 验证证书有效期
    if openssl x509 -in "$FULLCHAIN" -checkend 0 -noout 2>/dev/null; then
        # 检查证书主体是否为 localhost
        subject=$(openssl x509 -in "$FULLCHAIN" -noout -subject 2>/dev/null)
        if echo "$subject" | grep -qi "localhost"; then
            ok "证书已存在且有效：$FULLCHAIN"
            echo ""
            echo -e "${CYAN}证书信息:${RESET}"
            openssl x509 -in "$FULLCHAIN" -noout -dates -subject 2>&1
            exit 0
        fi
    fi
    warn "现有证书可能无效或不是 localhost，将覆盖"
fi

log "生成 CN=localhost 自签名证书..."
log "目标: $CERTS_DIR"

# 生成自签名证书 (有效期 10 年)
openssl req -x509 -newkey rsa:2048 \
    -keyout "$PRIVKEY" \
    -out "$FULLCHAIN" \
    -days 3650 \
    -nodes \
    -subj "/C=CN/ST=Local/L=Local/O=Self-Signed/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
    2>/dev/null

# 设置权限
chmod 600 "$PRIVKEY"
chmod 644 "$FULLCHAIN"

# 验证证书
if openssl x509 -in "$FULLCHAIN" -checkend 0 -noout 2>/dev/null; then
    ok "证书生成成功"
    echo ""
    echo -e "${CYAN}证书信息:${RESET}"
    openssl x509 -in "$FULLCHAIN" -noout -dates -subject -issuer 2>&1
    echo ""
    echo -e "${CYAN}私钥路径:${RESET} $PRIVKEY"
    echo -e "${CYAN}完整链路径:${RESET} $FULLCHAIN"
else
    echo "❌ 证书生成失败" >&2
    exit 1
fi
