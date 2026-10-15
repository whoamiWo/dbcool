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

当前包含 6 条（5 真实 CVE + 1 占位）：
JJWT / Apache HttpClient5 / Logstash Logback / MinIO / Logback + 占位 `CVE-2024-0000-EXAMPLE`。

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
