#!/usr/bin/env python3
"""PHASE114: 渲染 nginx.conf.template → conf.d/default.conf。

从环境变量读取 PUBLIC_HOST，决定 IP 直连模式（80 HTTP）还是域名模式（443 TLS）。
输出到 /etc/nginx/conf.d/default.conf。
"""
import os
import re

TEMPLATE = "/etc/nginx/nginx.conf.template"
OUTPUT = "/etc/nginx/conf.d/default.conf"
NONCE_FILE = "/usr/share/nginx/html/.nonce"
PUBLIC_HOST = os.environ.get("PUBLIC_HOST", "").strip()
SERVER_NAME = PUBLIC_HOST if PUBLIC_HOST else "_"
ENABLE_TLS = "1" if PUBLIC_HOST else "0"

# PHASE130 审计修复：nonce 有效性判定
# crypto.randomBytes(16).toString('base64') 产出 24 字符 base64（含 + / =），此处取 16 为下限留余量
MIN_NONCE_LEN = 16
BASE64_RE = re.compile(r"^[A-Za-z0-9+/=]+$")
# 'undefined'/'null' 全为字母，会通过 base64 正则，必须显式排除（vite 侧 buildStart 异常时的特征值）
INVALID_LITERALS = {"undefined", "null", "none", ""}


def is_valid_nonce(value):
    """判定 nonce 是否为可信的构建期产物。

    需同时满足：非空、非异常字面量、长度达标、字符集为 base64。
    任一条不满足即视为无效，交由调用方回退策略，避免渲染出空 nonce 导致整站样式阻断。
    """
    if not value:
        return False
    value = value.strip()
    if value.lower() in INVALID_LITERALS:
        return False
    if len(value) < MIN_NONCE_LEN:
        return False
    return bool(BASE64_RE.match(value))


# PHASE130: 读取构建期生成的 nonce，用于 style-src nonce 白名单
# 文件缺失或内容无效时回退到 'unsafe-inline'，保证站点可用性优先于策略严格性
try:
    with open(NONCE_FILE, encoding="utf-8") as f:
        NONCE = f.read().strip()
except FileNotFoundError:
    NONCE = ""
    print("[entrypoint] 警告：未找到 .nonce 文件（旧镜像未重新构建？）")

if is_valid_nonce(NONCE):
    STYLE_SRC = "'self' 'nonce-" + NONCE + "'"
    print(f"[entrypoint] 已加载构建期 nonce（长度 {len(NONCE)}），style-src 启用 nonce 白名单")
else:
    STYLE_SRC = "'self' 'unsafe-inline'"
    if NONCE:
        print(f"[entrypoint] 警告：.nonce 内容无效（疑似非 base64 或长度不足 {MIN_NONCE_LEN}），"
              f"style-src 回退到 'unsafe-inline'")
    else:
        print("[entrypoint] 警告：.nonce 为空或缺失，style-src 回退到 'unsafe-inline'")

CSP_VALUE = "default-src 'self'; script-src 'self' 'unsafe-inline'; " \
    "style-src " + STYLE_SRC + "; img-src 'self' data: blob:; " \
    "connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'self';"

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
    "    # PHASE115 安全响应头（443 域名模式）\n" \
    "    add_header X-Frame-Options \"DENY\" always;\n" \
    "    add_header X-Content-Type-Options \"nosniff\" always;\n" \
    "    add_header Referrer-Policy \"strict-origin-when-cross-origin\" always;\n" \
    "    # CSP 说明（PHASE129 收紧 / PHASE130 nonce 化）：\n" \
    "    #   default-src 'self' —— 默认所有资源仅同源\n" \
    "    #   script-src 'self' 'unsafe-inline' —— PHASE129 已移除 'unsafe-eval'\n" \
    "    #   style-src 'self' 'nonce-xxx' —— PHASE130 由构建期 nonce 取代 'unsafe-inline'\n" \
    "    #   connect-src 'self' —— PHASE129 由 'self' wss: ws: 收窄为 'self'\n" \
    "    add_header Content-Security-Policy \"" + CSP_VALUE + "\" always;\n" \
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
    "        # 本块有 add_header，会阻断 server 级安全头继承，故此处重复声明\n" \
    "        add_header X-Frame-Options \"DENY\" always;\n" \
    "        add_header X-Content-Type-Options \"nosniff\" always;\n" \
    "        add_header Referrer-Policy \"strict-origin-when-cross-origin\" always;\n" \
    "        add_header Content-Security-Policy \"" + CSP_VALUE + "\" always;\n" \
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

# 替换占位符
text = text.replace("${SERVER_NAME}", SERVER_NAME)
# PHASE130: 注入 style-src 值（含 nonce）
text = text.replace("${CSP_STYLE_SRC}", STYLE_SRC)

with open(OUTPUT, "w", encoding="utf-8") as f:
    f.write(text)

print(f"[entrypoint] 渲染完成 → {OUTPUT} (ENABLE_TLS={ENABLE_TLS}, SERVER_NAME={SERVER_NAME})")
