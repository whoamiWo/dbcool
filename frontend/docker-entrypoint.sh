#!/bin/sh
# ============================================================
#  NocoBase 前端 — Nginx 配置渲染入口（PHASE114）
#  PUBLIC_HOST 为空 → IP 直连模式（仅 80 HTTP，无 TLS）
#  PUBLIC_HOST 有值 → 域名模式（80 跳 443 + TLS + HSTS）
#  渲染后 nginx -t 校验，拦截真实语法错误；放行 upstream 解析类提示。
# ============================================================
set -e

PUBLIC_HOST="${PUBLIC_HOST:-}"

echo "[entrypoint] 模式：PUBLIC_HOST='${PUBLIC_HOST}'"

# 渲染 nginx 模板
python3 /render_nginx.py

# nginx -t 用默认主配置 /etc/nginx/nginx.conf（自动 include conf.d/*）
# 在 standalone 容器里 backend-java upstream 不可达时 nginx -t 会报 host not found；
# compose 网络内 DNS 可解析，生产无影响。仅拦截真实语法错误。
echo "[entrypoint] 执行 nginx -t 语法校验..."
if nginx -t 2>&1 | grep -q "test is successful"; then
    echo "[entrypoint] 配置校验通过"
elif nginx -t 2>&1 | grep -q "host not found in upstream\|could not resolve"; then
    echo "[entrypoint] 提示：upstream 在当前网络不可解析（compose 内正常），继续启动"
else
    echo "[entrypoint] ERROR：nginx 配置校验失败，拒绝启动" >&2
    nginx -t 2>&1
    cat /etc/nginx/conf.d/default.conf >&2
    exit 1
fi

echo "[entrypoint] 启动 nginx..."
exec nginx -g 'daemon off;'
