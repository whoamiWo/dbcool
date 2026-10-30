# 安全说明与依赖漏洞扫描

## 1. 默认扫描器（离线可用）

项目默认依赖 `scripts/java-dependency-audit.py`（PHASE104/106 引入），它用**本地已知漏洞库**
`config/known-vulnerabilities.json`（人工维护的 CVE 清单）对 Maven 依赖做比对：

```bash
cd /path/to/repo
python3 scripts/java-dependency-audit.py            # 终端报告
python3 scripts/java-dependency-audit.py \
  --json target/java-audit-report.json \
  --sbom target/java-sbom.json                     # 生成 SBOM + 报告（CI 用）
```

行为约定：
- 退出码 `0` = 无 HIGH/CRITICAL 阻断级漏洞；`1` = 有阻断级；`2` = 执行错误。
- 漏洞库为空 / SBOM 解析失败会**显式报错**（红线：禁止恒真 PASS）。
- 自动探测 `trivy` / `dependency-check` 官方二进制：存在则优先调用，否则回落离线模式
  并打印 `⚠️ 未检测到官方二进制 ... 回落 offline`。

CI 入口：`.github/workflows/ci.yml` 的 `java-dependency-audit` 步骤，把
`target/java-audit-report.json` 与 `target/java-sbom.json` 作为工件上传。

## 2. 可选：集成官方 Trivy / OWASP Dependency-Check

当前仓库默认**不**下载官方二进制（离线/网络受限环境下避免构建失败），
但在有网环境可按下面步骤启用官方扫描作为**二次校验**：

### 2.1 Trivy（推荐）

```bash
# 安装（以 apt 为例）
sudo apt-get install trivy

# 对 Java 依赖做 CVE 扫描（复用同一份 SBOM）
trivy fs --scanners vuln \
  --format json \
  --output target/trivy-report.json \
  backend-java/pom.xml

# 或针对 maven 直接扫
trivy fs backend-java --scanners vuln
```

在 `scripts/java-dependency-audit.py` 中已内置对 `trivy` 二进制的探测；
只要把 `trivy` 装到 `PATH`，脚本会优先走官方扫描并打印
`✅ 检测到 trivy 官方二进制，使用在线/本地数据库模式`。

### 2.2 OWASP Dependency-Check

```bash
# Maven 插件方式（在 backend-java/pom.xml 已声明则直接跑）
mvn org.owasp:dependency-check-maven:check -DautoUpdate=true

# 或独立二进制
dependency-check -d backend-java -o target/dependency-check-report.html
```

同样会被脚本识别（脚本会探测 `dependency-check` 命令并委托调用）。

## 3. 已知漏洞库（`config/known-vulnerabilities.json`）

人工维护的 CVE 清单，字段约定：

| 字段 | 含义 |
|---|---|
| `cve` | CVE 编号 |
| `group` / `artifact` | Maven 坐标 |
| `affected` | 受影响版本范围（Maven 版本比较语法，多区间用 `;` 分隔） |
| `severity` | `LOW` / `MEDIUM` / `HIGH` / `CRITICAL`（HIGH/CRITICAL 阻断构建） |
| `description` | 一句话说明 |
| `fixed_in` | 修复版本号（可留空表示"未知"） |
| `note`（可选） | 补充说明（例如"当前版本不在受影响区间，命中为误报"） |

当前包含 5 条真实 CVE：
JJWT / Apache HttpClient5 / Logstash Logback / MinIO / Logback（不含占位条目）。

新增条目时请：
1. 确认 CVE 的 `group:artifact` 与 Maven 坐标严格一致；
2. `affected` 用 NVD / 上游安全公告的官方区间，不要手写 `>=x,<y`；
3. 若当前项目版本已修复，仍保留条目并填 `note` 说明"实际不受影响"，避免误删；
4. 同步更新本文件的"当前包含 N 条"列表。

## 4. 误报处理

- `CVE-2023-6481`（Logback receiver 反序列化）在 logback ≥ 1.5.x 已移除 receiver 功能，
  当前 1.5.11 **实际不受影响**，扫描器报告视为误报（MEDIUM 级，不阻断构建）。
