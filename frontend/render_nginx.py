#!/usr/bin/env python3
"""PHASE114: 渲染 nginx.conf.template → conf.d/default.conf。

从环境变量读取 PUBLIC_HOST，决定 IP 直连模式（80 HTTP）还是域名模式（443 TLS）。
输出到 /etc/nginx/conf.d/default.conf。
"""
import os
import re

TEMPLATE = "/etc/nginx/nginx.conf.template"
OUTPUT = "/etc/nginx/conf.d/default.conf"
PUBLIC_HOST = os.environ.get("PUBLIC_HOST", "").strip()
SERVER_NAME = PUBLIC_HOST if PUBLIC_HOST else "_"
ENABLE_TLS = "1" if PUBLIC_HOST else "0"

# 域名模式 443 server 块（仅 ENABLE_TLS=1 注入）
TLS_BLOCK = "\n# ---------- HTTPS 443 server 块（域名模式） ----------\n" \
    "server {\n" \
    "    listen 443 ssl;\n" \
    "    http2 on;\n" \
    "    server_name " + SERVER_NAME + ";\n" \
    "\n" \
    "    ssl_certificate     /etc/nginx/certs/fullchain.pem;\n" \
    "    ssl_certificate_key /etc/nginx/certs/privkey.pem;\n" \
    "    ssl_protocols       TLSv1.2 TLSv1.3;\n" \
    "    ssl_ciphers         HIGH:!aNULL:!MD5;\n" \
    "    ssl_prefer_server_ciphers on;\n" \
    "    ssl_session_cache   shared:SSL:10m;\n" \
    "    ssl_session_timeout 10m;\n" \
    "\n" \
    "    add_header Strict-Transport-Security \"max-age=63072000; includeSubDomains\" always;\n" \
    "\n" \
    "    root /usr/share/nginx/html;\n" \
    "    index index.html;\n" \
    "\n" \
    "    gzip on;\n" \
    "    gzip_types text/plain text/css application/javascript application/json image/svg+xml;\n" \
    "    gzip_min_length 1024;\n" \
    "\n" \
    "    location /api/ {\n" \
    "        proxy_pass http://backend-java:8080;\n" \
    "        proxy_set_header Host $host;\n" \
    "        proxy_set_header X-Real-IP $remote_addr;\n" \
    "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n" \
    "        proxy_set_header X-Forwarded-Proto $scheme;\n" \
    "    }\n" \
    "\n" \
    "    location /api/ai/ {\n" \
    "        proxy_pass http://backend-python:8000;\n" \
    "        proxy_set_header Host $host;\n" \
    "        proxy_set_header X-Real-IP $remote_addr;\n" \
    "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n" \
    "        proxy_set_header X-Forwarded-Proto $scheme;\n" \
    "    }\n" \
    "\n" \
    "    location /assets/ {\n" \
    "        expires 1y;\n" \
    "        add_header Cache-Control \"public, immutable\";\n" \
    "        try_files $uri =404;\n" \
    "    }\n" \
    "\n" \
    "    location / {\n" \
    "        try_files $uri $uri/ /index.html;\n" \
    "    }\n" \
    "\n" \
    "    location /health {\n" \
    "        proxy_pass http://backend-java:8080/api/health;\n" \
    "        proxy_set_header Host $host;\n" \
    "        proxy_connect_timeout 3s;\n" \
    "        proxy_read_timeout 5s;\n" \
    "        access_log off;\n" \
    "    }\n" \
    "}\n"


def resolve_if_tls(m):
    """__IF_TLS__(if) __ELSE__(else) __ENDIF__ → 按模式取 if/else 块。"""
    return m.group(1) if ENABLE_TLS == "1" else m.group(2)


with open(TEMPLATE, encoding="utf-8") as f:
    text = f.read()

# 条件块：域名模式保留 __IF_TLS__ 块，IP 模式保留 __ELSE__ 块
text = re.sub(r"__IF_TLS__(.*?)__ELSE__(.*?)__ENDIF__", resolve_if_tls, text, flags=re.DOTALL)

# __TLS_BLOCK__ 占位行
if ENABLE_TLS == "1":
    text = text.replace("__TLS_BLOCK__\n", TLS_BLOCK + "\n")
else:
    text = text.replace("__TLS_BLOCK__\n", "")

# 替换 server_name 占位符
text = text.replace("${SERVER_NAME}", SERVER_NAME)

with open(OUTPUT, "w", encoding="utf-8") as f:
    f.write(text)

print(f"[entrypoint] 渲染完成 → {OUTPUT} (ENABLE_TLS={ENABLE_TLS}, SERVER_NAME={SERVER_NAME})")
