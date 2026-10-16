#!/bin/bash
# ============================================================
#  PHASE110: 证书生命周期管理（ACME 签发/续期/到期检查）
#
#  用法:
#    ./scripts/cert-lifecycle.sh issue <domain>     # 首次签发
#    ./scripts/cert-lifecycle.sh renew <domain>     # 手动续期
#    ./scripts/cert-lifecycle.sh check              # 到期检查（供 cron / startup-checklist 调用）
#
#  前置条件:
#    - certbot 已安装
#    - 80 端口可达（ACME HTTP-01 挑战）
#    - nginx 已配置 443 + 证书挂载路径 /etc/nginx/certs/
#
#  退出码:
#    0 = 成功 / 证书有效
#    1 = 失败（签发/续期失败、证书缺失、即将过期）
# ============================================================

set -euo pipefail

CERT_DIR="/etc/nginx/certs"
FULLCHAIN="$CERT_DIR/fullchain.pem"
PRIVKEY="$CERT_DIR/privkey.pem"
RENEWAL_DAYS=30          # 剩余天数少于此值时触发续期
NGINX_CONTAINER="nocobase-frontend"

log()  { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
fail() { echo "❌ $*" >&2; exit 1; }
ok()   { echo "✅ $*"; }

# ------------------------------------------------------------
# 检查证书是否存在且未过期
# ------------------------------------------------------------
check_cert() {
    if [ ! -f "$FULLCHAIN" ] || [ ! -f "$PRIVKEY" ]; then
        log "证书文件缺失: $FULLCHAIN 或 $PRIVKEY"
        return 1
    fi

    # 用 openssl 检查到期时间
    local expiry
    expiry=$(openssl x509 -enddate -noout -in "$FULLCHAIN" 2>/dev/null | cut -d= -f2)
    if [ -z "$expiry" ]; then
        fail "无法解析证书到期时间"
    fi

    local expiry_epoch now_epoch days_left
    expiry_epoch=$(date -d "$expiry" +%s 2>/dev/null || date -j -f "%b %d %T %Y %Z" "$expiry" +%s 2>/dev/null)
    now_epoch=$(date +%s)
    days_left=$(( (expiry_epoch - now_epoch) / 86400 ))

    log "证书剩余有效期: ${days_left} 天"

    if [ "$days_left" -le 0 ]; then
        fail "证书已过期"
    elif [ "$days_left" -le "$RENEWAL_DAYS" ]; then
        log "⚠️  证书即将过期（${days_left} 天），建议续期"
        return 2  # 即将过期
    else
        ok "证书有效，剩余 ${days_left} 天"
        return 0
    fi
}

# ------------------------------------------------------------
# 签发证书（ACME HTTP-01）
# ------------------------------------------------------------
issue_cert() {
    local domain="${1:?用法: cert-lifecycle.sh issue <domain>}"
    log "为 $domain 签发证书..."

    # 确保 nginx 已启动（ACME 挑战需要 80 端口）
    if ! docker ps --format '{{.Names}}' | grep -q "$NGINX_CONTAINER"; then
        fail "nginx 容器 $NGINX_CONTAINER 未运行，无法完成 ACME 挑战"
    fi

    # 使用 certbot standalone 模式（需要 80 端口空闲）
    # 如果 80 端口被 nginx 占用，改用 webroot 模式
    if docker exec "$NGINX_CONTAINER" nginx -t 2>/dev/null; then
        log "nginx 配置有效，使用 webroot 模式..."
        certbot certonly --webroot -w /usr/share/nginx/html \
            -d "$domain" \
            --non-interactive \
            --agree-tos \
            --email="admin@${domain}" \
            --deploy-hook="docker exec $NGINX_CONTAINER nginx -s reload" \
            || fail "certbot 签发失败"
    else
        log "使用 standalone 模式..."
        certbot certonly --standalone \
            -d "$domain" \
            --non-interactive \
            --agree-tos \
            --email="admin@${domain}" \
            --deploy-hook="docker exec $NGINX_CONTAINER nginx -s reload" \
            || fail "certbot 签发失败"
    fi

    # 复制证书到 nginx 挂载路径
    local letsencrypt_dir="/etc/letsencrypt/live/$domain"
    if [ -d "$letsencrypt_dir" ]; then
        cp "$letsencrypt_dir/fullchain.pem" "$FULLCHAIN"
        cp "$letsencrypt_dir/privkey.pem" "$PRIVKEY"
        ok "证书已复制到 $CERT_DIR"
    else
        fail "certbot 输出目录不存在: $letsencrypt_dir"
    fi

    # 重载 nginx
    docker exec "$NGINX_CONTAINER" nginx -s reload 2>/dev/null || true
    ok "nginx 已重载"
}

# ------------------------------------------------------------
# 续期证书
# ------------------------------------------------------------
renew_cert() {
    local domain="${1:?用法: cert-lifecycle.sh renew <domain>}"
    log "续期 $domain 的证书..."

    certbot renew \
        --non-interactive \
        --deploy-hook="docker exec $NGINX_CONTAINER nginx -s reload" \
        || fail "certbot 续期失败"

    # 复制新证书
    local letsencrypt_dir="/etc/letsencrypt/live/$domain"
    if [ -d "$letsencrypt_dir" ]; then
        cp "$letsencrypt_dir/fullchain.pem" "$FULLCHAIN"
        cp "$letsencrypt_dir/privkey.pem" "$PRIVKEY"
        ok "新证书已复制到 $CERT_DIR"
    fi

    docker exec "$NGINX_CONTAINER" nginx -s reload 2>/dev/null || true
    ok "nginx 已重载"
}

# ------------------------------------------------------------
# 主入口
# ------------------------------------------------------------
main() {
    local cmd="${1:-check}"
    shift || true

    case "$cmd" in
        issue)
            issue_cert "${1:?缺少 domain 参数}"
            ;;
        renew)
            renew_cert "${1:?缺少 domain 参数}"
            ;;
        check)
            check_cert
            ;;
        *)
            echo "用法: $0 {issue <domain>|renew <domain>|check}"
            exit 1
            ;;
    esac
}

main "$@"