- 若某 CVE 被证实误报，**不要删除条目**，改为在 `note` 中记录结论；
  这样既保留审计痕迹，也避免未来版本升级时遗漏。

## 5. 前端（npm / pnpm）

前端侧使用 `npm audit` 或 `pnpm audit`：

```bash
cd frontend
npm audit --audit-level=high   # 仅看 high 级阻断

# 生成 JSON 供 CI 归档
npm audit --json > target/npm-audit.json
```

当前前端存在少量 `moderate` 级可接受风险（见 `ARCHITECTURE_TESTING.md` §8），
策略：仅 `high` 级阻断构建，`moderate` 记录在案由安全 owner 定期复核。

## 6. 密钥与注入防护（非漏洞扫描范畴）

- 所有密钥/口令走 `env` 或 K8s Secret，**禁止**写进仓库（`.git-secrets` 钩子拦截）。
- 用户输入在 Service 层已做 SQL 参数化 + XSS 转义，Controller 不做字符串拼接 SQL。
- 审计日志 `audit_log` 对 `user_agent` / `ip` 字段做了 45/250 字符截断防日志注入。

## 6.1 Controller 方法级鉴权（PHASE114）

### 背景：此前的门禁是"假绿"

`ControllerAuthorizationCoverageTest` 原逻辑只比对**新增**未授权项，存量缺口一经写入
`backend-java/docs/controller-authorization-baseline.txt` 即被永久豁免。实测输出：

```
【通过】未授权 215 处，均在基线内（215 条）
Tests run: 1, Failures: 0 ... BUILD SUCCESS
```

即 215 个端点确实没有 `@PreAuthorize`，但门禁显示绿灯 —— 文档声称的"鉴权覆盖"
实为"冻结基线"。这与 `SecurityConfig.java:115` `.anyRequest().authenticated()`
只保证认证、不做角色判定叠加，意味着**任意已登录租户用户可调用任意写端点**。

### 现状：棘轮门禁 + 分层修复

| 项 | 内容 |
|---|---|
| 管理/配置端点 | 34 处补 `@PreAuthorize("hasRole('ADMIN')")` —— 关闭垂直越权 |
| 业务写端点 | 169 处补 `@PreAuthorize("isAuthenticated()")` —— 声明式加固（真正的水平越权由数据层 `findByTenantId` 派生查询承担） |
| 合规公开端点 | 14 条入白名单（第三方 HMAC 验签回调、健康探针、OAuth 登录入口） |
| 门禁 | `docs/authorization-ratchet.txt` 棘轮阈值 = **0**，只降不升 |

### 棘轮机制（防回退）

```bash
# 常规运行：当前缺口数 > 阈值即失败
mvn -o test -Dtest=ControllerAuthorizationCoverageTest

# 仅在修复端点后同步基线行号时使用（同时把阈值下调为新的缺口数）
mvn -o test -Dtest=ControllerAuthorizationCoverageTest -DargLine="-Dauthz.updateBaseline=true"
```

**红线**：不得直接调高 `authorization-ratchet.txt` 的阈值 —— 那等于回到"冻结基线"假绿状态。
已实证可失败：阈值设为 204 而实际 205 时，测试 FAIL 且 exit=1。

### 白名单规则（防偷渡）

- 每条必须带非空 `reason`；
- reason 不得含 `ADMIN` / `需认证` / `需权限` / `TODO` 等掩盖性措辞；
- 引用的源文件必须存在（曾存在指向不存在文件的失效条目）；
- 匹配按**文件路径 + 方法名**，不依赖行号（行号漂移曾导致回调白名单静默失效）。

### 第三方回调的回归陷阱

`SecurityConfig` 对 `/api/{slack,feishu,wecom,mattermost,dingtalk}/...` 回调 `permitAll`，
鉴权靠 Controller 内 HMAC 验签。**这些端点绝不可补 `@PreAuthorize`** —— 回调方无 JWT
会直接 403，导致集成功能中断。修改鉴权注解后必须复跑 E2E 与 `admin-authz-e2e-verify.py`。

### 已处置：JWT secret 无兜底默认值（PHASE114 后续加固）

此前 `application.yml:89` 与 `backend-python/config.py` 的 `jwt_secret` 都带
`dev_jwt_secret_...` 公开默认值：

