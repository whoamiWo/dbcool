#!/usr/bin/env python3
"""PHASE114: 渲染 nginx.conf.template → conf.d/default.conf。

从环境变量读取 PUBLIC_HOST，决定 IP 直连模式（80 HTTP）还是域名模式（443 TLS）。
输出到 /etc/nginx/conf.d/default.conf。

PHASE130 审计后改造：nonce 由「构建期」改为「容器启动期」生成并注入 index.html。
每次容器启动 nonce 都会变化，避免旧镜像里被长期共享的静态 nonce；
同时废弃独立的 dist/.nonce 文件（该文件此前可被直接 GET）。
"""
import os
import re
import secrets

TEMPLATE = "/etc/nginx/nginx.conf.template"
OUTPUT = "/etc/nginx/conf.d/default.conf"
INDEX_HTML = "/usr/share/nginx/html/index.html"
NONCE_PLACEHOLDER = "__CSP_NONCE__"
PUBLIC_HOST = os.environ.get("PUBLIC_HOST", "").strip()

# Wave4 待办池 #13：蓝绿发布 —— 后端服务名按 BACKEND_COLOR 切换。
# blue 用现网 backend-java / backend-python；green 用 *-green 副本（蓝绿切换时
# 由 scripts/blue-green-deploy.sh 在 nginx 容器内重写并重载，容器重启时本脚本
# 按 BACKEND_COLOR 保持一致）。
BACKEND_COLOR = os.environ.get("BACKEND_COLOR", "blue").strip().lower()
if BACKEND_COLOR not in {"blue", "green"}:
    BACKEND_COLOR = "blue"
JAVA_BACKEND = "backend-java-green" if BACKEND_COLOR == "green" else "backend-java"
PYTHON_BACKEND = "backend-python-green" if BACKEND_COLOR == "green" else "backend-python"
SERVER_NAME = PUBLIC_HOST if PUBLIC_HOST else "_"
ENABLE_TLS = "1" if PUBLIC_HOST else "0"

MIN_NONCE_LEN = 16
# 允许标准 base64（+ / =）与 URL-safe base64（- _）
NONCE_RE = re.compile(r"^[A-Za-z0-9+/=_-]+$")
# 异常字面量（占位符未替换、JS undefined 等）需显式排除，它们全为字母会通过字符集校验
INVALID_LITERALS = {"undefined", "null", "none", "", NONCE_PLACEHOLDER.lower()}


def is_valid_nonce(value):
    """判定 nonce 是否为可信随机值。

    需同时满足：非空、非异常字面量/占位符、长度达标、字符集合法。
    """
    if not value:
        return False
    value = value.strip()
    if value.lower() in INVALID_LITERALS:
        return False
    if len(value) < MIN_NONCE_LEN:
        return False
    return bool(NONCE_RE.match(value))


def generate_nonce():
    """生成 URL-safe 随机 nonce（约 32 字符）。"""
    return secrets.token_urlsafe(24)


def inject_nonce_into_html(nonce):
    """把 index.html 里的占位符替换为真实 nonce。

    返回 True 表示替换成功；False 表示失败（文件缺失、无占位符或写回失败）。
    """
    try:
        with open(INDEX_HTML, encoding="utf-8") as f:
            html = f.read()
    except OSError as e:
        print(f"[entrypoint] 警告：读取 index.html 失败（{e}），nonce 注入跳过")
        return False

    if NONCE_PLACEHOLDER not in html:
        print("[entrypoint] 警告：index.html 未找到 nonce 占位符"
              f"（{NONCE_PLACEHOLDER}），可能未重新构建，nonce 注入跳过")
        return False

    try:
        with open(INDEX_HTML, "w", encoding="utf-8") as f:
            f.write(html.replace(NONCE_PLACEHOLDER, nonce))
    except OSError as e:
        print(f"[entrypoint] 警告：写回 index.html 失败（{e}），nonce 注入跳过")
        return False

    return True


# PHASE130: 容器启动期生成 nonce → 注入 index.html → 填入 CSP。
# 任一环节失败则回退 'unsafe-inline'，保证站点可用性优先于策略严格性。
NONCE = generate_nonce()
if is_valid_nonce(NONCE) and inject_nonce_into_html(NONCE):
    STYLE_SRC = "'self' 'nonce-" + NONCE + "'"
    print(f"[entrypoint] 已生成容器级 nonce（长度 {len(NONCE)}）并注入 index.html，"
          f"style-src 启用 nonce 白名单")
else:
    NONCE = ""
    STYLE_SRC = "'self' 'unsafe-inline'"
    print("[entrypoint] 警告：nonce 生成或注入失败，style-src 回退到 'unsafe-inline'"
          "（此时 index.html 中的占位符会残留，但 unsafe-inline 放行所有 style，不会白屏）")

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
    "    #   style-src 'self' 'nonce-xxx' —— PHASE130 由容器启动期 nonce 取代 'unsafe-inline'\n" \
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
    "        proxy_pass http://" + JAVA_BACKEND + ":8080;\n" \
    "        proxy_set_header Host $host;\n" \
    "        proxy_set_header X-Real-IP $remote_addr;\n" \
    "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n" \
    "        proxy_set_header X-Forwarded-Proto $scheme;\n" \
    "    }\n" \
    "\n" \
    "    location /api/ai/ {\n" \
    "        proxy_pass http://" + PYTHON_BACKEND + ":8000;\n" \
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