- Java 侧 `KeyRingService` 已硬阻断**非 dev profile**（生产直跑时抛异常），
  但 `application.yml` 仍保留默认值，dev profile 直跑时生效
- Python 侧仅 warn 不阻断，`minio123` 类回落可被外部配置误用

处置（本次）：
- `application.yml` 改为 `secret: ${JWT_SECRET:?JWT_SECRET 环境变量未设置,参见 DEPLOY.md}`，
  与 `docker-compose.yml:196/277` 的 `${JWT_SECRET:?}` 对齐，**dev profile 直跑也必须显式注入**
- `backend-python/config.py` `jwt_secret` 移除默认值 + 新增 `model_validator`
  生产 profile（非 dev/test/ci 且非 pytest）检测到 `dev_jwt_secret` 即抛 `ValueError`
- 测试由 `backend-python/tests/conftest.py` 注入 CI 专用密钥
  `ci_jwt_secret_at_least_32_chars_for_hs256_algorithm`，与 Java 的
  `application-test.properties:22` 对齐
- `KeyRingService` 非 dev 阻断语义不变（仍抛异常，非 warn）

### 已处置：MinIO 对象存储弱口令兜底移除（PHASE114 后续加固）

`backend-python/services/backup.py:57` 原 `os.environ.get("MINIO_ROOT_PASSWORD", "minio123")`
在「Python 直跑 + `MINIO_ENABLED=true` + 未注入密码」时以弱口令访问对象存储。
compose 路径已由 `${MINIO_ROOT_PASSWORD:?}` 挡住，故仅本地直跑受影响。

处置（与 JWT 同语义，延迟到消费时校验）：
- `_MINIO_SECRET_KEY` 默认改为 `None`（移除 `minio123`）
- 在 MinIO 分支真正使用 `Minio(client)` 前 `if not _MINIO_SECRET_KEY: raise RuntimeError`
  避免模块导入期 `os.environ[...]` 让未启用 MinIO 的部署（默认路径）崩溃
- `tests/conftest.py` 注入 `MINIO_ROOT_PASSWORD=ci_minio_root_password_for_tests`

### 已处置：WebSocket CORS 由 `*` 收敛为同源白名单（PHASE114 后续加固）

**风险面**：原 `/ws/im/**`、`/ws/huddle`、`/ws/alerts` 三处均 `setAllowedOriginPatterns("*")`，
允许任意来源浏览器 JS 发起 WebSocket 握手预检（OPTIONS）。虽 `StompHandshakeInterceptor`（握手阶段）与
`anyRequest().authenticated()`（默认）已在更底层拦截无 JWT 请求，但 `*` 使 CORS 预检阶段泄露
"服务端接受任何来源" 的安全信号，且一旦握手拦截漏掉则直接暴露。

处置（与 REST CORS 白名单 `http://localhost:5173,http://localhost:3000,http://localhost` 对齐）：
- `SecurityConfig#corsConfigurationSource()` 改为读取 `app.cors.allowed-origins`（原 yml 中已定义但未读）
  —— 同步修掉 TECH_DEBT #11 配置漂移
- 3 个 WebSocket 配置类注入 `SecurityConfig`，调用 `securityConfig.allowedOrigins()` 替换 `*`
- 生产同源部署不受影响（CORS 仅在跨源时生效），开发 `localhost:5173` 访问正常

### 已处置：浏览器安全响应头（PHASE115 审计项 #12）

此前全站缺失 `X-Frame-Options` / `X-Content-Type-Options` / `Referrer-Policy` /
`Content-Security-Policy`。在 `frontend/nginx.conf` 及渲染管线补全（实测生效）：

| 头 | 值 | 防护 |
|---|---|---|
| `X-Frame-Options` | `DENY` | 防点击劫持（不被任何 `<iframe>` 嵌入） |
| `X-Content-Type-Options` | `nosniff` | 防 MIME 嗅探降级 |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | 防 Referer 泄露敏感路径 |
| `Content-Security-Policy` | `default-src 'self'; ... frame-ancestors 'none'; ...` | 收敛可加载资源来源 |

**CSP 收紧历史（PHASE129 → PHASE130）**：

- **PHASE129**：移除 `script-src 'unsafe-eval'`（实测 dist/assets/*.js 无 eval/Function 调用）、
  `connect-src` 由 `'self' wss: ws:` 收窄为 `'self'`（同源 WebSocket 用 `'self'` 覆盖）。
- **PHASE130**：`style-src` 由 `'unsafe-inline'` 改为 `'nonce-xxx'` ——
  `vite.config.ts` 构建期在 `index.html` 注入占位符 `__CSP_NONCE__`；容器启动时
  `render_nginx.py` 生成随机 nonce（每次启动唯一），把占位符替换为真实 nonce，
  并同步渲染 `style-src 'nonce-xxx'`；Emotion 的 `CacheProvider` 接入该 nonce
  （`main.tsx` 的 `readCspNonce`），使运行时注入的 `<style>` 标签携带 nonce，彻底移除 `'unsafe-inline'`。
  nonce 注入失败时回退 `'unsafe-inline'` 并告警，避免整站白屏。
- 三处 CSP 声明同步一致（`nginx.conf.template` 80 块 + `/assets/` 块 + `render_nginx.py` TLS_BLOCK）。

**渲染管线（PHASE114 双模式）**：nginx 配置由 `render_nginx.py` 从 `nginx.conf.template`
渲染，安全头需在**两处**同步注入才全模式生效——
- `nginx.conf.template`（80 块：域名模式跳转页 + IP 直连模式）
- `render_nginx.py` 的 `TLS_BLOCK`（443 块）
- 两处 `/assets/` location 因含自身 `add_header Cache-Control` 会阻断 server 级继承，
  故在块内重复声明 4 个头。

**实测验证**：`curl -I` 根路径与 `/assets/` 均返回 4 个头；冒烟 11/11、
编排 8/8、启动检查 0 问题，SPA 与 API 反代无回归（CSP 未破坏前端）。

### 已知未处置（见 `TECH_DEBT.md`）

无。PHASE130 已完成 CSP `style-src` nonce 化，所有安全响应头已收紧至生产就绪。

## 7. PHASE99 官方安全门禁（Trivy + ZAP）

### 7.1 统一入口脚本

新增 `scripts/security-gates.py`（PHASE99），提供三合一命令：

```bash
# 扫描 4 个 Dockerfile 的镜像（需 docker + trivy）
python3 scripts/security-gates.py scan-images --fail-on HIGH,CRITICAL

# 扫描 Java / Python / Node 依赖（需 trivy）
python3 scripts/security-gates.py scan-deps --fail-on HIGH,CRITICAL

# 对运行中的服务跑 ZAP 基线扫描（需 zap + 后端已起）
python3 scripts/security-gates.py zap-baseline --url http://localhost:8080 --fail-on HIGH
```

退出码约定（红线 1）：
- `0` = 无 HIGH/CRITICAL 命中
- `1` = 有 HIGH/CRITICAL 命中（阻断构建）
- `2` = 执行错误（二进制缺失 / 服务未起 / 报告解析失败）

**可失败性验证**：运行 `scripts/security-gates-selfcheck.py`，用合成报告证明
HIGH/CRITICAL 必阻断、损坏报告必报错（不得静默 PASS）。

### 7.2 CI 集成

`.github/workflows/ci.yml` 新增两个 job：

- `security-trivy`: 在 `java-test` / `python-test` / `frontend-test` 之后运行
  - 先安装 trivy + docker
  - 依次跑 `scan-images`（4 个镜像）和 `scan-deps`（3 种依赖）
  - 上传 `target/trivy-*.json` 作为工件

- `security-zap`: 在 `security-trivy` 之后运行
  - 启动 PostgreSQL 服务
  - 构建并启动 Java 后端
  - 安装 OWASP ZAP，跑 `zap-baseline`
  - 上传 `target/zap-baseline-report.*` 作为工件

### 7.3 排除清单

`.trivyignore` 列出排除路径（构建产物、测试夹具、第三方缓存、密钥类文件）。
**红线**：不得用 `*` 通配排除全部目录，仅排除明确不需要扫描的目标。

---
*维护者：安全 Owner（PHASE99 起）；扫描器变更需同步更新 CI `ci.yml`。*
